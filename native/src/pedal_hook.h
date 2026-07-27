#ifndef PEDAL_HOOK_H
#define PEDAL_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool enable_control_replacement;

    // Method offsets in libil2cpp.so to hook.
    uintptr_t set_throttle_offset;
    uintptr_t set_brake_offset;

    // For reading/writing current input values directly if needed.
    uintptr_t throttle_field_offset;
    uintptr_t brake_field_offset;

    // Gear shifting method offsets.
    uintptr_t shift_up_offset;
    uintptr_t shift_down_offset;
    uintptr_t set_gear_offset;

    // FixedUpdate is called every physics tick; hooking it lets us force the
    // desired input values into the instance fields even when the original
    // setters are inlined.
    uintptr_t fixed_update_offset;
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

#ifdef __cplusplus
}
#endif

#endif // PEDAL_HOOK_H
