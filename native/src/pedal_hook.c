#include "pedal_hook.h"
#include <android/log.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ShadowHook public API (delivered via prefab)
#include "shadowhook.h"

static pedal_hook_config_t g_config = {0};

// Global values written by the Java overlay / NativeBridge.  These are read
// by the proxy functions and replace the values the game originally passed in.
static volatile float g_throttle_value = 0.0f;
static volatile float g_brake_value = 0.0f;

// ShadowHook handles used for unhooking.
static void *g_throttle_stub = NULL;
static void *g_brake_stub = NULL;

// Pointers to the original implementations, populated by ShadowHook.
static void *g_throttle_orig = NULL;
static void *g_brake_orig = NULL;

// True when the hooks are currently installed.
static volatile int g_hooks_installed = 0;

/**
 * Locate the base address of a loaded shared library by scanning /proc/self/maps.
 * Returns 0 if the module cannot be found.
 */
static uintptr_t get_module_base(const char *module_name) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        return 0;
    }

    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), fp) != NULL) {
        if (strstr(line, module_name) == NULL) {
            continue;
        }

        // Format: <start>-<end> <perms> <offset> <dev> <inode> <pathname>
        uintptr_t start = 0;
        if (sscanf(line, "%" SCNxPTR "-", &start) == 1) {
            base = start;
            break;
        }
    }

    fclose(fp);
    return base;
}

// -----------------------------------------------------------------------------
// Proxy functions.  These must have the same calling convention / signature as
// the original IL2CPP methods they replace.
// -----------------------------------------------------------------------------

// IRDSCarControllInput::setThrottle(float value)  (instance method)
static void proxy_set_throttle(void *this, float value) {
    (void) value;
    float new_value = g_throttle_value;
    if (new_value < 0.0f) new_value = 0.0f;
    if (new_value > 1.0f) new_value = 1.0f;

    typedef void (*orig_t)(void *, float);
    if (g_throttle_orig != NULL) {
        ((orig_t) g_throttle_orig)(this, new_value);
    }
}

// IRDSCarControllInput::setBrake(float value)  (instance method)
static void proxy_set_brake(void *this, float value) {
    (void) value;
    float new_value = g_brake_value;
    if (new_value < 0.0f) new_value = 0.0f;
    if (new_value > 1.0f) new_value = 1.0f;

    typedef void (*orig_t)(void *, float);
    if (g_brake_orig != NULL) {
        ((orig_t) g_brake_orig)(this, new_value);
    }
}

// -----------------------------------------------------------------------------
// Hook installation / removal
// -----------------------------------------------------------------------------

bool pedal_install_hooks(const pedal_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_control_replacement) {
        LOGI("Pedal control replacement disabled");
        return true;
    }

    if (g_hooks_installed) {
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address");
        return false;
    }
    LOGI("libil2cpp.so base address: 0x%" PRIxPTR, base);

    // Hook setThrottle
    if (g_config.set_throttle_offset != 0) {
        uintptr_t target = base + g_config.set_throttle_offset;
        g_throttle_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_throttle,
                (void **) &g_throttle_orig);
        if (g_throttle_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setThrottle) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setThrottle at 0x%" PRIxPTR, target);
        }
    }

    // Hook setBrake
    if (g_config.set_brake_offset != 0) {
        uintptr_t target = base + g_config.set_brake_offset;
        g_brake_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_brake,
                (void **) &g_brake_orig);
        if (g_brake_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setBrake) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setBrake at 0x%" PRIxPTR, target);
        }
    }

    // Gear shifting hooks are reserved for a future slice.  The offsets are
    // already passed from Java but are intentionally left unused here so the
    // first real hook iteration remains focused on throttle / brake.
    if (g_config.shift_up_offset != 0 || g_config.shift_down_offset != 0 ||
        g_config.set_gear_offset != 0) {
        LOGI("Gear-shift hook offsets recorded but not yet installed");
    }

    g_hooks_installed = 1;
    return true;
}

void pedal_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }

    if (g_throttle_stub != NULL) {
        shadowhook_unhook(g_throttle_stub);
        g_throttle_stub = NULL;
        g_throttle_orig = NULL;
    }
    if (g_brake_stub != NULL) {
        shadowhook_unhook(g_brake_stub);
        g_brake_stub = NULL;
        g_brake_orig = NULL;
    }

    g_hooks_installed = 0;
    g_config.enable_control_replacement = false;
}

// Public API used by ala_core.c / NativeBridge to update desired input values.
void pedal_set_throttle_value(float value) {
    g_throttle_value = value;
}

void pedal_set_brake_value(float value) {
    g_brake_value = value;
}
