// ═══════════════════════════════════════════════════════════════════════════
// crash_hook.c — 游戏进程 native 崩溃自捕（信号级，async-signal-safe）
//
// 目的：游戏进程 SIGSEGV 类闪退目前零堆栈（Java CrashCatcher 只装模块进程，
// 不覆盖 native 信号；logcat crash buffer 用户拿不到）。场景加载窗口竞态
// 崩溃（2026-09-06 日志实证：LLV.Awake 后无声死亡，50% 复现率）需要现场
// 数据定案——PC 相对模块偏移可直接对拍 OffsetTable 定位元凶 hook。
//
// 设计红线：
// - handler 内只用 async-signal-safe 调用：open/read/write/close/fstat/
//   ftruncate/lseek + snprintf（bionic 标注 signal-safety）+ 手写十六进制/
//   十进制转换。localtime/gmtime/malloc 均 NOT safe → 时间戳用
//   CLOCK_REALTIME + 手动 UTC 拆解（牺牲时区，崩溃文件按事件顺序读）。
// - 链式转发：写完必须调旧 handler（Unity/系统可能装过，吞掉会破坏
//   崩溃上报）。旧 handler 不可恢复时恢复默认 + 重发信号自杀——让系统
//   照常生成 tombstone。
// - 单次触发：进入 handler 先置重入标志，handler 内再崩直接自杀。
// - 体积上限：追加模式，超 512KB 截断保留后半（与 Java 版同策略）。
//
// 落盘位置：/sdcard/Android/data/<游戏包>/files/ala_tool_crash_native.log
//（与 ala_tool_native.log 同目录——native_log 已实证游戏进程对该目录可写，
// LogExporter 策略 3 可读到它）。
// ═══════════════════════════════════════════════════════════════════════════
#include "crash_hook.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "AlaMobileTool"
#define CRASH_FILE_MAX (512 * 1024)

// ── 旧 handler 链 ──
typedef struct {
    bool valid;                // 安装前存在 handler（含系统默认 SIG_DFL 也记）
    bool has_siginfo;          // 旧 handler 走 sa_sigaction 还是 sa_handler
    struct sigaction act;      // 安装前的 sigaction
} old_handler_t;

static old_handler_t g_old[32];
static volatile sig_atomic_t g_in_handler = 0;  // 重入防护
static char g_crash_path[256] = {0};
static bool g_installed = false;

// 备用信号栈：栈溢出型 SIGSEGV（ pedal 写线程递归/深调用是候选场景）发生时
// 正常栈已不可写，handler 必须跑在 sigaltstack 上才有活路。SA_ONSTACK 只有
// 配合这里的 sigaltstack 才真正生效。
static char g_sigstack_mem[SIGSTKSZ * 4];  // 64KB 级，静态分配无 malloc

// ── async-signal-safe 时间戳（UTC，牺牲时区）──
static void safe_utc_timestamp(char *buf, int buf_len) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long sec = ts.tv_sec;
    int days = (int) (sec / 86400);
    int rem = (int) (sec % 86400);
    if (rem < 0) { rem += 86400; days -= 1; }
    int hh = rem / 3600, mm = (rem % 3600) / 60, ss = rem % 60;
    // days → y/m/d（civil_from_days，Howard Hinnant 公有领域算法）
    long z = days + 719468;
    long era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned doe = (unsigned) (z - era * 146097);
    unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    long y = (long) yoe + era * 400;
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    unsigned d = doy - (153 * mp + 2) / 5 + 1;
    unsigned m = mp < 10 ? mp + 3 : mp - 9;
    y += (m <= 2);
    snprintf(buf, buf_len, "%04ld-%02u-%02u %02d:%02d:%02d.%03d UTC",
             y, m, d, hh, mm, ss, (int) (ts.tv_nsec / 1000000));
}

