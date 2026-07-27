#include <stdint.h>
#include <stdbool.h>
#include <jni.h>
#include <android/log.h>

#include "pedal_hook.h"
#include "drs_hook.h"

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    bool native_initialized;
    bool controls_enabled;
    bool drs_enabled;
    bool disable_auto_gear;
} module_state_t;

static module_state_t g_state = {0};

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_init(JNIEnv *env, jclass clazz,
                                           jlong set_throttle, jlong set_brake,
                                           jlong set_clutch, jlong shift_up, jlong shift_down,
                                           jlong set_gear, jlong fixed_update,
                                           jlong throttle_field, jlong brake_field,
                                           jlong clutch_field, jlong gear_field, jlong drs_toggle,
                                           jboolean enable_controls, jboolean enable_drs,
                                           jboolean disable_auto_gear) {
    (void) env;
    (void) clazz;
    (void) clutch_field;
    (void) gear_field;

    pedal_hook_config_t pedal_cfg = {
        .enable_control_replacement = (bool) enable_controls,
        .set_throttle_offset = (uintptr_t) set_throttle,
        .set_brake_offset = (uintptr_t) set_brake,
        .shift_up_offset = (uintptr_t) shift_up,
        .shift_down_offset = (uintptr_t) shift_down,
        .set_gear_offset = (uintptr_t) set_gear,
        .fixed_update_offset = (uintptr_t) fixed_update,
        .throttle_field_offset = (uintptr_t) throttle_field,
        .brake_field_offset = (uintptr_t) brake_field,
        .drivetrain_offset = 0x98,
        .drivetrain_automatic_field_offset = 0xBC
    };

    drs_hook_config_t drs_cfg = {
        .enable_auto_drs = (bool) enable_drs,
        .drs_toggle_offset = (uintptr_t) drs_toggle,
        .throttle_field_offset = (uintptr_t) throttle_field,
        .brake_field_offset = (uintptr_t) brake_field,
    };

    if (!pedal_install_hooks(&pedal_cfg)) {
        LOGE("Failed to install pedal hooks");
    }

    if (!drs_install_hooks(&drs_cfg)) {
        LOGE("Failed to install DRS hooks");
    }

    g_state.controls_enabled = (bool) enable_controls;
    g_state.drs_enabled = (bool) enable_drs;
    g_state.disable_auto_gear = (bool) disable_auto_gear;
    g_state.native_initialized = true;

    pedal_set_disable_auto_gear((int) g_state.disable_auto_gear);

    LOGI("Native bridge initialized: controls=%d drs=%d disable_auto_gear=%d",
         enable_controls, enable_drs, disable_auto_gear);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setThrottle(JNIEnv *env, jclass clazz, jfloat value) {
    (void) env;
    (void) clazz;
    pedal_set_throttle_value((float) value);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setBrake(JNIEnv *env, jclass clazz, jfloat value) {
    (void) env;
    (void) clazz;
    pedal_set_brake_value((float) value);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setClutch(JNIEnv *env, jclass clazz, jfloat value) {
    LOGI("setClutch(%f)", value);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setGear(JNIEnv *env, jclass clazz, jint gear) {
    LOGI("setGear(%d)", gear);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_shiftUp(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pedal_shift_up();
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_shiftDown(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pedal_shift_down();
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setDRSActive(JNIEnv *env, jclass clazz, jboolean active) {
    (void) env;
    (void) clazz;
    drs_set_active((int) active);
}
