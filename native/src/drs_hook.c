#include "drs_hook.h"
#include "il2cpp_hooks.h"
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static drs_hook_config_t g_config = {0};
static pthread_t g_drs_thread;
static volatile int g_drs_running = 0;

/**
 * Placeholder for calling the original IRDSCarControllInput::drsToggle()
 * method. In a real build this would use ShadowHook to obtain the function
 * pointer at g_config.drs_toggle_offset and invoke it with the correct
 * calling convention.
 */
static void call_drs_toggle(void) {
    LOGI("call_drs_toggle stub (offset=0x%zx)", g_config.drs_toggle_offset);
}

static void *drs_poll_loop(void *arg) {
    (void) arg;

    while (g_drs_running) {
        // TODO(human): read current car telemetry from the active
        // IRDSCarControllInput instance and decide whether DRS should be on.
        // For now this loop just sleeps.
        usleep(100 * 1000);
    }

    return NULL;
}

bool drs_install_hooks(const drs_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_auto_drs) {
        LOGI("Auto DRS disabled");
        return true;
    }

    LOGI("Auto DRS enabled (poll strategy)");
    g_drs_running = 1;
    pthread_create(&g_drs_thread, NULL, drs_poll_loop, NULL);

    return true;
}

void drs_uninstall_hooks(void) {
    g_drs_running = 0;
    pthread_join(g_drs_thread, NULL);
    g_config.enable_auto_drs = false;
}
