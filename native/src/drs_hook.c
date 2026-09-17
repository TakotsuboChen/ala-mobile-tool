#include "drs_hook.h"
#include "native_log.h"
#include "pedal_hook.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <link.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

// ═══════════════════════════════════════════════════════════════════════════
// carModifier 实例字段偏移
// ⚠️ 升版核对清单 = 本文件全部 OFF_* 常量（见 CLAUDE.md 红线）。
// 8.0.6 (200150) 实测值：
// ═══════════════════════════════════════════════════════════════════════════
#define OFF_CARMODIFIER_CAR_INPUTS     0xD8   // private IRDSCarControllInput icinp
#define OFF_CARMODIFIER_PLAYERCAR      0x9C   // public bool playercar
#define OFF_CARMODIFIER_DRS_STATE      0xF8   // private DRSState _currentDRSState
#define OFF_CARMODIFIER_DRS_ALERT_ZONE 0x47C  // private bool drsAlertPlayedThisZone
#define OFF_CARMODIFIER_CAR_TYPE       0x24   // public CarType carType (0=GroundEffectEra 1=DoubleDRSEra)

// DRSState 枚举常量（非偏移）。Deployable = 游戏已确认"当前可开启"。
#define DRS_STATE_DEPLOYABLE           3
// CarType 枚举常量（非偏移）。2026 主动空力车型——其状态机会逐帧 3↔4 循环，
// 需要 per-zone 闩锁（见 proxy_on_drs_state_changed）。
#define CAR_TYPE_DOUBLE_DRS_ERA        1

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

static void *g_state_stub = NULL;
static void *g_state_orig = NULL;
// ManualDRSUsage 的绝对地址在 install 时解析一次并缓存 —— 它位于每辆车都会经过的
// 状态变化回调里（deployable 会在玩家车和 AI 车身上反复出现），回调内不能再调
// dl_iterate_phdr（内部持锁，高频调用是纯浪费）。
static void *g_manual_drs_addr = NULL;
static volatile int g_hooks_installed = 0;

// 运行时开关（Java 配置同步）。与 g_config.enable_auto_drs 二选一为真即启用，
// 便于 Java 端在不重装 hook 的前提下改开关（同 pedal_set_tc_abs 模式）。
static volatile int g_auto_active = 0;

// 重入守卫：auto 部署会在 orig 返回后再调 ManualDRSUsage，后者同步回调
// OnDRSStateChanged(4)。状态判据（!=3 直接返回）已能挡住自激，这里再上一道
// 保险，防 Unity 内部异常路径上的意外重入。
static volatile int g_in_proxy = 0;

// ═══════════════════════════════════════════════════════════════════════════
// 玩家车白名单
// ═══════════════════════════════════════════════════════════════════════════
// carModifier 是每辆车一份的组件，OnDRSStateChanged 会在**所有车**上触发
// （AI 车同样跑 DRS 状态机）。必须严格白名单，否则会替 AI 车按 DRS 键。
//
// 首选判据：carModifier.icinp(0xD8) == pedal_get_controller()。后者由
// IRDSPlayerControls.Update 写入（该组件只挂玩家车 GameObject），是唯一的
// 可靠身份源；这也与 TC/ABS 的 is_target_player_car 严格白名单同源。
//
// 兜底：pedal hooks 未启用（用户没开踏板替换）时 g_player_controller 恒 NULL，
// 此时回落到 carModifier.playercar(0x9C) —— 游戏自己的玩家车标记。它比
// "is_player_controller 字段探测"可靠（那是看 IRDSCarControllInput.playerControls
// 是否非空，AI 车也可能非空），但仍不如指针白名单，故只作次选。
static int drs_is_player_car(void *cm) {
    if (cm == NULL) {
        return 0;
    }
    void *controller = pedal_get_controller();
    if (controller != NULL) {
        void *icinp = *(void **) ((uintptr_t) cm + OFF_CARMODIFIER_CAR_INPUTS);
        return icinp != NULL && icinp == controller;
    }
    return *(volatile unsigned char *) ((uintptr_t) cm + OFF_CARMODIFIER_PLAYERCAR) != 0;
}

