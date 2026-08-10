#include <stdint.h>
#include <stdbool.h>
#include <jni.h>
#include <android/log.h>

#include "pedal_hook.h"
#include "drs_hook.h"
#include "unlock_hook.h"
#include "music_hook.h"

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
                                           jlong actual_throttle_field, jlong actual_brake_field,
                                           jlong clutch_field, jlong gear_field,
                                           jlong drivetrain_fixed_update, jlong drivetrain_automatic_field,
                                           jlong drivetrain_do_gear_shifting,
                                           jlong traction_filter,
                                           jlong handle_abs,
                                           jlong player_controls_update,
                                           jlong drs_toggle,
                                           jlong billing_manager_awake,
                                           jlong billing_manager_get_instance,
                                           jlong billing_manager_initialize_billing,
                                           jlong billing_manager_on_owned_none,
                                           jlong billing_manager_on_purchase_failed,
                                           jlong billing_manager_set_unlocked,
                                           jlong billing_manager_on_already_owned,
                                           jlong billing_manager_is_unlocked_field,
                                           jlong billing_manager_has_store_connection_field,
                                           jlong billing_manager_has_completed_ownership_check_field,
                                           jboolean enable_controls, jboolean enable_drs,
                                           jboolean disable_auto_gear,
                                           jboolean enable_unlock,
                                           jboolean enable_tc, jboolean enable_abs,
                                           jlong music_volume_update,
                                           jlong music_volume_start,
                                           jlong audio_source_set_volume) {
    (void) env;
    (void) clazz;
    (void) clutch_field;
    (void) gear_field;

    pedal_hook_config_t pedal_cfg = {
        .enable_control_replacement = (bool) enable_controls,
        .enable_tc = (bool) enable_tc,
        .enable_abs = (bool) enable_abs,
        .set_throttle_offset = (uintptr_t) set_throttle,
        .set_brake_offset = (uintptr_t) set_brake,
        .shift_up_offset = (uintptr_t) shift_up,
        .shift_down_offset = (uintptr_t) shift_down,
        .set_gear_offset = (uintptr_t) set_gear,
        .fixed_update_offset = (uintptr_t) fixed_update,
        .throttle_field_offset = (uintptr_t) throttle_field,
        .brake_field_offset = (uintptr_t) brake_field,
        .actual_throttle_field_offset = (uintptr_t) actual_throttle_field,
        .actual_brake_field_offset = (uintptr_t) actual_brake_field,
        .drivetrain_offset = 0x98,
        .drivetrain_automatic_field_offset = drivetrain_automatic_field,
        .drivetrain_fixed_update_offset = (uintptr_t) drivetrain_fixed_update,
        .drivetrain_do_gear_shifting_offset = (uintptr_t) drivetrain_do_gear_shifting,
        .traction_filter_offset = (uintptr_t) traction_filter,
        .handle_abs_offset = (uintptr_t) handle_abs,
        .player_controls_update_offset = (uintptr_t) player_controls_update
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

    // 15s 延迟路径的 unlock hooks：只装一次（如果 early init 没装的话）。
    // 这里传完整的 GetInstance / OnAlreadyOwned offset —— early initUnlock
    // 可能因 libil2cpp.so 未加载而失败，15s 路径必须能独立完成安装。
    // g_hooks_installed 守卫保证不会重复装。
    unlock_hook_config_t unlock_cfg = {
        .enable_unlock = (bool) enable_unlock,
        .billing_manager_awake_offset = (uintptr_t) billing_manager_awake,
        .billing_manager_get_instance_offset = (uintptr_t) billing_manager_get_instance,
        .billing_manager_initialize_billing_offset = (uintptr_t) billing_manager_initialize_billing,
        .billing_manager_on_owned_none_offset = (uintptr_t) billing_manager_on_owned_none,
        .billing_manager_on_purchase_failed_offset = (uintptr_t) billing_manager_on_purchase_failed,
        .billing_manager_set_unlocked_offset = (uintptr_t) billing_manager_set_unlocked,
        .billing_manager_on_already_owned_offset = (uintptr_t) billing_manager_on_already_owned,
        .billing_manager_is_unlocked_field_offset = (uintptr_t) billing_manager_is_unlocked_field,
        .billing_manager_has_store_connection_field_offset = (uintptr_t) billing_manager_has_store_connection_field,
        .billing_manager_has_completed_ownership_check_field_offset = (uintptr_t) billing_manager_has_completed_ownership_check_field,
    };

    if (!unlock_install_hooks(&unlock_cfg)) {
        LOGE("Failed to install unlock hooks");
    }

    // 主菜单音乐替换 hooks：静音游戏主菜单音乐 + 提供主菜单心跳信号。
    // 开关由 Java 端 JNI 动态设置，这里只装 hook 不强制开启。
    music_hook_config_t music_cfg = {
        .handle_music_volume_update_offset = (uintptr_t) music_volume_update,
        .handle_music_volume_start_offset = (uintptr_t) music_volume_start,
        .audio_source_set_volume_offset = (uintptr_t) audio_source_set_volume,
    };
    if (!music_install_hooks(&music_cfg)) {
        LOGE("Failed to install music hooks");
    }

    g_state.controls_enabled = (bool) enable_controls;
    g_state.drs_enabled = (bool) enable_drs;
    g_state.disable_auto_gear = (bool) disable_auto_gear;
    g_state.native_initialized = true;

    pedal_set_disable_auto_gear((int) g_state.disable_auto_gear);

    LOGI("Native bridge initialized: controls=%d drs=%d disable_auto_gear=%d",
         enable_controls, enable_drs, disable_auto_gear);
}

// 独立的 unlock hooks 安装入口——在 onPackageReady 早期调用，
// 不等 15 秒延迟，让 hook_awake/hook_get_instance 能赶上 BillingManager 早期调用。
// pedal hooks 仍走原来的 15s 延迟路径（writer 线程需要游戏 controller 已存在）。
JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_initUnlock(JNIEnv *env, jclass clazz,
                                                  jboolean enable_unlock,
                                                  jlong billing_manager_awake,
                                                  jlong billing_manager_get_instance,
                                                  jlong billing_manager_initialize_billing,
                                                  jlong billing_manager_on_owned_none,
                                                  jlong billing_manager_on_purchase_failed,
                                                  jlong billing_manager_set_unlocked,
                                                  jlong billing_manager_on_already_owned,
                                                  jlong billing_manager_is_unlocked_field,
                                                  jlong billing_manager_has_store_connection_field,
                                                  jlong billing_manager_has_completed_ownership_check_field) {
    (void) env;
    (void) clazz;

    unlock_hook_config_t unlock_cfg = {
        .enable_unlock = (bool) enable_unlock,
        .billing_manager_awake_offset = (uintptr_t) billing_manager_awake,
        .billing_manager_get_instance_offset = (uintptr_t) billing_manager_get_instance,
        .billing_manager_initialize_billing_offset = (uintptr_t) billing_manager_initialize_billing,
        .billing_manager_on_owned_none_offset = (uintptr_t) billing_manager_on_owned_none,
        .billing_manager_on_purchase_failed_offset = (uintptr_t) billing_manager_on_purchase_failed,
        .billing_manager_set_unlocked_offset = (uintptr_t) billing_manager_set_unlocked,
        .billing_manager_on_already_owned_offset = (uintptr_t) billing_manager_on_already_owned,
        .billing_manager_is_unlocked_field_offset = (uintptr_t) billing_manager_is_unlocked_field,
        .billing_manager_has_store_connection_field_offset = (uintptr_t) billing_manager_has_store_connection_field,
        .billing_manager_has_completed_ownership_check_field_offset = (uintptr_t) billing_manager_has_completed_ownership_check_field,
    };

    LOGI("initUnlock: enable_unlock=%d (early install before 15s delay)", enable_unlock);
    // 提前把 OnAlreadyOwned / GetInstance offset 记到 g_config，
    // force_unlock_via_on_already_owned 在 hook_awake 里调用时能找到。
    LOGI("initUnlock: awake=0x%lx get_instance=0x%lx on_already_owned=0x%lx",
         (unsigned long) billing_manager_awake,
         (unsigned long) billing_manager_get_instance,
         (unsigned long) billing_manager_on_already_owned);

    if (!unlock_install_hooks(&unlock_cfg)) {
        LOGE("Failed to install unlock hooks (early)");
    }
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

JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setTcAbs(JNIEnv *env, jclass clazz,
                                                jboolean enable_tc, jboolean enable_abs) {
    (void) env;
    (void) clazz;
    pedal_set_tc_abs((int) enable_tc, (int) enable_abs);
}

// 主动触发一次强制解锁（不依赖 hook 触发时机）。
// 在 15s 延迟路径中调用：get_Instance → SetUnlocked(true) → OnAlreadyOwned。
JNIEXPORT jboolean JNICALL
Java_tools_alamobile_mod_NativeBridge_forceUnlockNow(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return unlock_force_now() ? JNI_TRUE : JNI_FALSE;
}

// 设置主菜单音乐替换开关（Java 端配置变更时调用）。
JNIEXPORT void JNICALL
Java_tools_alamobile_mod_NativeBridge_setMusicReplace(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void) env;
    (void) clazz;
    music_set_replace_enabled((int) enabled);
}

// 查询主菜单音乐替换开关状态。
JNIEXPORT jboolean JNICALL
Java_tools_alamobile_mod_NativeBridge_isMusicReplaceEnabled(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return music_is_replace_enabled() ? JNI_TRUE : JNI_FALSE;
}

// 查询是否仍在主菜单（最近一次 handleMusicVolume.Update() 心跳 < 2s）。
// Java 端 timer 轮询此方法，用于决定播放/停止替换音乐。
JNIEXPORT jboolean JNICALL
Java_tools_alamobile_mod_NativeBridge_isInMainMenu(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return music_is_in_main_menu() ? JNI_TRUE : JNI_FALSE;
}
