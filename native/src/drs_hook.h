#ifndef DRS_HOOK_H
#define DRS_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Possible DRS strategies:
 *
 * 1. Hook the call sites of IRDSCarControllInput::drsToggle() inside the
 *    original game code. Whenever the game tries to toggle DRS, decide whether
 *    to allow it based on telemetry (speed, throttle, inDRSZone, etc.).
 *
 * 2. Poll telemetry from a background thread and invoke drsToggle() on the
 *    active IRDSCarControllInput instance when DRS should be active. This
 *    requires a reliable way to obtain the current instance pointer.
 *
 * The module currently uses strategy 1 as the default (preferred in CLAUDE.md:
 * "prefer hooking the game's own DRS input check").
 */

typedef struct {
    bool enable_auto_drs;

    uintptr_t drs_toggle_offset;
    uintptr_t car_controller_instance;

    // Telemetry field offsets inside IRDSCarControllInput / IRDSDrivetrain.
    uintptr_t throttle_field_offset;
    uintptr_t brake_field_offset;
    uintptr_t speed_field_offset;
} drs_hook_config_t;

bool drs_install_hooks(const drs_hook_config_t *config);
void drs_uninstall_hooks(void);
void drs_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // DRS_HOOK_H
