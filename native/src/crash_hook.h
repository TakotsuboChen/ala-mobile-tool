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
 * /sdcard/Android/media/<游戏包>/ala_tool_crash_native.log，
 * 然后链式转发旧 handler（Unity/系统可能装过，绝不能吞）。
 *
 * 背景（用户日志实证 2026-09-06）：游戏进程在 LLV.Awake 场景加载窗口
 * 竞态 SIGSEGV，无声死亡零堆栈——Java 版 CrashCatcher 只装模块进程，
 * native 信号崩溃也不经过它。本模块补上游戏进程 + native 层盲区。
 *
 * 幂等：重复安装是 no-op。
 */
void crash_catcher_install(void);

/**
 * **登记一个已安装的钩子**（2026-10-02 新增，崩溃现场取证用）。
 *
 * 各 `*_install_hooks` 在 shadowhook_hook_sym_addr 成功后调用一次。崩溃
 * handler 会把整张表连同最近日志快照一起落盘，这样报告里直接能看到
 * "崩的时候哪些 hook 活着、各自挂在哪个绝对地址"——不用再靠日志反推。
 *
 * async-signal-safe：只写静态数组 + `snprintf`，无锁无分配。
 * 同名重复登记会覆盖（early install 与 15s 延迟路径都会装同一批钩子）。
 *
 * @param name 钩子短名（如 "carController" / "FixedUpdate"），≤39 字符
 * @param addr 目标函数的**绝对地址**（base + offset），0 表示未解析
 */
void crash_hook_register(const char *name, void *addr);

#ifdef __cplusplus
}
#endif

#endif // CRASH_HOOK_H
