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

// 日志开关（1=写文件+logcat，0=只 logcat）
static volatile int g_log_enabled = 0;

// 日志文件路径缓存
static char g_log_path[256] = {0};
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

// 文件滚动阈值：2MB
#define MAX_LOG_SIZE (2 * 1024 * 1024)

void native_log_init(int enabled) {
    g_log_enabled = enabled ? 1 : 0;
}

void native_log_set_enabled(int enabled) {
    g_log_enabled = enabled ? 1 : 0;
}

int native_log_is_enabled(void) {
    return g_log_enabled;
}

/**
 * 从 /proc/self/cmdline 推导包名，拼日志文件路径：
 * /sdcard/Android/data/<pkg>/files/ala_tool_native.log
 *
 * strip :suffix（子进程），与 unlock_hook.c 原 npatch_log 同理。
 * 游戏进程对自己的 externalFilesDir 天然可写，NPatch + LSPosed 都适用。
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
    snprintf(g_log_path, sizeof(g_log_path),
             "/sdcard/Android/data/%s/files/ala_tool_native.log", cmdline);
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

void native_log_print(int prio, const char *tag, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    // logcat 始终打
    __android_log_print(prio, tag, "%s", buf);

    // 文件只在 enabled 时写
    if (!g_log_enabled) return;

    resolve_log_path();
    if (g_log_path[0] == '\0') return;

    pthread_mutex_lock(&g_log_mutex);
    int f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (f >= 0) {
        // 滚动检查
        struct stat st;
        if (fstat(f, &st) == 0 && st.st_size > MAX_LOG_SIZE) {
            close(f);
            truncate_log_file(g_log_path);
            f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
            if (f < 0) {
                pthread_mutex_unlock(&g_log_mutex);
                return;
            }
        }

        char line[1200];
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        int ms = (int)(ts.tv_nsec / 1000000);
        const char *prio_str = (prio == ANDROID_LOG_INFO) ? "I" :
                               (prio == ANDROID_LOG_WARN) ? "W" :
                               (prio == ANDROID_LOG_ERROR) ? "E" :
                               (prio == ANDROID_LOG_DEBUG) ? "D" : "?";
        snprintf(line, sizeof(line),
                 "[%04d-%02d-%02dT%02d:%02d:%02d.%03d pid=%d tid=%d][%s/%s] %s\n",
                 tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                 tm.tm_hour, tm.tm_min, tm.tm_sec, ms,
                 (int)getpid(), (int)gettid(), prio_str, tag, buf);
        write(f, line, strlen(line));
        close(f);
    }
    pthread_mutex_unlock(&g_log_mutex);
}