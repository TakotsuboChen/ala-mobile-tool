#ifndef PEDAL_HOOK_H
#define PEDAL_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool enable_control_replacement;

    // 原生 TC/ABS：强制玩家车 tclEnable/absEnable 打开，让游戏自带
    // 牵引力控制/防抱死在非手柄模式下也生效。只作用于玩家车
    //（IRDSPlayerControls 组件只挂玩家车，天然身份过滤），不破坏
    // 陀螺仪/触摸转向，也根治 M18 的 AI 误控。
    bool enable_tc;
    bool enable_abs;

    // TC 档位（v1.4——时机档 = ε + minSPD 配对覆写，TC_LEVEL_DESIGN）：
    // - tc_mix：强度插值系数 ∈ [0,1]。0=关闭（return accel），1=游戏默认
    //   （逐位透传）；中间值在 TractionFilter 返回点线性插值，数学上等价于
    //   把 carController 内联补偿系数 -0.85 缩放为 -0.85×mix。
    // - tc_eps / tc_minspd：介入时机覆写对。反汇编确认 TractionFilter 的
    //   门控顺序：①carSpeed<TCLminSPD(0x38) → 透传（在读 ε 之前！）；
    //   ②TCLSlip(0x34)==0 → TC 关闭；③tclEnable(0xc6)；④(1-ε)·W>1 介入。
    //   游戏运行时真实参数 ε=0.40、minSPD=11.0（TCdiag 实测，非 ctor 默认
    //   0.45/1.0）。**minSPD 门控在 ε 之前**：只调 ε 只能影响 40km/h 以上的
    //   高速段介入阈值，最明显的起步打滑区间（<40km/h）纹丝不动——这就是
    //   v1.1/v1.3 "调时机无效果"的根因。故时机档必须 (eps, minspd) 成对写：
    //   >0 时每帧把两字段覆写为绝对值；<=0 = 不写（"游戏默认"）。
    // **关键教训**：v1.2 的"每帧无条件写回原厂值"会覆盖游戏 race-start 的
    // SetPlayerSettings 参数，导致 TC 全程早介入、打滑区间消失。因此：仅在
    // 用户所选配置真正偏离原厂时才写字段；切回游戏默认用一次性基线回写清
    // 残留（基线 = 首次覆写前捕获的游戏真实值，非 ctor 默认）。
    // install 时兜底 1.0/0.0/0.0（g_config={0} 会把 float 置 0，0.0 语义是
    // "关闭"不是"游戏默认"，必须显式兜底）。
    float tc_mix;
    float tc_eps;
    float tc_minspd;

    // ABS 档位（v1——干预强度 b 覆写 + 制动压力 T_b 等比缩放，
    // ABS_LEVEL_DESIGN v2）：
    // - abs_mix：b 覆写总闸。≤0 时忽略 b 覆写（"关闭 ABS"语义走
    //   enable_abs 布尔 → 既有 usesABS=false 四层关闭通道）。
    // - abs_b_override：pulse 释放深度 b(0x3E0) 绝对覆写值（轮级，per-wheel
    //   同值）。游戏 SetBrakeBiasValues：bias=60（中点）→ 前后轮 b 全 0 →
    //   pulse 帧 T×0 完全泄压，方波 [F_base·Ω, 0] 平均 0.5——"全段几乎不
    //   锁死"过度保护的根源。抬 b 抬方波平均 (1+b)/2；b≥0.3 后 β 饱和、
    //   Ω 摩擦圆耦合关死（零副作用杠杆）。<0 = 不覆写（恢复捕获基线）。
    // - abs_tb_scale：制动压力 T_b(0x88) 等比缩放 ∈ [0,1.0]。1.0 = 不
    //   缩放；<1 时**独立于 abs_mix/enable_abs 生效**（关 ABS/默认档下也
    //   修"关 ABS 秒锁死"——制动基数远超抓地极限的病态）。等比缩放非截断：
    //   踏板响应曲线（输入端 0-1）完全正交，全链（F_base 天花板/方波/关
    //   ABS 基数）一致缩放。覆写 = 基线×tbScale 绝对值写（勿现值×系数防
    //   复利衰减，TC v1.2 教训）；切回 1.0 一次性基线恢复。
    // install 时兜底 1.0/-1.0/1.0（g_config={0} 会把 float 置 0，语义必须是
    // "不覆写/不缩放"而非 0 值，必须显式兜底）。
    float abs_mix;
    float abs_b_override;
    float abs_tb_scale;

    // Method offsets in libil2cpp.so to hook.
    uintptr_t set_throttle_offset;
    uintptr_t set_brake_offset;

    // For reading/writing current input values directly if needed.
    uintptr_t throttle_field_offset;
    uintptr_t brake_field_offset;

    // Additional fields that the original game may use as the authoritative
    // input after FixedUpdate has processed the public _inputTorque/_brake.
    uintptr_t actual_throttle_field_offset;
    uintptr_t actual_brake_field_offset;

    // Gear shifting method offsets.
    uintptr_t shift_up_offset;
    uintptr_t shift_down_offset;
    uintptr_t set_gear_offset;

    // FixedUpdate is called every physics tick; hooking it lets us force the
    // desired input values into the instance fields even when the original
    // setters are inlined.
    uintptr_t fixed_update_offset;

    // Drivetrain field offsets.
    uintptr_t drivetrain_offset;
    uintptr_t drivetrain_automatic_field_offset;

    // IRDSDrivetrain::FixedUpdate is hooked to keep automatic=false reliably.
    uintptr_t drivetrain_fixed_update_offset;

    // IRDSDrivetrain::DoGearShifting — 自动换挡唯一入口。hook 它在 orig 前设
    // overrideClutchManagement(0x15C)=1 + automatic(0xBC)=1，让 DoGearShifting
    // 开头 direct return，真正禁用自动换挡（不被 FixedUpdate 每帧覆盖）。
    uintptr_t drivetrain_do_gear_shifting_offset;

    // TractionFilter / HandleABS 方法偏移——直接 hook 这两个方法，
    // 在入口处根据模块开关决定是否跳过（返回原始 accel / 不干预）。
    // 比写字段更可靠：不受游戏每帧覆盖 tclEnable/absEnable 影响。
    uintptr_t traction_filter_offset;
    uintptr_t handle_abs_offset;

    // IRDSPlayerControls::Update is hooked to continuously refresh
    // g_last_controller from the player's IRDSPlayerControls.carInputs
    // (offset 0x60). This survives scene reloads / restarts because Update
    // is called every frame on the current player instance.
    uintptr_t player_controls_update_offset;
} pedal_hook_config_t;

