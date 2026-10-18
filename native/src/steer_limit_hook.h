#ifndef STEER_LIMIT_HOOK_H
#define STEER_LIMIT_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 解除最大转向角度限制（enableUnlockSteerAngle）。
 *
 * ── 要解决的问题 ─
 *
 * 游戏按车速把每只轮子的**最大转向角**往下压：车速越高，能打的角度越小。
 * 这是真车转向手感的一部分，但在手机触屏 + 原厂辅助叠加之后，玩家在
 * 中高速弯常常觉得"方向打不动"。本功能解除这个随车速的收紧，让每只轮子
 * 始终保有静态基础最大转向角。
 *
 * ── 游戏侧机制（8.0.6 反汇编实证）──
 *
 * `IRDSWheel` 上有两个成对的字段：
 *   • `maxSteeringAngle`(0x90)    —— **静态基础值**，只在 `Awake`
 *     (0x1A7D07C，`ldr s0,[x19,#0x90]; str s0,[x19,#0x94]`) 与
 *     `IRDSWishboneSuspension.Init`(0x1A7D4CC) 从 prefab 读取，游戏运行期
 *     **不再改写**。
 *   • `maxSteeringAngleOld`(0x94) —— **运行期生效值**。`Awake` 时被 0x90
 *     初始化，此后由 `SetMaxSteeringAngle(float)`(0x1A7B49C) 覆写
 *     （`str s8,[x19,#0x90]` / `str s8,[x19,#0x94]` 二选一，取决于
 *     `Application.isPlaying`——运行时写的是 **0x94**）。
 *
 * `IRDSWheel.RoadForce`(0x1A7DB3C) 在 0x1A7E020 处读 `0x94` 与 `0xf4`
 * （wheel.steering），`fcmp s1,#0.0; b.ne` 分流：
 *   • `0x94 != 0` → `fdiv s0, s0, s1`（`0x7c`/maxAngle 除以最大转角）后进
 *     轮胎侧偏计算 ⇒ **这是限角真正生效的地方**；
 *   • `0x94 == 0` → 走另一条退化分支。
 * 所以"限角"就是 0x94 被压低，解限就是让 0x94 回到 0x90。
 *
 * ── Hook 点 ─
 *
 * hook `IRDSWheel.SetMaxSteeringAngle(float)`(RVA 0x1A7B49C)，**开关开启时
 * 丢弃入参**、改为把 `this->maxSteeringAngle`(0x90) 原样写进
 * `maxSteeringAngleOld`(0x94)——等价于"永远使用静态基础最大转向角"。
 *
 * 为什么选这个点而不是 hook `RoadForce` 或每帧写字段：
 *   • `SetMaxSteeringAngle` 是**唯一**在运行期写 0x94 的入口（全 `.so` 只有
 *     三个 `bl` 调用者，见下），堵住它就等于堵住全部限角来源；
 *   • 每帧硬写字段会与游戏自己的 `Awake`/`Init` 初始化竞态，且要依赖
 *     物理帧节奏；hook 入口是事件驱动，天然只在游戏想改角度时介入。
 *
 * ⚠️ **不**去 hook `carModifier.TiltSteerDynamicAssist`(0x1769274)：那个方法
 * 确实也在按车速算角度（`21° − 12° × clamp((v−27)/56, 0, 1)`），但它被
 * `carModifier.isTiltSteer`(0x1C4) 门控。该标志在 `carModifier.Start` 里由
 * PlayerPrefs 的 `"isTilt"` 键决定，**判定是反的**：
 *
 *   0x1763C5C  bl   PlayerPrefs.HasKey("isTilt")
 *   0x1763C68  tbz  w0, #0, 0x1763C7C     ; 没有这个键 → 跳到置位
 *   0x1763C74  bl   PlayerPrefs.GetInt("isTilt")
 *   0x1763C78  cbnz w0, 0x1763C84         ; 键值 != 0 → 跳过置位（保持 false）
 *   0x1763C7C  mov  w8, #1
 *   0x1763C80  strb w8, [x19, #0x1C4]     ; isTiltSteer = true
 *
 * ⇒ `isTiltSteer == true` 的条件是「**没有** `isTilt` 键」**或**「键值为 0」，
 *   即它实际含义接近「**未启用**倾斜转向」。而 TiltSteerDynamicAssist 的守卫
 *   是 `0x1769294 ldrb w8,[x0,#0x1C4]; cbz w8, ret`（false 才早退）
 *   ⇒ 该方法跑在「**没开**倾斜转向」的玩家身上（触摸/方向键，即多数用户）。
 *
 * 即便如此仍不 hook 它：hook `SetMaxSteeringAngle` 覆盖全部调用路径（含
 * TiltSteer 那条，因为它最终也是通过 SetMaxSteeringAngle 落地），单点更稳。
 *
 * 📌 2026-10-18 修正：本文档早期版本写成「只对开启陀螺仪倾斜转向的玩家生效」，
 *   与反汇编不符（条件写反了）。以上述指令序列为准。
 *
 * ── 调用图证据（升版必核）──
 *
 * `SetMaxSteeringAngle` 全 `.so` 三个 `bl` 调用者（8.0.6 实测）：
 *   1. `IRDSMotorCycle.Update`(0x174C294)          —— 摩托车（本作无此玩法）
 *   2. `carModifier.Start`(0x17635FC +0x1030)      —— 开局把 0x90 重置为 21.0f
 *   3. `carModifier.TiltSteerDynamicAssist`(0x1769274 +0x18C) —— 倾斜转向限角
 * ⚠️ 升版时必核：① 这三个调用者是否仍是全部（`find_callers.py 0x1A7B49C`）；
 *   ② `SetMaxSteeringAngle` 内是否仍是 `isPlaying` 二选一写 0x90/0x94；
 *   ③ `IRDSWheel.maxSteeringAngle`(0x90) / `maxSteeringAngleOld`(0x94)
 *      两个字段偏移是否未变（`IRDSWheel` 布局在 8.0.0→8.0.6 未变）。
 *
 * ── 为什么不需要玩家车白名单 ─
 *
 * 与"禁止删除下一圈成绩"不同，本功能**对所有车生效是安全的**：它只放宽
 * 物理上限，不改变任何 AI 的决策输入。AI 的转向由 `IRDSCarControllerAI`
 * 给出的 `inputSteer` 决定，`maxSteeringAngleOld` 只是把该输入映射成轮胎
 * 侧偏角时的**上限**——AI 本来就不会要求超过基础转角的角度，所以解限对
 * AI 行为无影响，同时避免"玩家超车时 AI 突然被限角"这类不一致。
 * （这也让 hook 不必依赖 `g_player_controller`，安装时机不受白名单就绪
 *   影响——白名单只在 `proxy_player_controls_update` 里写入，见 pedal_hook.c。）
 *
 * ── ABI 注记 ─
 *
 * AArch64 IL2CPP 实例方法：x0=this, s0=Value, x1=method_info。
 * GPR 与 FPR 独立寄存器堆，this 与 float 首参不冲突。
 * 本 hook 是**函数级** hook（`shadowhook_hook_sym_addr`），proxy 是普通 C
 * 函数调用，**可自由使用浮点**——与"指令级拦截回调必须 float-free"无关。
 */

typedef struct {
    /** 解除最大转向角度限制总开关（配置项 enableUnlockSteerAngle，默认关）。 */
    bool enable_unlock_steer_angle;

    /** IRDSWheel::SetMaxSteeringAngle(float) 的 RVA。 */
    uintptr_t set_max_steering_angle_offset;

    /** IRDSWheel.maxSteeringAngle 字段偏移（静态基础值，默认 0x90）。 */
    uint32_t wheel_max_steering_angle_field;

    /** IRDSWheel.maxSteeringAngleOld 字段偏移（运行期生效值，默认 0x94）。 */
    uint32_t wheel_max_steering_angle_old_field;
} steer_limit_hook_config_t;

bool steer_limit_install_hooks(const steer_limit_hook_config_t *config);
void steer_limit_uninstall_hooks(void);

/**
 * 运行时开关（不重装 hook）。语义 = 配置项 enableUnlockSteerAngle 的运行时
 * 同步，与 next_lap_set_active / overtake_set_active 同类低频 setter。
 */
void steer_limit_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // STEER_LIMIT_HOOK_H
