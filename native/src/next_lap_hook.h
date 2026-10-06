#ifndef NEXT_LAP_HOOK_H
#define NEXT_LAP_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 禁止删除下一圈成绩（enableProtectNextLap）。
 *
 * ── 要解决的问题 ─
 *
 * 游戏在赛道后段（trackPercentage > 0.8）冲出赛道限制时，会把**本圈和下一圈**
 * 的成绩一起作废：`odometerHandler.InvalidateLap` 同时置 alreadyInvalidated[0]
 * 与 [1]，并弹 "This and next lap times deleted"。刷圈时这意味着一次失误要
 * 牺牲两圈，效率减半。本功能让这种情况只作废本圈。
 *
 * ── Hook 点与策略 ─
 *
 * hook `odometerHandler.InvalidateLap(float trackPercentage, bool isInPit)`
 * （8.0.6 RVA 0x1A0DA2C），开关开启时把 trackPercentage 参数**改写为 0**。
 *
 * 反汇编实证（8.0.6）：trackPercentage 在方法内只被两处 `fcmp s8, #0.8`
 * 使用（0x1A0DAE4 / 0x1A0DBB0，常量 .rodata 0x929B2C = 0.8f），是"是否连带
 * 删除下一圈"的唯一判据。改写为 0 后：
 *   • 本圈照常作废（validLap=0、alreadyInvalidated[0]=1 原样发生）；
 *   • alreadyInvalidated[1] 不再被设置（两处设置点 0x1A0DB08 / 0x1A0DBCC
 *     都在 > 0.8 分支内）；
 *   • 提示文案自动变成 "Lap time deleted"（走 <= 0.8 分支）——**文案与实际
 *     行为一致**，模块不需要自己改任何提示。
 *
 * 为什么**不**用"orig 之后清 alreadyInvalidated[1]"：那样提示文案仍是
 * "This and next lap times deleted"，与模块实际行为矛盾（用户会以为功能没
 * 生效）。参数改写让游戏自己的全部逻辑（标志、文案、状态机）自然走正确分支。
 *
 * ── 调用图证据（升版必核）──
 *
 * InvalidateLap 全 .so 只有一个 `bl` 调用者
 *（carModifier.TractionControlDynamicAssist @0x1769A60），而后者只被
 * carModifier.Update 调用，且 Update 里调用前有 `ldrb w8, [x19, #0x9C]`
 *（playercar）守卫 ⇒ **只作用于玩家车**，无需白名单。
 * ⚠️ 升版时必须重新核对：InvalidateLap 的调用者集合是否仍是这一个，
 *    carModifier.Update 的 playercar 守卫是否仍在，0.8f 阈值与两处 fcmp
 *    是否仍在同一方法内。
 *
 * ── ABI 注记 ─
 *
 * AArch64 IL2CPP 实例方法 ABI：x0=this, s0=trackPercentage, w1=isInPit,
 * x2=method_info。AArch64 的 GPR 与 FPR 是**独立寄存器堆**（不像 AArch32
 * 的 r0/s0 重叠），故 this 与 float 首参不冲突。本 hook 是**函数级** hook
 *（shadowhook_hook_sym_addr），proxy 是普通 C 函数调用，可自由使用浮点
 * ——与 CLAUDE.md 里"指令级拦截回调必须 float-free"是两回事。
 */

typedef struct {
    /** 禁止删除下一圈成绩总开关（配置项 enableProtectNextLap，默认开）。 */
    bool enable_protect_next_lap;

    /** odometerHandler::InvalidateLap(float, bool) 的 RVA。 */
    uintptr_t invalidate_lap_offset;
} next_lap_hook_config_t;

bool next_lap_install_hooks(const next_lap_hook_config_t *config);
void next_lap_uninstall_hooks(void);

/**
 * 运行时开关（不重装 hook）。语义 = 配置项 enableProtectNextLap 的运行时同步，
 * 与 overtake_set_active / drs_set_active 同类的低频 setter。
 */
void next_lap_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // NEXT_LAP_HOOK_H
