#ifndef NATIVE_LOG_H
#define NATIVE_LOG_H

#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 核心日志函数：同时打 logcat + 写文件。
 * 路径从 /proc/self/cmdline 推导包名，写到
 * /sdcard/Android/media/<pkg>/ala_tool_native.log
 *
 * ⚠️ 2026-09-14 从 Android/data/<pkg>/files/ 迁移到 Android/media/<pkg>/：
 * media 不在 scoped storage 受限区，模块 App 持 AFA 后可跨包直读导出
 *（游戏闪退后仍能带出日志，不再依赖广播推送）。
 *
 * @param prio ANDROID_LOG_INFO / ANDROID_LOG_WARN / ...
 * @param tag  日志 tag
 * @param fmt  printf 格式串
 * @param ...  printf 参数
 */
void native_log_print(int prio, const char *tag, const char *fmt, ...);

/**
 * **最近日志环形快照**（供崩溃 handler 使用，2026-10-02 新增）。
 *
 * 为什么需要：native 崩溃现场只有寄存器 + PC 偏移，看不出"崩之前模块在干什么"。
 * 日志文件虽然一直写，但崩溃 handler 内不能 `localtime`/`malloc`，直接读文件又
 * 会与写者争锁（`pthread_mutex_lock` **不是** async-signal-safe，崩溃时若锁
 * 被同一线程持有就是死锁）。所以在 `native_log_print` 里额外维护一个**静态
 * 环形缓冲**，handler 只读它，不碰文件、不碰锁。
 *
 * ⚠️ **best-effort 语义**：读者不加锁，可能读到半行（写者正在写当前槽）。
 * 崩溃报告里出现一条截断的尾行是正常现象，不是 bug——比"什么都没有"好得多。
 *
 * @param out       输出缓冲（调用方提供，handler 内用静态数组）
 * @param cap       缓冲容量
 * @param max_lines 最多输出几行（限制 handler 耗时）
 * @return 实际写入的字节数（不含结尾 NUL）
 */
int native_log_ring_snapshot(char *out, int cap, int max_lines);

/**
 * **崩溃前把异步日志队列尽力刷出去**（2026-10-04 异步化配套）。
 *
 * 日志改为后台线程异步落盘后，主线程只入队。进程若在后台线程排空前崩溃，
 * 队列里尚未写盘的行会随进程一起丢。崩溃 handler 内调用本函数可把它们
 * 补写到文件，尽量保住现场。
 *
 * ⚠️ 只做 open/write/close，**不加锁、不分配**，满足 async-signal-safe 约束；
 * 队列可能正被后台线程消费，读到半行或重复一行都可能——best-effort，
 * 比"什么都没有"好得多。
 */
void native_log_flush(void);

/* 提供给各模块的便捷宏 */
#define NLOGI(...) native_log_print(ANDROID_LOG_INFO, "AlaMobileTool", __VA_ARGS__)
#define NLOGW(...) native_log_print(ANDROID_LOG_WARN, "AlaMobileTool", __VA_ARGS__)
#define NLOGE(...) native_log_print(ANDROID_LOG_ERROR, "AlaMobileTool", __VA_ARGS__)
#define NLOGD(...) native_log_print(ANDROID_LOG_DEBUG, "AlaMobileTool", __VA_ARGS__)

#ifdef __cplusplus
}
#endif

#endif // NATIVE_LOG_H