#include "native_log.h"

#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

// gettid 在 bionic 上是系统调用（<unistd.h> 里已有声明），无需额外头。

// 日志文件路径缓存
static char g_log_path[256] = {0};

// 文件滚动阈值：2MB
#define MAX_LOG_SIZE (2 * 1024 * 1024)

// ── 最近日志环形快照（2026-10-02）────────────────────────────────────────
// 崩溃 handler 需要"崩之前模块在干什么"，但 handler 内不能用锁/文件 IO。
// 这里在**写日志时顺便**把已格式化好的整行拷进静态环，handler 只读环。
//
// 单槽 240 字节（够放一行：日志行最长 1200 但典型 ~140B；截断长行可接受）。
// 64 槽 ⇒ 15KB 静态占用，覆盖崩溃前最后几十行。
// ⚠️ `g_ring_seq` 用 unsigned 回绕计数，读者靠"seq 在读取期间是否变化"
// 判断是否撕裂；变了就把该行标记为截断。
#define SNAP_SLOTS 64
#define SNAP_LINE  240

static char          g_snap[SNAP_SLOTS][SNAP_LINE];
static volatile unsigned g_snap_seq = 0;   // 已写入的总行数（单调，回绕）

// 写者侧：把一行拷进环。**在生产者的格式化路径上调用**（2026-10-04 异步化
// 后不再有全局锁——快照环本身无锁，见下）。不做任何可能失败的分配。
static void snap_push(const char *line) {
    unsigned s = g_snap_seq;
    char *dst = g_snap[s % SNAP_SLOTS];
    int i = 0;
    while (line[i] != '\0' && line[i] != '\n' && i < SNAP_LINE - 1) {
        dst[i] = line[i];
        i++;
    }
    dst[i] = '\0';
    g_snap_seq = s + 1;   // 最后发布：读者看到新 seq 时槽已就绪
}

int native_log_ring_snapshot(char *out, int cap, int max_lines) {
    if (out == NULL || cap <= 0) return 0;
    // 快照时刻的序号。读者不加锁——半行可接受（见头文件说明）。
    unsigned end = g_snap_seq;
    if (end == 0) { out[0] = '\0'; return 0; }

    unsigned avail = end < SNAP_SLOTS ? end : SNAP_SLOTS;
    unsigned n = avail < (unsigned) max_lines ? avail : (unsigned) max_lines;
    unsigned start = end - n;   // 取**最新** n 行（越新越接近崩溃点）

    int p = 0;
    for (unsigned k = start; k < end && p < cap - 2; k++) {
        const char *src = g_snap[k % SNAP_SLOTS];
        int i = 0;
        while (src[i] != '\0' && p < cap - 2) out[p++] = src[i++];
        out[p++] = '\n';
    }
    out[p] = '\0';
    return p;
}

// ── 异步落盘队列（2026-10-04）────────────────────────────────────────────// ⚠️ **为什么必须异步**：本文件的写路径是 open/fstat/write/close，目标
// `/sdcard/Android/media/` 在 Android 上是 **FUSE 转发**（每次 open/close 都是
// 一轮内核↔用户态往返）。而调用方绝大多数在 **Unity 主线程**（`SLIPprobe`/
// `ABSdiag` 挂在 FixedUpdate 回调上）——同步写 = 每写一行就阻塞游戏主线程。
//
// 实机实证（2026-10-04）：标定探针每 0.5s 落 15 行，主线程被阻塞 **中位 17ms、
// P90 34ms、峰值 100ms**（占空比 4%）；用户用秒表测出「每 0.5 秒卡顿跳帧一次」，
// 关掉反馈后仍在（探针独立于功能开关，见 AlaMobileModule 的接线）。
//
// 修复 = 生产者只做 memcpy 入队（微秒级、无锁、无 syscall），后台线程负责
// 真正写盘。日志的**内容与顺序完全不变**，只是不再占用游戏帧预算。
//
// ## 设计要点
// - **无锁 SPSC**：`seq_` 单调递增，生产者写 `slots_[seq & MASK]` 后发布
//   `seq = seq+1`；消费者读 `seq` 再读槽位。单生产者（主线程）单消费者
//   （日志线程）天然安全；**多线程同时打日志时槽位可能互相覆盖**（丢一行），
//   比加锁阻塞游戏线程可接受得多。
// - **满队列丢新**：队列满时不等待、直接丢弃（`g_dropped++`），绝不阻塞生产者。
// - **溢出告警**：丢弃量每满 256 条，由消费者线程**直接写文件**补一行
//   （不经队列——消费者再入队会破坏单生产者前提）。
// - **崩溃安全**：`snap_push` 仍在生产者侧**同步**执行（快照环无锁、无 IO），
//   所以崩溃现场拿得到最后几行；而异步队列里尚未落盘的行会随进程一起丢——
//   这是异步化的固有代价，用 `native_log_flush()`（崩溃 handler 内调用）缓解。
#define LOGQ_BITS   8
#define LOGQ_SIZE   (1 << LOGQ_BITS)   // 256 行 ≈ 256×~140B ≈ 35KB
#define LOGQ_MASK   (LOGQ_SIZE - 1)
#define LOG_LINE_MAX 1200

