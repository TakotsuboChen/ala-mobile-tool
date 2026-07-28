#include "unlock_hook.h"
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "shadowhook.h"

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static unlock_hook_config_t g_config = {0};
static void *g_il2cpp_handle = NULL;

// Original function pointers
typedef void (*awake_func_t)(void *this_ptr);
typedef void (*init_billing_func_t)(void *this_ptr);
typedef void (*on_owned_none_func_t)(void *this_ptr);
typedef void (*on_purchase_failed_func_t)(void *this_ptr);
typedef void (*set_unlocked_func_t)(void *this_ptr, bool unlocked);

static awake_func_t orig_awake = NULL;
static init_billing_func_t orig_init_billing = NULL;
static on_owned_none_func_t orig_on_owned_none = NULL;
static on_purchase_failed_func_t orig_on_purchase_failed = NULL;
static set_unlocked_func_t orig_set_unlocked = NULL;

// Hook for BillingManager.Awake() - completely skip it and set unlock state
static void hook_awake(void *this_ptr) {
    LOGI("BillingManager.Awake() hooked - skipping original, forcing unlock");

    if (!this_ptr) {
        LOGE("Awake: this_ptr is NULL");
        return;
    }

    // Set all unlock-related fields to true
    bool *is_unlocked = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_is_unlocked_field_offset);
    bool *has_store_connection = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_has_store_connection_field_offset);
    bool *has_completed_check = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_has_completed_ownership_check_field_offset);

    *is_unlocked = true;
    *has_store_connection = true;
    *has_completed_check = true;

    LOGI("Set BillingManager fields: IsUnlocked=true, HasStoreConnection=true, HasCompletedOwnershipCheck=true");

    // DO NOT call original Awake() - it will trigger InitializeBilling() which queries Google Play
}

// Hook for BillingManager.InitializeBilling() - prevent billing initialization
static void hook_initialize_billing(void *this_ptr) {
    LOGI("BillingManager.InitializeBilling() hooked - skipping");
    // Do nothing - prevent any billing initialization
}

// Hook for BillingManager.OnOwnedNone() - prevent error dialog
static void hook_on_owned_none(void *this_ptr) {
    LOGI("BillingManager.OnOwnedNone() hooked - blocking error dialog");
    // Do nothing - prevent error dialog from showing
}

// Hook for BillingManager.OnPurchaseFailed() - prevent failure dialog
static void hook_on_purchase_failed(void *this_ptr) {
    LOGI("BillingManager.OnPurchaseFailed() hooked - blocking failure dialog");
    // Do nothing - prevent failure dialog from showing
}

bool unlock_install_hooks(const unlock_hook_config_t *config) {
    if (!config) {
        LOGE("unlock_install_hooks: config is NULL");
        return false;
    }

    memcpy(&g_config, config, sizeof(unlock_hook_config_t));

    if (!g_config.enable_unlock) {
        LOGI("Unlock feature is disabled in config");
        return true;
    }

    LOGI("Installing unlock hooks...");

    // Load libil2cpp.so
    g_il2cpp_handle = dlopen("libil2cpp.so", RTLD_NOW);
    if (!g_il2cpp_handle) {
        LOGE("Failed to dlopen libil2cpp.so: %s", dlerror());
        return false;
    }

    LOGI("libil2cpp.so loaded at %p", g_il2cpp_handle);

    // Hook 1: BillingManager.Awake()
    if (g_config.billing_manager_awake_offset != 0) {
        void *awake_addr = (void *)((uint8_t *)g_il2cpp_handle + g_config.billing_manager_awake_offset);
        LOGI("Hooking BillingManager.Awake() at %p", awake_addr);

        void *result = shadowhook_hook_sym_addr(
            awake_addr,
            (void *)hook_awake,
            (void **)&orig_awake
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.Awake()");
        } else {
            LOGE("Failed to hook BillingManager.Awake()");
        }
    } else {
        LOGW("BillingManager.Awake() offset is 0, skipping");
    }

    // Hook 2: BillingManager.InitializeBilling()
    if (g_config.billing_manager_initialize_billing_offset != 0) {
        void *init_billing_addr = (void *)((uint8_t *)g_il2cpp_handle + g_config.billing_manager_initialize_billing_offset);
        LOGI("Hooking BillingManager.InitializeBilling() at %p", init_billing_addr);

        void *result = shadowhook_hook_sym_addr(
            init_billing_addr,
            (void *)hook_initialize_billing,
            (void **)&orig_init_billing
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.InitializeBilling()");
        } else {
            LOGE("Failed to hook BillingManager.InitializeBilling()");
        }
    }

    // Hook 3: BillingManager.OnOwnedNone()
    if (g_config.billing_manager_on_owned_none_offset != 0) {
        void *on_owned_none_addr = (void *)((uint8_t *)g_il2cpp_handle + g_config.billing_manager_on_owned_none_offset);
        LOGI("Hooking BillingManager.OnOwnedNone() at %p", on_owned_none_addr);

        void *result = shadowhook_hook_sym_addr(
            on_owned_none_addr,
            (void *)hook_on_owned_none,
            (void **)&orig_on_owned_none
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnOwnedNone()");
        } else {
            LOGE("Failed to hook BillingManager.OnOwnedNone()");
        }
    }

    // Hook 4: BillingManager.OnPurchaseFailed()
    if (g_config.billing_manager_on_purchase_failed_offset != 0) {
        void *on_purchase_failed_addr = (void *)((uint8_t *)g_il2cpp_handle + g_config.billing_manager_on_purchase_failed_offset);
        LOGI("Hooking BillingManager.OnPurchaseFailed() at %p", on_purchase_failed_addr);

        void *result = shadowhook_hook_sym_addr(
            on_purchase_failed_addr,
            (void *)hook_on_purchase_failed,
            (void **)&orig_on_purchase_failed
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnPurchaseFailed()");
        } else {
            LOGE("Failed to hook BillingManager.OnPurchaseFailed()");
        }
    }

    LOGI("Unlock hooks installation complete");
    return true;
}

void unlock_uninstall_hooks(void) {
    LOGI("Uninstalling unlock hooks");

    if (g_il2cpp_handle) {
        dlclose(g_il2cpp_handle);
        g_il2cpp_handle = NULL;
    }

    orig_awake = NULL;
    orig_init_billing = NULL;
    orig_on_owned_none = NULL;
    orig_on_purchase_failed = NULL;
    orig_set_unlocked = NULL;

    LOGI("Unlock hooks uninstalled");
}
