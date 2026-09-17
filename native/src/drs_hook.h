#ifndef DRS_HOOK_H
#define DRS_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 自动 DRS / 主动空力（AA）套件。
 *
 * ── 实现原理 ──
 *
 * 游戏把「现在可以开 DRS」这件事广播成状态机事件：carModifier._currentDRSState
 * 推进到 Deployable(3) 时，OnDRSStateChanged(3) 会播 DRS 提示音（反汇编实证：
 * carModifier.OnDRSStateChanged @0x17600C4 `cmp w8,#3` → 播 drsAlert）。
 * 所以 hook OnDRSStateChanged 就等于「捕捉允许开启的信号」，模块完全不需要
 * 自己分析赛道区域数据。
 *
 * 这一点很关键，因为两套规则的可用区域判定走的是**两条完全独立的管线**：
 *   • DRS（地效车）    —— 全局标志 IRDSStatistics.isDRSGloballyEnabled(0x101)
 *                        + 圈数门槛 lapForDRS + 物理触发区 carModifier.OnTriggerEnter
 *   • 主动空力（2026） —— 全局标志 isSafeForBoostAndActiveAero(0x102)
 *                        + waypoint 数组协程 IRDSNavigateTWaypoints.CheckActiveAeroAvailability
 *                        + carType==DoubleDRSEra 硬门（carModifier.FixedUpdate 里分派）
 * 两者的区域判定方式不同（物理触发器 vs waypoint 索引数组），但都会把状态机
 * 推到 Deployable(3) —— 于是同一个 hook 点天然覆盖两种车、两套规则。
 *
 * ── 策略 ──
 *
 * 只做「在合法窗口替玩家按下 DRS 键」，不做状态机接管：
 * 收到 Deployable(3) → 调 carModifier.ManualDRSUsage()（即玩家按键的真实入口）。
 * 3 → 4 的合法性校验/动画/音效/HUD/previousDRSState 维护全部由游戏自己做。
 *
 * 明确否决的做法（曾评估，避免重走）：
 *   ✗ 直接写 carModifier._currentDRSState —— 绕过 OnDRSStateChanged，
 *     会丢失部署动画、音效、HUD 通知与 previousDRSState 维护，状态机会不一致。
 *   ✗ hook carModifier.FixedUpdate —— 每帧高频、且是所有车共享的 passthrough
 *     函数（红线：禁止日志 + 会命中全部车）。
 *   ✗ 模块自建赛道区域表 —— 游戏已有 activeAerozone[]，且两套规则判定管线不同，
 *     重复实现既冗余又必然出错。
 *   ✗ hook IRDSCarControllInput.drsToggle —— 那是玩家按键入口。自动模式下没有
 *     人按键，这个 hook 永远触发不了（旧实现即栽在这里：装上了 hook 却只会
 *     把所有 DRS 按键请求吞掉）。
 */

typedef struct {
    /** 自动 DRS / AA 总开关（配置项 enableAutoDrs）。 */
    bool enable_auto_drs;

    /** carModifier::OnDRSStateChanged(DRSState) —— 「允许开启」信号源。 */
    uintptr_t on_drs_state_changed_offset;

    /** carModifier::ManualDRSUsage() —— 玩家手动开启 DRS 的真实入口。 */
    uintptr_t manual_drs_usage_offset;
} drs_hook_config_t;

bool drs_install_hooks(const drs_hook_config_t *config);
void drs_uninstall_hooks(void);

/**
 * 运行时开关自动部署（不重装 hook）。语义 = 配置项 enableAutoDrs 的运行时同步，
 * 与 pedal_set_tc_abs 同类的低频 setter。
 */
void drs_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // DRS_HOOK_H