bool pedal_install_hooks(const pedal_hook_config_t *config);
void pedal_uninstall_hooks(void);

void pedal_set_throttle_value(float value);
void pedal_set_brake_value(float value);

// Invoke the hooked shiftUp/shiftDown methods on the last known controller.
// These are called from the Java overlay gear buttons.
void pedal_shift_up(void);
void pedal_shift_down(void);

// Returns the last IRDSCarControllInput instance seen by the hooks.
void *pedal_get_controller(void);

// Enable/disable automatic gear shifting suppression.
void pedal_set_disable_auto_gear(int disable);

// 更新 TC/ABS 开关——配置变更时 Java 层调此方法同步到 native g_config。
// 不需要重新装 hook，只改 g_config.enable_tc/enable_abs。
void pedal_set_tc_abs(int enable_tc, int enable_abs);

// TC 档位（强度 mix + 时机 ε）——配置变更时 Java 层调此方法同步。
// 与 set_tc_abs 同为低频 setter，不需重装 hook。
void pedal_set_tc_params(float mix, float eps, float minspd);

// ABS 档位（干预强度 b 覆写 + 制动压力 T_b 缩放）——配置变更时 Java 层调
// 此方法同步。与 set_tc_params 同为低频 setter，不需重装 hook。
void pedal_set_abs_params(float mix, float b_override, float tb_scale);

#ifdef __cplusplus
}
#endif

#endif // PEDAL_HOOK_H
