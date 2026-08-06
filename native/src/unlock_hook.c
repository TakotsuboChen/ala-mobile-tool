#include "unlock_hook.h"
#include <android/log.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <link.h>
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

static volatile int g_hooks_installed = 0;

// Use dl_iterate_phdr to get the actual load base of libil2cpp.so.
// dlopen() returns an opaque soinfo* handle, NOT the load base address,
// so we must not use it for offset calculations.
typedef struct {
    const char *name;
    uintptr_t base;
} find_module_ctx_t;

static int find_module_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    find_module_ctx_t *ctx = (find_module_ctx_t *) data;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, ctx->name) != NULL) {
        ctx->base = (uintptr_t) info->dlpi_addr;
        return 1;
    }
    return 0;
}

static uintptr_t get_module_base(const char *module_name) {
    find_module_ctx_t ctx = {.name = module_name, .base = 0};
    dl_iterate_phdr(find_module_callback, &ctx);
    return ctx.base;
}

// IL2CPP string 创建：调用 libil2cpp.so 的 il2cpp_string_new 函数。
// OnAlreadyOwned(string productId) 需要一个 IL2CPP string 参数 "unlock_alamobile"。
// 我们通过 dlsym 找到 il2cpp_string_new，把 C string 转成 IL2CPP string。
static void *(*g_il2cpp_string_new)(const char *str) = NULL;

static void resolve_il2cpp_string_new(void) {
    if (g_il2cpp_string_new != NULL) return;
    void *handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle) {
        g_il2cpp_string_new = (void *(*)(const char *))dlsym(handle, "il2cpp_string_new");
        LOGI("il2cpp_string_new resolved: %p", g_il2cpp_string_new);
    } else {
        LOGE("Failed to dlopen libil2cpp.so for il2cpp_string_new: %s", dlerror());
    }
}

// BillingManager.OnAlreadyOwned(string) 的函数指针类型。
// IL2CPP 实例方法的调用约定：第一个参数是 this（BillingManager 实例），
// 第二个参数是方法的第一个参数（IL2CPP string 指针）。
typedef void (*on_already_owned_func_t)(void *this_ptr, void *il2cpp_string);

static on_already_owned_func_t g_on_already_owned = NULL;

