#ifndef UNLOCK_HOOK_H
#define UNLOCK_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool enable_unlock;

    // BillingManager.Awake() — entry point, triggers InitializeBilling → CheckOwned → Google Play
    uintptr_t billing_manager_awake_offset;

    // BillingManager.GetInstance() — 兜底注入点。静态方法返回单例，
    // 任何代码访问 BillingManager 都会调它，比 Awake 触发概率高。
    uintptr_t billing_manager_get_instance_offset;

    // BillingManager.InitializeBilling() — sets up BillingBridge and queries
    uintptr_t billing_manager_initialize_billing_offset;

    // BillingManager.OnOwnedNone() — shows the ATTENTION error dialog
    uintptr_t billing_manager_on_owned_none_offset;

    // BillingManager.OnPurchaseFailed() — shows purchase failure dialog
    uintptr_t billing_manager_on_purchase_failed_offset;

    // BillingManager.SetUnlocked(bool) — actually sets unlock state
    uintptr_t billing_manager_set_unlocked_offset;

    // BillingManager.OnAlreadyOwned(string) — 主动注入"已拥有"回调
    // 在 hook_awake 里调用此方法，绕过 Java BillingBridge 依赖
    uintptr_t billing_manager_on_already_owned_offset;

    // BillingManager instance fields
    uintptr_t billing_manager_is_unlocked_field_offset;           // 0x20
    uintptr_t billing_manager_has_store_connection_field_offset;  // 0x21
    uintptr_t billing_manager_has_completed_ownership_check_field_offset; // 0x22
} unlock_hook_config_t;

bool unlock_install_hooks(const unlock_hook_config_t *config);
void unlock_uninstall_hooks(void);

/**
 * 主动触发一次强制解锁，不依赖 hook 触发时机。
 * 在 15s 延迟路径中作为 one-shot 调用：
 * 1) 通过 get_Instance() 获取 BillingManager 单例指针
 * 2) 如果非空，直接调 SetUnlocked(true) 解锁
 * 3) 同时调 OnAlreadyOwned("unlock_alamobile") 走 Unity 完整链
 *
 * 返回值：true=解锁成功或已解锁，false=拿不到实例
 */
bool unlock_force_now(void);

#ifdef __cplusplus
}
#endif

#endif // UNLOCK_HOOK_H
