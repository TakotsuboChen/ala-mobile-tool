#include "il2cpp_hooks.h"
#include "pedal_hook.h"
#include "drs_hook.h"
#include <android/log.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool install_hooks(void) {
    LOGI("install_hooks called (stub implementation)");
    return true;
}

void uninstall_hooks(void) {
    LOGI("uninstall_hooks called (stub implementation)");
    pedal_uninstall_hooks();
    drs_uninstall_hooks();
}