// ═══════════════════════════════════════════════════════════════════════════
// Hook: carModifier::OnDRSStateChanged(DRSState newState)
// ═══════════════════════════════════════════════════════════════════════════
// 这是「游戏允许开启 DRS/AA」的广播点 —— 状态机推进到 Deployable(3) 时
// 游戏在此播提示音并点亮 HUD，说明此刻开启必然合法。
//
// 覆盖范围（无需模块分析赛道）：
//   • 地效车 DRS      —— OnTriggerEnter 物理触发区 → 状态 3
//   • 2026 主动空力   —— CheckActiveAeroAvailability 协程查 activeAerozone → 状态 3
// 两条管线的区域判定方式完全不同（这正对应"同赛道两套区域不同"），但都经此
// 汇合，故一个 hook 点即可。
//
// ⚠️ 本 hook 装在**所有车**上（每车一份 carModifier），属 passthrough 性质：
// 日志只允许出现在玩家车 + 实际自动部署的分支内，否则 AI 车每次 DRS 状态
// 变化都会刷日志（CLAUDE.md 日志红线）。
static void proxy_on_drs_state_changed(void *this, int new_state, void *method_info) {
    // ⚠️ 时序要点：drsAlertPlayedThisZone(0x47C) 是"本区域已提示过"标志——
    // orig 只在**首次**进入 Deployable 时把它置 1，同区域内不再清（离开区域时
    // 由 HandleDRS 清零）。所以必须在调 orig **之前**读，读值即"本区域是否已
    // 处理过"；调用之后再读永远是 1，闩锁失效。
    int zone_seen = 0;
    int is_double_drs_era = 0;
    if (new_state == DRS_STATE_DEPLOYABLE && (g_auto_active || g_config.enable_auto_drs)) {
        zone_seen = *(volatile unsigned char *) ((uintptr_t) this + OFF_CARMODIFIER_DRS_ALERT_ZONE) != 0;
        is_double_drs_era = *(volatile int *) ((uintptr_t) this + OFF_CARMODIFIER_CAR_TYPE) == CAR_TYPE_DOUBLE_DRS_ERA;
    }

    typedef void (*orig_t)(void *, int, void *);
    if (g_state_orig != NULL) {
        ((orig_t) g_state_orig)(this, new_state, method_info);
    }

    // ── 以下为玩家车专属路径，可安全打日志 ──
    if (new_state != DRS_STATE_DEPLOYABLE) {
        return; // Active(4)/Idle(1) 等状态变化与自动部署无关
    }
    if (!g_auto_active && !g_config.enable_auto_drs) {
        return;
    }
    if (g_in_proxy) {
        return;
    }
    // ️ 每区域只自动部署一次。**这条不是优化，是必需**：2026 主动空力车
    // （carType==DoubleDRSEra）的状态机是逐帧循环的——HandleDRS 把 Active(4)
    // 重置回 Idle(1)，下一帧 ManageActiveAero 见车仍在区域内又置回 Deployable(3)，
    // 于是 OnDRSStateChanged(3) 每帧都触发（实机实测：单区域 ~60 次/秒，
    // 2.5 分钟内 154 条，最高 43 条/秒）。那是游戏刻意的「可重复开启」（reuseDRS）
    // 语义，不是可部署次数。逐帧的 3↔4 抖动还会让翼片目标角反复改向、动画
    // 迟迟不收敛。
    //
    // 闩锁用游戏自己的 per-zone 标志 drsAlertPlayedThisZone(0x47C)——它由
    // ManageActiveAero 在「离开区域」时清零，区域内恒为 1。⚠️ 必须在 orig
    // **之前**读：orig 会把未处理过的区域置 1，之后再读恒为 1，闩锁失效。
    //
    // ⚠️ 只对 DoubleDRSEra 生效：0x47C 的唯一清零点在 ManageActiveAero（2026 车
    // 专属方法），地效车（GroundEffectEra）的该标志一旦置 1 便整场比赛不复位——
    // 对它用闩锁会导致"每场比赛只能自动开一次"。地效车本身 3→4 后稳定，无循环，
    // 靠状态机的自然事件即可（离开区域由 HandleDRS 复位到 Idle，进新区域再来一次）。
    if (is_double_drs_era && zone_seen) {
        return;
    }
    if (!drs_is_player_car(this)) {
        return; // AI 车：绝不代按 DRS
    }

    uintptr_t manual = g_config.manual_drs_usage_offset;
    if (manual == 0) {
        return;
    }
    void *manual_fn = g_manual_drs_addr;

    g_in_proxy = 1;
    typedef void (*manual_t)(void *, void *);
    ((manual_t) manual_fn)(this, NULL);
    g_in_proxy = 0;

    int state_after = *(int *) ((uintptr_t) this + OFF_CARMODIFIER_DRS_STATE);
    LOGI("Auto DRS deployed (state %d -> %d)", DRS_STATE_DEPLOYABLE, state_after);
}

// ═══════════════════════════════════════════════════════════════════════════
// Install / uninstall
// ═══════════════════════════════════════════════════════════════════════════
// ⚠️ 与旧实现的关键差异：hook 恒装上（不再因开关为假而 return）。
//   • OnDRSStateChanged 是信号源而非拦截点 —— 它不做拦截、只做旁路观察，
//     因为信号本身不能靠开关"重装"（重装时机与赛道加载耦合，且 ShadowHook
//     重复装卸在该高频方法上有风险）。开关完全由 g_auto_active 在回调内判定。
//   • 旧实现在开关为假时直接不装 hook，并把 drsToggle 的调用全部吞掉 —— 那
//     等于"模块存在即禁用玩家 DRS"，是必须修掉的行为缺陷（见 CLAUDE.md）。
bool drs_install_hooks(const drs_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_auto_active = g_config.enable_auto_drs ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.on_drs_state_changed_offset == 0) {
        LOGE("DRS hook skipped: on_drs_state_changed_offset not provided");
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for DRS hook");
        return false;
    }

    uintptr_t target = base + g_config.on_drs_state_changed_offset;
    g_state_stub = shadowhook_hook_sym_addr(
            (void *) target,
            (void *) proxy_on_drs_state_changed,
            (void **) &g_state_orig);
    if (g_state_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(OnDRSStateChanged) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        return false;
    }

    LOGI("Hooked OnDRSStateChanged at 0x%" PRIxPTR " (auto_drs=%d)",
         target, g_auto_active);

    if (g_config.manual_drs_usage_offset != 0) {
        g_manual_drs_addr = (void *) (base + g_config.manual_drs_usage_offset);
        LOGI("ManualDRSUsage resolved at 0x%" PRIxPTR, (uintptr_t) g_manual_drs_addr);
    } else {
        LOGE("ManualDRSUsage offset missing — auto deploy will be a no-op");
    }

    g_hooks_installed = 1;
    return true;
}

void drs_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_state_stub != NULL) {
        shadowhook_unhook(g_state_stub);
        g_state_stub = NULL;
        g_state_orig = NULL;
    }
    g_manual_drs_addr = NULL;
    g_auto_active = 0;
    g_hooks_installed = 0;
    g_config.enable_auto_drs = false;
}

void drs_set_active(int active) {
    g_auto_active = active ? 1 : 0;
}
