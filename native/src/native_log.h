#ifndef NATIVE_LOG_H
#define NATIVE_LOG_H

#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 初始化 native 日志系统。
 * @param enabled 初始开关状态（1=写文件，0=只打 logcat）
 */
void native_log_init(int enabled);

/**
 * 运行时切换日志开关。
 * @param enabled 1=写文件+logcat，0=只 logcat
 */
void native_log_set_enabled(int enabled);

/**
 * 查询当前日志开关状态。
 * @return 1=enabled, 0=disabled
 */
int native_log_is_enabled(void);

/**
 * 核心日志函数：同时打 logcat +（如果 enabled）写文件。
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