// ── /proc/self/maps 解析：addr 所在模块路径 + 段内偏移 ──
// handler 内安全：open/read/close + 手写解析。读期间映射可能变化，
// 残行丢弃（崩溃瞬间小概率，可接受）。偏移 = addr - 段起始（r-xp 段
// 基址与 get_module_base/OffsetTable 对拍口径一致）。
static bool find_module_for_addr(uintptr_t addr, char *module, int module_len, uintptr_t *out_off) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[1024];
    size_t have = 0;
    bool found = false;
    module[0] = '\0';
    *out_off = 0;
    while (!found) {
        ssize_t n = read(fd, buf + have, sizeof(buf) - have - 1);
        if (n <= 0) break;
        have += (size_t) n;
        buf[have] = '\0';
        char *line = buf;
        char *nl;
        while ((nl = strchr(line, '\n')) != NULL && !found) {
            *nl = '\0';
            // 行: start-end perms offset dev inode path
            uintptr_t lo = 0, hi = 0;
            const char *p = line;
            // start
            while (*p == ' ') p++;
            for (; *p >= '0' && *p <= '9'; p++) lo = lo * 16 + (uintptr_t) (*p - '0');
            if (*p != '-') { line = nl + 1; continue; }
            p++;
            for (; *p >= '0' && *p <= '9'; p++) hi = hi * 16 + (uintptr_t) (*p - '0');
            if (addr >= lo && addr < hi) {
                // 跳 perms/offset/dev/inode 取 path（最后一段，可能缺失）
                const char *path = NULL;
                const char *q = line;
                int spaces_seen = 0;
                // 找到行内最后一个 " /" 形式的路径起点（path 总以 '/' 开头）
                while (*q) {
                    if (*q == '/' && q > line && *(q - 1) == ' ') path = q;
                    q++;
                }
                (void) spaces_seen;
                if (path != NULL) {
                    char clean[256];
                    int ci = 0;
                    for (const char *r = path; *r && ci < 255; r++) clean[ci++] = *r;
                    clean[ci] = '\0';
                    snprintf(module, module_len, "%s", clean);
                } else {
                    snprintf(module, module_len, "(anonymous)");
                }
                *out_off = addr - lo;
                found = true;
            }
            line = nl + 1;
        }
        // 残行移到头部
        if (!found && line != buf) {
            have -= (size_t) (line - buf);
            memmove(buf, line, have + 1);
        } else if (!found) {
            have = 0;  // 单行超缓冲，丢弃
        }
    }
    close(fd);
    return found;
}

// ── async-signal-safe 追加写入（带 512KB 截断保护）──
static void append_report(const char *data, size_t len) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > CRASH_FILE_MAX) {
        // 保留后半：ftruncate 粗粒度截断（可能切半行，崩溃文件可接受）
        off_t keep_from = st.st_size / 2;
        lseek(fd, keep_from, SEEK_SET);
        ftruncate(fd, keep_from);
        lseek(fd, 0, SEEK_END);
    }
    size_t written = 0;
    while (written < len) {
        ssize_t w = write(fd, data + written, len - written);
        if (w <= 0) {
            if (w == -1 && errno == EINTR) continue;
            break;
        }
        written += (size_t) w;
    }
    close(fd);
}

