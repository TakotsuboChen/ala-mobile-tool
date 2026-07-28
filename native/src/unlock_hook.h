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

    // BillingManager.InitializeBilling() — sets up BillingBridge and queries
    uintptr_t billing_manager_initialize_billing_offset;

    // BillingManager.OnOwnedNone() — shows the ATTENTION error dialog
    uintptr_t billing_manager_on_owned_none_offset;

    // BillingManager.OnPurchaseFailed() — shows purchase failure dialog
    uintptr_t billing_manager_on_purchase_failed_offset;

    // BillingManager.SetUnlocked(bool) — actually sets unlock state
    uintptr_t billing_manager_set_unlocked_offset;

    // BillingManager instance fields
    uintptr_t billing_manager_is_unlocked_field_offset;           // 0x20
    uintptr_t billing_manager_has_store_connection_field_offset;  // 0x21
    uintptr_t billing_manager_has_completed_ownership_check_field_offset; // 0x22
} unlock_hook_config_t;

bool unlock_install_hooks(const unlock_hook_config_t *config);
void unlock_uninstall_hooks(void);

#ifdef __cplusplus
}
#endif

#endif // UNLOCK_HOOK_H
