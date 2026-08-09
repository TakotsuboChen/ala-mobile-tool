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

#ifdef __cplusplus
}
#endif

#endif // PEDAL_HOOK_H