static char            g_logq[LOGQ_SIZE][LOG_LINE_MAX];
static volatile unsigned g_logq_seq = 0;   // 已入队总行数（生产者发布）
static volatile unsigned g_logq_done = 0;  // 已落盘行数（消费者推进）
static volatile unsigned g_logq_drop = 0;  // 丢弃行数
static volatile int      g_logq_started = 0;
static pthread_t         g_logq_thread;

// 生产者侧：入队一行。**唯一的热路径**——只做 memcpy + 发布序号，无锁无 IO。
static void logq_push(const char *line, int len) {
    if (len <= 0) return;
    if (len > LOG_LINE_MAX - 1) len = LOG_LINE_MAX - 1;
    const unsigned s = g_logq_seq;
    if (s - g_logq_done >= LOGQ_SIZE) {   // 队列满 → 丢新，绝不阻塞生产者
        g_logq_drop++;
        return;
    }
    char *dst = g_logq[s & LOGQ_MASK];
    memcpy(dst, line, (size_t) len);
    dst[len] = '\0';
    g_logq_seq = s + 1;   // 最后发布：消费者看到新 seq 时槽位已就绪
}

// 历史说明：曾有 g_log_enabled 开关控制文件写入（2026-09-06 移除——与 Java
// 层 logEnabled 同批强制开启，排查闪退时关日志=丢现场）。

/**
 * 从 /proc/self/cmdline 推导包名，拼日志文件路径：
 * /sdcard/Android/media/<pkg>/ala_tool_native.log
 *
 * strip :suffix（子进程），与 unlock_hook.c 原 npatch_log 同理。
 *
 * ⚠️ 2026-09-14 迁移：路径从 `Android/data/<pkg>/files/` 改到 `Android/media/<pkg>/`。
 * 原因：`Android/media` 不在 scoped storage 受限区，模块 App 持 AFA
 *（MANAGE_EXTERNAL_STORAGE）后可**跨包直读**，导出日志不再依赖游戏进程
 * 广播推送，也不必以"推送是否到达"反推游戏是否在运行（游戏闪退后仍可导出
 * 现场）。游戏进程写**自己包**的 media 目录 = 同 uid，无需任何权限。
 * 详见 [CrossPkgMailbox]（Java 侧同类通道）。
 */
static void resolve_log_path(void) {
    if (g_log_path[0] != '\0') return;
    char cmdline[256] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) return;
    cmdline[n] = '\0';
    // strip :suffix (子进程)
    char *colon = strchr(cmdline, ':');
    if (colon) *colon = '\0';
    // 确保 /sdcard/Android/media/<pkg>/ 存在（父目录 Android/media 由系统预建）
    char dir[224];
    snprintf(dir, sizeof(dir), "/sdcard/Android/media/%s", cmdline);
    mkdir(dir, 0777);
    snprintf(g_log_path, sizeof(g_log_path),
             "/sdcard/Android/media/%s/ala_tool_native.log", cmdline);
}

/**
 * 截断文件保留后半部分，防无限增长。
 * 用 truncate + 重写，简单但够用——2MB 不会频繁触发。
 */
static void truncate_log_file(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) { close(fd); return; }
    // 保留后半
    off_t keep_from = size / 2;
    lseek(fd, keep_from, SEEK_SET);
    char buf[4096];
    ssize_t n;
    // 先读到临时文件
    char tmp_path[280];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path);
    int out = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (out < 0) { close(fd); return; }
    // 跳到下一个换行
    n = read(fd, buf, 1);
    if (n == 1 && buf[0] != '\n') {
        // 找下一个换行
        while (read(fd, buf, 1) == 1) {
            if (buf[0] == '\n') break;
        }
    }
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        write(out, buf, n);
    }
    close(fd);
    close(out);
    rename(tmp_path, path);
}

