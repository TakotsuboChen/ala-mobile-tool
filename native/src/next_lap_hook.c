// ═══════════════════════════════════════════════════════════════════════════
// next_lap_hook.c — 禁止删除下一圈成绩（enableProtectNextLap）
//
// 游戏在赛道后段（trackPercentage > 0.8）冲出赛道限制时，会把**本圈和下一圈**
// 的成绩一起作废（odometerHandler.InvalidateLap 同时置 alreadyInvalidated[0]
// 与 [1]，并弹 "This and next lap times deleted"）。刷圈时一次失误要牺牲两圈，
// 效率减半。本功能让这种情况只作废本圈。
//
// ── 反汇编实证（8.0.6 / 200150，build/v8.0.6-official coex libil2cpp.so）──
//
// odometerHandler.InvalidateLap(float trackPercentage, bool isInPit) @ RVA 0x1A0DA2C
//
// 参数落位（AArch64 IL2CPP 实例方法）：x0=this, s0=trackPercentage, w1=isInPit,
// x2=method_info。**GPR 与 FPR 是独立寄存器堆**（不像 AArch32 的 r0/s0 重叠），
// this 与 float 首参不冲突。
//
// trackPercentage 在方法内只被两处 `fcmp s8, s0` 使用，s0 恒为 .rodata
// 0x929B2C = 0.8f：
//   0x1A0DAE4  —— [0] 已置位（本圈已作废）时的二次出界路径 → "Next lap deleted"
//   0x1A0DBB0  —— [0] 未置位的首次出界路径     → "This and next lap deleted"
// 两处 `alreadyInvalidated[1] = 1`（0x1A0DB08 / 0x1A0DBCC）都在 `> 0.8` 分支内；
// `<= 0.8` 分支（0x1A0DBD8 起）只置 [0]=1 并弹 "Lap time deleted"。
//
// ⇒ 把 trackPercentage 改写为 0，游戏自己的全部逻辑（标志、文案、状态机）
//   自然走 `<= 0.8` 分支：本圈照常作废、下一圈保住、提示文案同步变成
//   "Lap time deleted"（与模块实际行为一致）。
//
// ── 为什么改写参数而不是"orig 之后清 [1]" ──
// 后者会让提示文案仍是 "This and next lap times deleted"，与模块实际行为
// 矛盾（用户会以为功能没生效）。参数改写让游戏自己走正确分支，文案自动正确。
//
// ── 调用图证据（升版必核）──
// InvalidateLap 全 .so 只有一个 `bl` 调用者：
//   carModifier.TractionControlDynamicAssist @0x1769A60（RVA 0x176986C）
//   后者只被 carModifier.Update 调用（0x176A8D4），且 Update 里调用前有
//   `ldrb w8, [x19, #0x9C]`（playercar）守卫 ⇒ **只作用于玩家车**，无需白名单。
// ⚠️ 升版时必核：① InvalidateLap 的调用者集合是否仍是这一个；
//   ② carModifier.Update 的 playercar(0x9C) 守卫是否仍在；
//   ③ 0.8f（.rodata）与两处 fcmp 是否仍在同一方法内。
//
// ── 红线自查 ──
// 函数级 hook（shadowhook_hook_sym_addr），proxy 是普通 C 函数调用，可自由使用
// 浮点 —— 与「指令级拦截回调必须 float-free」是两回事。不写任何游戏字段，
// 只改写一个入参。hook 恒装上（开关在回调内判），关掉时逐字节等于原版。
// ═══════════════════════════════════════════════════════════════════════════
#include "next_lap_hook.h"
#include "native_log.h"
#include "crash_hook.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <link.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

static next_lap_hook_config_t g_config = {0};

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

static void *g_invalidate_stub = NULL;
static void *g_invalidate_orig = NULL;
static volatile int g_hooks_installed = 0;

// 运行时开关（Java 配置同步）。与 g_config.enable_protect_next_lap 二选一为真
// 即启用（同 overtake_hook.c 的双判据惯例），Java 端改开关无需重装 hook。
static volatile int g_protect_active = 0;

static inline int protect_enabled(void) {
    return g_protect_active || g_config.enable_protect_next_lap;
}

// odometerHandler::InvalidateLap(float trackPercentage, bool isInPit)
//   x0=this, s0=trackPercentage, w1=isInPit, x2=method_info
static void proxy_invalidate_lap(void *this, float track_percentage, int is_in_pit,
                                 void *method_info) {
    typedef void (*t)(void *, float, int, void *);

    // 开关开启时把 trackPercentage 压到 0 —— 让游戏走 `<= 0.8` 分支，
    // 只作废本圈、保住下一圈，提示文案同步变为 "Lap time deleted"。
    // ⚠️ 此处打日志是**可观测性刚需**：本 hook 改变游戏原生判定，必须能从
    //    日志确认它真的拦下了"连带删除下一圈"（原始 trackPercentage > 0.8
    //    就是铁证）。非高频路径（每圈至多几次、且只作用于玩家车），
    //    不违反"透传 hook 保持静默"红线。
    if (protect_enabled() && track_percentage > 0.0f) {
        LOGI("protect_next_lap: InvalidateLap trackPercentage=%.4f (was >0.8 → next lap "
             "would be deleted) → clamped to 0 (this lap only, isInPit=%d)",
             track_percentage, is_in_pit);
        ((t) g_invalidate_orig)(this, 0.0f, is_in_pit, method_info);
        return;
    }
    ((t) g_invalidate_orig)(this, track_percentage, is_in_pit, method_info);
}

bool next_lap_install_hooks(const next_lap_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_protect_active = g_config.enable_protect_next_lap ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.invalidate_lap_offset == 0) {
        LOGE("Next-lap hook skipped: invalidate_lap_offset=0");
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for next-lap hook");
        return false;
    }

    uintptr_t target = base + g_config.invalidate_lap_offset;
    g_invalidate_stub = shadowhook_hook_sym_addr(
            (void *) target,
            (void *) proxy_invalidate_lap,
            (void **) &g_invalidate_orig);
    if (g_invalidate_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(InvalidateLap) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        return false;
    }

    crash_hook_register("InvalidateLap", (void *) target);
    LOGI("Hooked odometerHandler.InvalidateLap at 0x%" PRIxPTR
         " (protect_next_lap=%d)", target, g_protect_active);

    g_hooks_installed = 1;
    return true;
}

void next_lap_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_invalidate_stub != NULL) {
        shadowhook_unhook(g_invalidate_stub);
        g_invalidate_stub = NULL;
        g_invalidate_orig = NULL;
    }
    g_protect_active = 0;
    g_hooks_installed = 0;
    g_config.enable_protect_next_lap = false;
}

void next_lap_set_active(int active) {
    g_protect_active = active ? 1 : 0;
}
