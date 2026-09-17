#ifndef OVERTAKE_HOOK_H
#define OVERTAKE_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 自锁型超车按键（enableLatchOvertake）。
 *
 * ── 要解决的问题 ─
 *
 * 游戏原生的 OTK（超车）屏幕按钮是**自复位**语义：按住 = 超车模式开启，
 * 松手 = 关闭。长距离超车要一直按着，手指被占死，没法同时操作踏板。
 * 本功能把「按住」改成「点一下切换」：点一下进入锁定（松手不关），再点一下退出。
 * **是否允许开超车（ERS 是否解锁、电量是否够）完全仍由游戏判定**。
 *
 * ── Hook 点 ──
 *
 * 只 hook **OTK 按钮自己的**两个 UnityEvent 入口：
 *     odometerHandler.TouchPressOTK()   ← 按下
 *     odometerHandler.TouchReleaseOTK() ← 抬起
 * 二者是按钮专属方法（全 .so 只被该按钮的 prefab 事件调用），因此：
 *   • 天然不碰 AI 车（AI 走 AIHybridManager.ManageOvertake → EnableDisableOvertakeModeSwitch）；
 *   • 天然不碰**别的按钮**。
 *
 * ⚠️ **不要**改挂到 HybridComponent.EnableOTK/DisableOTK 上。它们虽然也覆盖
 * 触摸路径（TouchPressOTK 尾部就是 `b EnableOTK`），但**不是按钮专属**：
 *   HybridComponent.switchHModeUp(0x1A27484) 是游戏自己的 OTK toggle
 *   （`if (OvertakeActive) DisableOTK() else EnableOTK()`），被
 *   **odometerHandler.onHybridMapChange** 与 **odometerHandler.Update**
 *   （即 ERS 混动模式切换键）调用。挂在那里会把 ERS 模式键的"关超车"
 *   一并吞掉 —— 那是弄坏游戏另一条正常路径。
 *
 * ── 策略（不写任何游戏状态字段）──
 *
 *   按下时：若超车已开着 → 本次点按语义是"关"，直接调游戏的 DisableOTK()；
 *           否则原样转发 TouchPressOTK（开启路径逐字节是游戏自己的逻辑）
 *   抬起时：若锁定态由本模块建立且超车仍开着、电量仍非零 → 吞掉，保持开启；
 *           否则原样转发（比如电量耗尽时游戏已自行收尾，松手如实传达）
 * 开启与关闭都走游戏自己的入口，故手臂动画（rotateHands.playHybridOtkAction）、
 * HUD 更新、UpdateHybridRNCs 联动全部照常发生；ERS 未解锁 / 电量不足时点了
 * 没反应也与原生完全一致（模块不补任何提示）。
 *
 * 明确否决的做法：
 *   ✗ 直接写 HybridComponent.OvertakeActive(0xC4) —— 绕过游戏入口会丢手臂动画、
 *     HUD 更新与 UpdateHybridRNCs 联动，状态机不一致。
 *   ✗ 改挂 EnableOTK/DisableOTK —— 见上，会波及 ERS 模式切换键。
 *   ✗ 改手柄路径（IRDSPlayerControls.NitroMobile）—— 用户诉求是屏幕按钮；
 *     手柄 nitroButton 语义未实证，不擅自改。
 */

typedef struct {
    /** 自锁型超车按键总开关（配置项 enableLatchOvertake）。 */
    bool enable_latch_overtake;

    /** odometerHandler::TouchPressOTK() —— OTK 按钮按下。 */
    uintptr_t touch_press_otk_offset;

    /** odometerHandler::TouchReleaseOTK() —— OTK 按钮抬起。 */
    uintptr_t touch_release_otk_offset;

    /** HybridComponent::DisableOTK() —— 关闭超车时转调的游戏入口。 */
    uintptr_t disable_otk_offset;
} overtake_hook_config_t;

bool overtake_install_hooks(const overtake_hook_config_t *config);
void overtake_uninstall_hooks(void);

/**
 * 运行时开关（不重装 hook）。语义 = 配置项 enableLatchOvertake 的运行时同步，
 * 与 drs_set_active / pedal_set_tc_abs 同类的低频 setter。
 */
void overtake_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // OVERTAKE_HOOK_H