// 主动注入"已拥有"状态：在 BillingManager.Awake() hook 里直接调
// OnAlreadyOwned("unlock_alamobile")，让 Unity 侧走完整的解锁链
// (SetUnlocked(true) → OnUnlockedChanged → 持久化 PlayerPrefs AnciTuttu)。
// 这不依赖游戏自己发起 BillingBridge.checkOwned → sendUnityMessage 回调链，
// 在 vivo 等设备上游戏不主动调 checkOwned 时也能完成解锁。
static void force_unlock_via_on_already_owned(void *this_ptr) {
    if (this_ptr == NULL) return;

    resolve_il2cpp_string_new();
    if (g_il2cpp_string_new == NULL) {
        LOGE("force_unlock: il2cpp_string_new not available, cannot create string");
        return;
    }

    if (g_on_already_owned == NULL) {
        uintptr_t base = get_module_base("libil2cpp.so");
        if (base == 0) {
            LOGE("force_unlock: libil2cpp.so base not found");
            return;
        }
        g_on_already_owned = (on_already_owned_func_t)(base + g_config.billing_manager_on_already_owned_offset);
        LOGI("force_unlock: OnAlreadyOwned at %p", g_on_already_owned);
    }

    // 创建 IL2CPP string "unlock_alamobile"
    void *product_id_str = g_il2cpp_string_new("unlock_alamobile");
    if (product_id_str == NULL) {
        LOGE("force_unlock: il2cpp_string_new returned NULL");
        return;
    }

    LOGI("force_unlock: calling OnAlreadyOwned(\"unlock_alamobile\") on BillingManager %p", this_ptr);
    g_on_already_owned(this_ptr, product_id_str);
    LOGI("force_unlock: OnAlreadyOwned called successfully");
}

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

    // 主动注入"已拥有"状态——调用 OnAlreadyOwned("unlock_alamobile")
    // 让 Unity 侧走完整解锁链 (SetUnlocked → OnUnlockedChanged → 持久化)。
    // 不依赖游戏自己发起 BillingBridge.checkOwned → sendUnityMessage。
    force_unlock_via_on_already_owned(this_ptr);

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

    LOGI("unlock_install_hooks: enable_unlock=%d", g_config.enable_unlock);
    LOGI("unlock_install_hooks: awake_offset=0x%lx init_billing_offset=0x%lx on_owned_none_offset=0x%lx on_purchase_failed_offset=0x%lx",
         (unsigned long)g_config.billing_manager_awake_offset,
         (unsigned long)g_config.billing_manager_initialize_billing_offset,
         (unsigned long)g_config.billing_manager_on_owned_none_offset,
         (unsigned long)g_config.billing_manager_on_purchase_failed_offset);

    if (g_hooks_installed) {
        LOGI("Unlock hooks already installed, skipping");
        return true;
    }

    if (!g_config.enable_unlock) {
        LOGI("Unlock feature is disabled in config");
        return true;
    }

    LOGI("Installing unlock hooks...");

    // Get the actual load base of libil2cpp.so via dl_iterate_phdr.
    // dlopen() returns an opaque handle, NOT the load base address.
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address");
        return false;
    }
    LOGI("libil2cpp.so base address: 0x%" PRIxPTR, base);

    // Hook 1: BillingManager.Awake()
    if (g_config.billing_manager_awake_offset != 0) {
        void *awake_addr = (void *)(base + g_config.billing_manager_awake_offset);
        LOGI("Hooking BillingManager.Awake() at %p", awake_addr);

        void *result = shadowhook_hook_sym_addr(
            awake_addr,
            (void *)hook_awake,
            (void **)&orig_awake
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.Awake()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.Awake(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("BillingManager.Awake() offset is 0, skipping");
    }

    // Hook 2: BillingManager.InitializeBilling()
    if (g_config.billing_manager_initialize_billing_offset != 0) {
        void *init_billing_addr = (void *)(base + g_config.billing_manager_initialize_billing_offset);
        LOGI("Hooking BillingManager.InitializeBilling() at %p", init_billing_addr);

        void *result = shadowhook_hook_sym_addr(
            init_billing_addr,
            (void *)hook_initialize_billing,
            (void **)&orig_init_billing
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.InitializeBilling()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.InitializeBilling(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    // Hook 3: BillingManager.OnOwnedNone()
    if (g_config.billing_manager_on_owned_none_offset != 0) {
        void *on_owned_none_addr = (void *)(base + g_config.billing_manager_on_owned_none_offset);
        LOGI("Hooking BillingManager.OnOwnedNone() at %p", on_owned_none_addr);

        void *result = shadowhook_hook_sym_addr(
            on_owned_none_addr,
            (void *)hook_on_owned_none,
            (void **)&orig_on_owned_none
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnOwnedNone()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.OnOwnedNone(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    // Hook 4: BillingManager.OnPurchaseFailed()
    if (g_config.billing_manager_on_purchase_failed_offset != 0) {
        void *on_purchase_failed_addr = (void *)(base + g_config.billing_manager_on_purchase_failed_offset);
        LOGI("Hooking BillingManager.OnPurchaseFailed() at %p", on_purchase_failed_addr);

        void *result = shadowhook_hook_sym_addr(
            on_purchase_failed_addr,
            (void *)hook_on_purchase_failed,
            (void **)&orig_on_purchase_failed
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnPurchaseFailed()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.OnPurchaseFailed(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    LOGI("Unlock hooks installation complete");
    g_hooks_installed = 1;
    return true;
}

void unlock_uninstall_hooks(void) {
    LOGI("Uninstalling unlock hooks");

    // No need to dlclose - we used dl_iterate_phdr instead of dlopen
    orig_awake = NULL;
    orig_init_billing = NULL;
    orig_on_owned_none = NULL;
    orig_on_purchase_failed = NULL;
    orig_set_unlocked = NULL;

    LOGI("Unlock hooks uninstalled");
}
