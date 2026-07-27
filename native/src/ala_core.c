#include <stdint.h>
#include <stdbool.h>
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct {
    uintptr_t throttle_setter;
    uintptr_t brake_setter;
    uintptr_t gear_setter;
    uintptr_t drs_setter;
    uintptr_t drs_getter;
    bool enable_controls;
    bool enable_drs;
} g_state = {0};

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_init(JNIEnv *env, jclass clazz,
                                            jlong throttle_setter, jlong brake_setter,
                                            jlong gear_setter, jlong drs_setter,
                                            jlong drs_getter, jboolean enable_controls,
                                            jboolean enable_drs) {
    g_state.throttle_setter = (uintptr_t) throttle_setter;
    g_state.brake_setter = (uintptr_t) brake_setter;
    g_state.gear_setter = (uintptr_t) gear_setter;
    g_state.drs_setter = (uintptr_t) drs_setter;
    g_state.drs_getter = (uintptr_t) drs_getter;
    g_state.enable_controls = enable_controls;
    g_state.enable_drs = enable_drs;

    LOGI("Native bridge initialized: controls=%d drs=%d", enable_controls, enable_drs);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setThrottle(JNIEnv *env, jclass clazz, jfloat value) {
    LOGI("setThrottle(%f)", value);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setBrake(JNIEnv *env, jclass clazz, jfloat value) {
    LOGI("setBrake(%f)", value);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setGear(JNIEnv *env, jclass clazz, jint gear) {
    LOGI("setGear(%d)", gear);
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_shiftUp(JNIEnv *env, jclass clazz) {
    LOGI("shiftUp");
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_shiftDown(JNIEnv *env, jclass clazz) {
    LOGI("shiftDown");
}

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setDRSActive(JNIEnv *env, jclass clazz, jboolean active) {
    LOGI("setDRSActive(%d)", active);
}
