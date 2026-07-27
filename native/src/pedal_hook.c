#include "pedal_hook.h"
#include "il2cpp_hooks.h"
#include <android/log.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static pedal_hook_config_t g_config = {0};

bool pedal_install_hooks(const pedal_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_control_replacement) {
        LOGI("Pedal control replacement disabled");
        return true;
    }

    // TODO: install ShadowHook inline hooks for set_throttle / set_brake
    // and hook the shift_up / shift_down / set_gear methods.
    LOGI("Pedal hooks would be installed here (stub)");

    return true;
}

void pedal_uninstall_hooks(void) {
    g_config.enable_control_replacement = false;
}
