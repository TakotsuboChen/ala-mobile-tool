#include "drs_hook.h"
#include "native_log.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

static drs_hook_config_t g_config = {0};

typedef struct {
    const char *name;
    uintptr_t base;
} find_module_ctx_t;

static int find_module_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    find_module_ctx_t *ctx = (find_module_ctx_t *) data;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, ctx->name) != NULL) {
        ctx->base = (uintptr_t) info->dlpi_addr;
        return 1; // stop iteration
    }
    return 0;
}

static uintptr_t get_module_base(const char *module_name) {
    find_module_ctx_t ctx = {.name = module_name, .base = 0};
    dl_iterate_phdr(find_module_callback, &ctx);
    return ctx.base;
}

// Set by the Java overlay when the user toggles auto DRS / DRS override.
static volatile int g_drs_requested = 0;

static void *g_drs_stub = NULL;
static void *g_drs_orig = NULL;
static volatile int g_hooks_installed = 0;


// IRDSCarControllInput::drsToggle(void)  (instance method, no args)
static void proxy_drs_toggle(void *this) {
    if (g_drs_requested) {
        typedef void (*orig_t)(void *);
        if (g_drs_orig != NULL) {
            ((orig_t) g_drs_orig)(this);
        }
    }
    // When auto DRS is not requested, swallow the call so the game does not
    // toggle DRS.  This is a conservative default while telemetry reading is
    // still being reverse-engineered.
}

bool drs_install_hooks(const drs_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_auto_drs) {
        LOGI("Auto DRS disabled");
        return true;
    }

    if (g_hooks_installed) {
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for DRS hook");
        return false;
    }
    LOGI("libil2cpp.so base address for DRS hook: 0x%" PRIxPTR, base);

    if (g_config.drs_toggle_offset != 0) {
        uintptr_t target = base + g_config.drs_toggle_offset;
        g_drs_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_drs_toggle,
                (void **) &g_drs_orig);
        if (g_drs_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(drsToggle) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked drsToggle at 0x%" PRIxPTR, target);
        }
    }

    g_hooks_installed = 1;
    return true;
}

void drs_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }

    if (g_drs_stub != NULL) {
        shadowhook_unhook(g_drs_stub);
        g_drs_stub = NULL;
        g_drs_orig = NULL;
    }

    g_drs_requested = 0;
    g_hooks_installed = 0;
    g_config.enable_auto_drs = false;
}

void drs_set_active(int active) {
    g_drs_requested = active ? 1 : 0;
}