// ── 核心 handler ──
static void crash_handler(int sig, siginfo_t *info, void *uctx) {
    // 重入：崩溃处理中再崩 → 恢复默认重发自杀（防死循环）
    if (g_in_handler) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    g_in_handler = 1;

    // 1. 现场：PC + fault addr + 关键寄存器（arm64 ucontext）
    uintptr_t pc = 0, lr = 0, sp = 0, fault = 0;
    unsigned long x0 = 0, x1 = 0, x2 = 0, x3 = 0;
    ucontext_t *uc = (ucontext_t *) uctx;
    if (uc != NULL) {
        pc = (uintptr_t) uc->uc_mcontext.pc;
        lr = (uintptr_t) uc->uc_mcontext.regs[30];
        sp = (uintptr_t) uc->uc_mcontext.sp;
        x0 = (unsigned long) uc->uc_mcontext.regs[0];
        x1 = (unsigned long) uc->uc_mcontext.regs[1];
        x2 = (unsigned long) uc->uc_mcontext.regs[2];
        x3 = (unsigned long) uc->uc_mcontext.regs[3];
    }
    if (info != NULL) fault = (uintptr_t) info->si_addr;

    // 2. PC 模块定位
    char module[256] = "(unknown)";
    uintptr_t mod_off = 0;
    bool located = (pc != 0) && find_module_for_addr(pc, module, sizeof(module), &mod_off);
    char lr_module[256] = "(unknown)";
    uintptr_t lr_off = 0;
    bool lr_located = (lr != 0) && find_module_for_addr(lr, lr_module, sizeof(lr_module), &lr_off);

    // 3. 组装报告（单缓冲，一次 append）
    char report[1600];
    char ts[64];
    safe_utc_timestamp(ts, sizeof(ts));
    int len = snprintf(report, sizeof(report),
        "\n=== native crash ===\n"
        "[%s pid=%d tid=%d] signal=%s(%d) fault_addr=%p\n"
        "pc=0x%lx  =>  %s +0x%lx\n"
        "lr=0x%lx  =>  %s +0x%lx\n"
        "sp=0x%lx x0=%016lx x1=%016lx x2=%016lx x3=%016lx\n"
        "located=%d\n",
        ts, (int) getpid(), (int) gettid(),
        (sig == SIGSEGV) ? "SIGSEGV" : (sig == SIGBUS) ? "SIGBUS" :
        (sig == SIGFPE) ? "SIGFPE" : (sig == SIGILL) ? "SIGILL" :
        (sig == SIGABRT) ? "SIGABRT" : (sig == SIGTRAP) ? "SIGTRAP" : "SIG?",
        sig, (void *) fault,
        (unsigned long) pc, module, (unsigned long) mod_off,
        (unsigned long) lr, lr_module, (unsigned long) lr_off,
        (unsigned long) sp, x0, x1, x2, x3,
        located ? 1 : 0);
    if (len > 0) append_report(report, (size_t) len);

    // 4. 链式转发旧 handler（绝不能吞——Unity/系统崩溃上报依赖它）
    old_handler_t *old = (sig < 32) ? &g_old[sig] : NULL;
    if (old != NULL && old->valid) {
        if (old->has_siginfo) {
            old->act.sa_sigaction(sig, info, uctx);
        } else {
            old->act.sa_handler(sig);
        }
        // 旧 handler 返回（未杀进程）→ 恢复默认重发自杀，防同一指令反复重入
        signal(sig, SIG_DFL);
        raise(sig);
    } else {
        signal(sig, SIG_DFL);
        raise(sig);
    }
}

// ── 安装 ──
void crash_catcher_install(void) {
    if (g_installed) return;

    // 落盘路径：与 native_log 同目录（/proc/self/cmdline 推导包名）
    char cmdline[256] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
        close(fd);
        if (n > 0) {
            cmdline[n] = '\0';
            char *colon = strchr(cmdline, ':');
            if (colon) *colon = '\0';  // strip :suffix 子进程
            snprintf(g_crash_path, sizeof(g_crash_path),
                     "/sdcard/Android/data/%s/files/ala_tool_crash_native.log", cmdline);
        }
    }
    if (g_crash_path[0] == '\0') {
        // cmdline 读取失败：退到游戏 externalFilesDir 无法定位，用裸路径兜底
        snprintf(g_crash_path, sizeof(g_crash_path),
                 "/sdcard/Android/data/files/ala_tool_crash_native.log");
    }

    const int signals[] = { SIGSEGV, SIGBUS, SIGFPE, SIGILL, SIGABRT, SIGTRAP };
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;  // ONSTACK：栈溢出型 SIGSEGV 也进得来
    sigemptyset(&sa.sa_mask);

    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_sigstack_mem;
    ss.ss_size = sizeof(g_sigstack_mem);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) {
        int sig = signals[i];
        struct sigaction old;
        memset(&old, 0, sizeof(old));
        if (sigaction(sig, NULL, &old) == 0) {
            g_old[sig].valid = true;
            g_old[sig].has_siginfo = (old.sa_flags & SA_SIGINFO) != 0;
            g_old[sig].act = old;
        } else {
            g_old[sig].valid = false;
        }
        sigaction(sig, &sa, NULL);
    }
    g_installed = true;

    // 用 native_log 落一条安装痕迹（此时 native_log 已初始化）
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "crash_catcher installed -> %s", g_crash_path);
}