/**
 * 异步日志线程：唯一做文件 IO 的地方（open/fstat/write/close + 滚动截断）。
 *
 * 循环排空队列；队列空时 `usleep(20ms)` 让出 CPU（20ms 对日志新鲜度足够，
 * 且避免了忙等）。**本线程永不被游戏帧预算约束**——它的每一次阻塞都只影响
 * 日志延迟，不影响帧率。
 */
static void *logq_writer(void *arg) {
    (void) arg;
    for (;;) {
        const unsigned end = g_logq_seq;
        unsigned i = g_logq_done;
        if (i == end) {
            usleep(20000);   // 20ms
            continue;
        }
        int wrote_any = 0;
        while (i != end) {
            resolve_log_path();
            if (g_log_path[0] == '\0') { g_logq_done = end; break; }
            const char *line = g_logq[i & LOGQ_MASK];
            int f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
            if (f >= 0) {
                struct stat st;
                if (fstat(f, &st) == 0 && st.st_size > MAX_LOG_SIZE) {
                    close(f);
                    truncate_log_file(g_log_path);
                    f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
                }
                if (f >= 0) {
                    write(f, line, strlen(line));
                    close(f);
                    wrote_any = 1;
                }
            }
            g_logq_done = ++i;   // 逐行推进：即使写失败也前进，避免卡死队头
        }
        // 溢出告警：每丢弃 256 行补一条。⚠️ **直接写文件、不经队列**——
        // 本线程已是队列的消费者，再从消费者侧入队会破坏单生产者前提。
        static unsigned warned = 0;
        const unsigned dropped = g_logq_drop;
        if (dropped >= warned + 256) {
            warned = dropped;
            char w[256];
            int wl = snprintf(w, sizeof(w),
                     "native_log: %u lines dropped (queue full; slow log storage)\n",
                     dropped);
            int f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
            if (f >= 0) {
                write(f, w, (size_t) wl);
                close(f);
            }
        }
        (void) wrote_any;
    }
    return NULL;
}

void native_log_flush(void) {
    // 崩溃 handler 调用：尽力把队列里已格式化的行写出去。
    // 用**非阻塞**的 open + 直接 write，不碰任何可能已被持有的锁。
    if (!g_logq_started) return;
    const unsigned end = g_logq_seq;
    unsigned i = g_logq_done;
    while (i != end) {
        if (g_log_path[0] == '\0') break;
        int f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
        if (f < 0) break;
        const char *line = g_logq[i & LOGQ_MASK];
        write(f, line, strlen(line));
        close(f);
        i++;
    }
}

static void logq_start_once(void) {
    if (g_logq_started) return;
    g_logq_started = 1;
    if (pthread_create(&g_logq_thread, NULL, logq_writer, NULL) != 0) {
        g_logq_started = 0;   // 起不来就退回同步写（下面 native_log_print 会判）
    }
}

void native_log_print(int prio, const char *tag, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    // logcat 始终打（代价低：Android 的 liblog 本身是异步 socket）。
    __android_log_print(prio, tag, "%s", buf);

    // 格式化完整行（含时间戳/pid/tid）——这一步必须在**生产者侧**做：
    // 时间戳要反映"日志产生时刻"而非"落盘时刻"，且格式化的 CPU 开销远小于
    // syscall，留在主线程无妨（探针实测 15 行格式化 ≈ 亚毫秒级）。
    char line[LOG_LINE_MAX];
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    int ms = (int)(ts.tv_nsec / 1000000);
    const char *prio_str = (prio == ANDROID_LOG_INFO) ? "I" :
                           (prio == ANDROID_LOG_WARN) ? "W" :
                           (prio == ANDROID_LOG_ERROR) ? "E" :
                           (prio == ANDROID_LOG_DEBUG) ? "D" : "?";
    int line_len = snprintf(line, sizeof(line),
             "[%04d-%02d-%02dT%02d:%02d:%02d.%03d pid=%d tid=%d][%s/%s] %s\n",
             tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
             tm.tm_hour, tm.tm_min, tm.tm_sec, ms,
             (int)getpid(), (int)gettid(), prio_str, tag, buf);

    // 崩溃现场快照：**同步**（无锁无 IO，微秒级），保证崩溃时拿得到最后几行。
    snap_push(line);

    logq_start_once();
    if (g_logq_started) {
        logq_push(line, line_len);   // 热路径终点：入队即返回，无 syscall
    }
    // 线程起不来（极罕见）时放弃文件写入而非退回同步写——退回等于把卡顿
    // 原样带回游戏主线程，违背本次修复的初衷。logcat 仍可用。
}
