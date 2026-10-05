#ifndef KERB_HAPTIC_H
#define KERB_HAPTIC_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 路肩振感反馈信号源——**直接用游戏自己的"路肩"定义**。
 *
 * ## 信号源 = `IRDSWheel.materialIndex (0x2E8) == -5`
 *
 * 不发明任何判据。反汇编 `IRDSCarVisuals.TireModelVisuals`(0x1A68B4C) 实证，
 * 游戏自己判定"是否在路肩上"就是这一条：
 *
 * ```
 *   0x1a6a5a8: ldr  w8, [x8, #0x2e8]    ; wheel.materialIndex
 *   0x1a6a5ac: cmn  w8, #5              ; == -5 ?
 *   0x1a6a5b0: b.ne ...
 *   0x1a6a5bc: strb w29, [x8, #0xd7]    ; carController.kerbSound = true
 * ```
 *
 * 而 `IRDSSoundController.kerbSoundUpdate`(0x1A7881C) 正是读这个 `kerbSound`
 * 决定播不播路肩音。**所以 materialIndex==-5 ⟺ 游戏播路肩音**，
 * 「跟游戏路肩声音同步」在实现上就是读同一个字段。
 *
 * `UpdatePhysicsMaterialFactors`(0x1A7F3BC) 是 materialIndex 的**唯一写者**
 * （全 `.so` 只有一个 `bl` 指向它，来自 `ComputeWheelPhysics`），每物理帧刷新：
 *
 * | materialIndex | 含义 |
 * |---|---|
 * | -1 | 赛道铺装面（trackPhysicMaterial） |
 * | **-5** | **路肩 / 振动带（kerbPhyisicMaterial）** |
 * | -2 | 草地 |
 * | -3 | 砂石 |
 * | -4 | 野沥青 |
 * | -6 | 混凝土 |
 *
 * ⚠️ **不要**改去用 `get_SurfaceType()`(0x1A7B670)——它只是 materialIndex 的
 * 只读属性封装，且**全 `.so` 零 `bl` 指向**（死代码，游戏自己都不用）。
 *
 * ## 强度与频率
 *
 * - **强度**：车速越高振得越强（`KERB_LEVEL_MIN` → 1.0 在 `KERB_LEVEL_FULL_KMH`），
 *   压上路肩的轮子越多越强（单轮 `KERB_WHEEL_BASE`，四轮 = 1.0）。
 * - **频率**：颗粒率随车速上升（`KERB_HZ_MIN` → `KERB_HZ_MAX`）。频率不在
 *   native 侧合成波形，只把**目标颗粒率**（Hz）交给 Java——波形的段长/段数
 *   由 LRA 硬件能力决定，属 Java 侧 [HapticMixer] 的职责。
 *
 * ## 与抓地力反馈的关系
 *
 * 本通道**只出电平**，不碰振动器。两条电平由 Java 侧 [HapticMixer] 合成为
 * 一个波形（逐段取大）——两个无限循环波形共用一台马达时谁后发谁赢，必须
 * 合并成一条，否则"路肩上打滑"只会剩下一个（详见 HapticMixer 类注释）。
 */

/** 配置变更时下发（低频，同 slip_feedback_set_params 模式，不重装 hook）。 */
void kerb_haptic_set_enabled(int enabled);

/**
 * 查询当前路肩电平与目标颗粒率（Java 主线程轮询，无锁读 volatile）。
 *
 * @param out_level 输出：电平 0..1
 * @param out_rate_hz 输出：目标颗粒率（Hz），未压路肩时为 0
 */
void kerb_haptic_query(float *out_level, float *out_rate_hz);

/**
 * 每物理帧调用（玩家车白名单内，**必须在 carController 之后**，
 * 与 slip_feedback_tick 同点——materialIndex 由 ComputeWheelPhysics 每帧刷新）。
 */
void kerb_haptic_tick(void *car_inputs);

#ifdef __cplusplus
}
#endif

#endif // KERB_HAPTIC_H
