// ═══════════════════════════════════════════════════════════════════════════
// fps_unlock_hook.h — 强制全局 120 FPS（enableForce120Fps）
//
// 游戏移动端设置页「画面 → 帧率」下拉只有 30 / 60 两项，玩家永远够不到
// 120。但引擎本身支持三档：odometerHandler.setGameAndGraphicsVariables
// (RVA 0x1A02C44) 按 settingsValues._graphicsFramerate(+0x20) 分三支，
// 硬编码 30 / 60 / 120。120 那一支只存在于 PC 版画布
// (SettingCanvasStandalone)，移动端 UI 无从触发。
//
// 本模块 hook Application.set_targetFrameRate(int)（RVA 0x3257B6C），
// 开关开启时把入参 30 / 60 改写为 120，其它值（300 等）原样透传。
//
// ── 为什么改「入参」而不是改「字段」 ─
//
// 直接写 GlobalVariables.sV->_graphicsFramerate = 2 有两个致命缺陷：
//   ① 污染存档：odometerHandler.OnDestroy（退出赛道）会调
//      savePlayerSettings，而 BuildPlayerSettings_V2 把 sV **全部字段**
//      逐条写回 saveFile（含 +0x20）⇒ 用户退一次赛道，存档里的帧率就
//      永久变成 2。
//   ② UI 错乱：loadSettingsToMenu 会执行 TMP_Dropdown.set_value(2)，
//      而移动端 m_Options.Count == 2 ⇒ 越界 value 行为无保证（clamp 到 1
//      会导致「设置页显示 60，实际跑 120」）。
// 改入参是纯函数式的：不碰任何游戏状态，开关关掉立刻恢复用户原值。
//
// ── Hook 点的性质（升版必核）──
//
// 这是 **ICall thunk**，不是 IL2CPP 托管方法：函数体尾部 `br x1` 尾跳
// il2cpp_resolve_icall 解析出的 Unity 原生实现。
//
//   ABI: w0 = value, x1 = method_info（静态方法，**无 this**）
//   ⚠️ 全部 5 个调用点都 `mov x1, xzr` 传 NULL，且函数体内 0x325b78
//      立刻把 x1 覆盖成 ICall 缓存指针 ⇒ proxy 里**不要读 x1**，
//      原样透传即可。
//
// 全 .so 五个调用点（8.0.6 实测）：
//   • 0x1a02ce4 / 0x1a02d34 / 0x1a02d84 — odometerHandler
//     .setGameAndGraphicsVariables 内，分别传 30 / 60 / 120（三处都活）
//   • 0x18d8ad0 — EffectsGui.Start 传 300（Unity 官方示例工程遗留，
//     序列化字段在 225677 个 GameObject 里零命中 ⇒ **死代码不执行**）
//   • 0x18d8fb8 — MainGui.Start 传 300（同上，死代码）
// 本 hook 只匹配 30 / 60，300 与 -1 等其它值一律原样透传。
//
// 升版核对清单：① find_callers.py 0x3257b6c 仍为 5 个且全为 `bl`
//（若有内联化，该路径会绕过 hook）；② .rodata 0x9540b4 的符号名仍是
// UnityEngine.Application::set_targetFrameRate；③ 函数 RVA 是否变化。
//
// ── 已知边界（必须让用户知道）──
//
// 本 hook 只解决**软件渲染上限**。屏幕实际刷新率是另一层约束：Unity
// 文档明确「Rendering is always limited by the maximum refresh rate of
// the display」，且本游戏窗口的 LayoutParams.preferredRefreshRate 为 0
// （= 无偏好，Unity 自己构造时不设该字段）。⇒ 屏幕停在 60Hz 时，
// targetFrameRate=120 只会让渲染线程被 buffer queue 阻塞，用户体感
// **无明显改善**。要真正跑满 120 还需在 Java 层补窗口刷新率声明。
// ═══════════════════════════════════════════════════════════════════════════
#ifndef FPS_UNLOCK_HOOK_H
#define FPS_UNLOCK_HOOK_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    /** 强制全局 120 FPS 总开关（配置项 enableForce120Fps，默认关）。 */
    bool enable_force_120_fps;
    /** UnityEngine.Application::set_targetFrameRate(int) 的 RVA。 */
    uintptr_t set_target_frame_rate_offset;
} fps_unlock_hook_config_t;

/** 安装 hook。hook 恒装上（开关在回调内判），返回是否成功。 */
bool fps_unlock_install_hooks(const fps_unlock_hook_config_t *config);

/** 卸载 hook。 */
void fps_unlock_uninstall_hooks(void);

/**
 * 运行时开关（不重装 hook）。语义 = 配置项 enableForce120Fps 的运行时同步，
 * 配置广播到达后调用，游戏运行中拨开关立即生效。
 */
void fps_unlock_set_active(int active);

#endif // FPS_UNLOCK_HOOK_H
