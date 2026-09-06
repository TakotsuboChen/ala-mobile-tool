#ifndef NATIVE_LOG_H
#define NATIVE_LOG_H

#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 核心日志函数：同时打 logcat + 写文件。
 * 路径从 /proc/self/cmdline 推导包名，写到
 * /sdcard/Android/data/<pkg>/files/ala_tool_native.log
 *
 * @param prio ANDROID_LOG_INFO / ANDROID_LOG_WARN / ...
 * @param tag  日志 tag
 * @param fmt  printf 格式串
 * @param ...  printf 参数
 */
void native_log_print(int prio, const char *tag, const char *fmt, ...);

/* 提供给各模块的便捷宏 */
#define NLOGI(...) native_log_print(ANDROID_LOG_INFO, "AlaMobileTool", __VA_ARGS__)
#define NLOGW(...) native_log_print(ANDROID_LOG_WARN, "AlaMobileTool", __VA_ARGS__)
#define NLOGE(...) native_log_print(ANDROID_LOG_ERROR, "AlaMobileTool", __VA_ARGS__)
#define NLOGD(...) native_log_print(ANDROID_LOG_DEBUG, "AlaMobileTool", __VA_ARGS__)

#ifdef __cplusplus
}
#endif

#endif // NATIVE_LOG_H