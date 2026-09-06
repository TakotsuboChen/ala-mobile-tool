#ifndef CRASH_HOOK_H
#define CRASH_HOOK_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 游戏进程 native 崩溃自捕（信号级）。
 *
 * 安装 SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE 的 sigaction handler：
 * 崩溃现场（信号 + 寄存器 + PC 相对模块偏移）落盘
 * /sdcard/Android/data/<游戏包>/files/ala_tool_crash_native.log，
 * 然后链式转发旧 handler（Unity/系统可能装过，绝不能吞）。
 *
 * 背景（用户日志实证 2026-09-06）：游戏进程在 LLV.Awake 场景加载窗口
 * 竞态 SIGSEGV，无声死亡零堆栈——Java 版 CrashCatcher 只装模块进程，
 * native 信号崩溃也不经过它。本模块补上游戏进程 + native 层盲区。
 *
 * 幂等：重复安装是 no-op。
 */
void crash_catcher_install(void);

#ifdef __cplusplus
}
#endif

#endif // CRASH_HOOK_H
