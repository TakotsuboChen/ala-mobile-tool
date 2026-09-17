#include "overtake_hook.h"
#include "native_log.h"
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
// 字段偏移
// ⚠️ 升版核对清单 = 本文件全部 OFF_* 常量（见 CLAUDE.md 红线）。
// 8.0.6 (200150) 实测值：
// ═══════════════════════════════════════════════════════════════════════════
#define OFF_ODOMETER_HYBRID         0x440  // odometerHandler.hC (HybridComponent)
#define OFF_HYBRID_OVERTAKE_ACTIVE  0xC4   // private bool OvertakeActive
// public float currentCapacity（电量 0..1；doubleDRSEra 另有 0xFC 作容量上限）。
// 游戏自己的 EnableOTK 第 6 条守卫即 `currentCapacity <= 0 → return`
//（反汇编实证 0x1A275A4~0x1A275AC），故本模块用同一判据判断"锁定态是否还有效"。
#define OFF_HYBRID_CAPACITY         0x74

static overtake_hook_config_t g_config = {0};

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

static void *g_press_stub = NULL;
static void *g_press_orig = NULL;
static void *g_release_stub = NULL;
static void *g_release_orig = NULL;
// HybridComponent::DisableOTK —— install 时解析缓存。回调属玩家点按路径
//（每局次数极少），本不必像 DRS 那样严格，但保持同样的 install 期解析惯例：
// 回调内不做 dl_iterate_phdr（持锁）。
static void *g_disable_otk_addr = NULL;
static volatile int g_hooks_installed = 0;

// 运行时开关（Java 配置同步）。与 g_config.enable_latch_overtake 二选一为真
// 即启用（同 drs_hook.c 的双判据惯例），Java 端改开关无需重装 hook。
static volatile int g_latch_active = 0;

// 「当前锁定态是本模块在**哪个实例**上建立的」—— 只有回调的 this 与之相同，
// 抬起才会被吞掉。存实例指针而非 bool：HybridComponent 每车一份，实例比对
// 天然免疫"另一个实例的抬起事件误吞本实例的锁定态"。
//
// 是否真的开着以游戏自己的 OvertakeActive(0xC4) 为权威判据（见 proxy_touch_press_otk），
// 本字段只回答"这次抬落该归谁处理"。
static void *g_latched_hc = NULL;

static inline int latch_enabled(void) {
    return g_latch_active || g_config.enable_latch_overtake;
}

static inline int overtake_active(void *hc) {
    return *(volatile unsigned char *) ((uintptr_t) hc + OFF_HYBRID_OVERTAKE_ACTIVE) != 0;
}

// 超车开着但电量已耗尽时，游戏自己的 FixedUpdate 管理管线会收尾
//（反汇编实证 ManageActiveAeroEra 0x1A24F08 分支 → EnableDisableOvertakeModeSwitch(false)）。
// 此时玩家的 OTK 按钮还按着、稍后才会松手；若不先清锁定态，松手会被吞掉，
// 玩家下次点按反而变成"关闭"（与体感不符）。故抬起路径额外核一次电量。
static inline int has_capacity(void *hc) {
    return *(volatile float *) ((uintptr_t) hc + OFF_HYBRID_CAPACITY) > 0.0f;
}

// odometerHandler → HybridComponent。注意按钮 stub 自身对 NULL 是抛异常语义
//（`cbz x0, <throw>`），本模块遇 NULL 一律退回"原样转发"，不改变该语义。
static inline void *hybrid_of(void *odometer) {
    if (odometer == NULL) {
        return NULL;
    }
    return *(void *volatile *) ((uintptr_t) odometer + OFF_ODOMETER_HYBRID);
}

// ══════════════════════════════════════════════════════════════════════════
// 为什么 hook 按钮方法而不是 HybridComponent.EnableOTK/DisableOTK
// ═══════════════════════════════════════════════════════════════════════════
// TouchPressOTK/TouchReleaseOTK 是 OTK 按钮的 UnityEvent 目标（datapack.unity3d
// 里 `IRDS.UI.odometerHandler, Assembly-CSharp` + 这两个方法名 + 同文件 GUID，
// 8.0.6 实测 @offset 122230882），全 .so 零 bl 指向 —— **按钮专属**。
//
// 而 HybridComponent.EnableOTK(0x1A274F4)/DisableOTK(0x1A27494) 虽然也覆盖触摸
// 路径（TouchPressOTK 尾部就是 `b EnableOTK`，0x1A0BE38），却**不是按钮专属**：
//   HybridComponent.switchHModeUp(0x1A27484) 是游戏自己的 OTK toggle
//   （`ldrb w8,[x0,#0xC4]; cbz → EnableOTK else DisableOTK`），
//   被 **odometerHandler.onHybridMapChange(0x1A0BD88)** 与
//   **odometerHandler.Update(0x1A04F20)** 调用 —— 即 ERS 混动模式切换键。
// 挂在那里，玩家按 ERS 模式键想关掉超车时会被本模块吞掉，属于弄坏游戏
// 另一条正常路径。所以 hook 点必须落在按钮专属方法上。
//
// 需要 this 上的白名单吗？**不需要**：按钮专属方法只有玩家能触发（AI 超车走
// AIHybridManager.ManageOvertake → EnableDisableOvertakeModeSwitch，不经过
// 这里）。可信度落在**调用图证据**上 —— 升版时必核：TouchPressOTK/
// TouchReleaseOTK 是否仍然只被按钮 prefab 引用（.so 内零 bl）。

// ═══════════════════════════════════════════════════════════════════════════
// Hook: odometerHandler::TouchPressOTK()   ← OTK 按钮按下
// Hook: odometerHandler::TouchReleaseOTK() ← OTK 按钮抬起
// ═══════════════════════════════════════════════════════════════════════════
// 自复位 → 点按切换，**不写任何游戏状态字段**：
//   • 按下时若超车已开着 → 本次点按语义是"关"，转调游戏的 DisableOTK()
//   • 抬起时若锁定态由本模块在此实例上建立、超车仍开着且电量仍非零 → 吞掉
// 其余情况一律原样转发，故：
//   • 开启路径逐字节是游戏自己的 TouchPressOTK（内含 EnableOTK 的 ERS 解锁 /
//     电量 / 圈数全部守卫），点了没反应与原生完全一致，模块不补任何提示；
//   • 关闭路径复用游戏自己的 DisableOTK，手臂动画（rotateHands.
//     playHybridOtkAction）、HUD 更新、UpdateHybridRNCs 联动照常发生。
static void proxy_touch_press_otk(void *this, void *method_info) {
    typedef void (*t)(void *, void *);

    if (latch_enabled()) {
        void *hc = hybrid_of(this);
        if (hc != NULL && overtake_active(hc)) {
            // 已开着 → 本次点按语义是"关"。转调游戏自己的 DisableOTK 收尾。
            g_latched_hc = NULL;
            ((t) g_disable_otk_addr)(hc, method_info);
            LOGI("Latch overtake: tap -> released");
            return;
        }
        ((t) g_press_orig)(this, method_info);
        // 以游戏状态为准：ERS 未解锁 / 电量不足时游戏会拒绝这次开启
        //（EnableOTK 内 4 条守卫原样保留），此时不进锁定态。
        if (hc != NULL && overtake_active(hc)) {
            g_latched_hc = hc;
            LOGI("Latch overtake: tap -> locked (tap-to-toggle)");
        } else {
            g_latched_hc = NULL;
        }
        return;
    }

    ((t) g_press_orig)(this, method_info);
}

static void proxy_touch_release_otk(void *this, void *method_info) {
    typedef void (*t)(void *, void *);

    // 吞掉"抬起"这一半 —— 锁定态一直保持到玩家再点一次。
    // 电量耗尽导致游戏自行收尾时（OvertakeActive 已被清、或电量归零），
    // 不再吞：抬起如实传达，玩家体感与游戏状态保持一致。
    if (latch_enabled() && g_latched_hc != NULL) {
        void *hc = hybrid_of(this);
        if (hc != NULL && hc == g_latched_hc
            && overtake_active(hc) && has_capacity(hc)) {
            return;
        }
    }
    g_latched_hc = NULL;
    ((t) g_release_orig)(this, method_info);
}

// ═══════════════════════════════════════════════════════════════════════════
// Install / uninstall
// ══════════════════════════════════════════════════════════════════════════
// ️ 与自动 DRS 同策略：hook 恒装上，不因开关为假而跳过。
//   • 这两个方法只在玩家点按 OTK 按钮时触发（每局次数极少，非高频方法），
//     开关在回调内判即可；关掉功能时行为与原版逐字节一致（原样调 orig）。
//   • 若改按开关装/卸 hook，会引入"关掉功能那一刻玩家正按着 OTK"的边缘态，
//     且重装时机与赛道加载耦合，不值得。恒装上更简单、也更好诊断。
bool overtake_install_hooks(const overtake_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_latch_active = g_config.enable_latch_overtake ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.touch_press_otk_offset == 0 || g_config.touch_release_otk_offset == 0
        || g_config.disable_otk_offset == 0) {
        LOGE("Overtake hook skipped: press=0x%" PRIxPTR " release=0x%" PRIxPTR " disable=0x%" PRIxPTR,
             (uintptr_t) g_config.touch_press_otk_offset,
             (uintptr_t) g_config.touch_release_otk_offset,
             (uintptr_t) g_config.disable_otk_offset);
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for overtake hook");
        return false;
    }

    g_disable_otk_addr = (void *) (base + g_config.disable_otk_offset);

    uintptr_t press_target = base + g_config.touch_press_otk_offset;
    g_press_stub = shadowhook_hook_sym_addr(
            (void *) press_target,
            (void *) proxy_touch_press_otk,
            (void **) &g_press_orig);
    if (g_press_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(TouchPressOTK) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        return false;
    }

    uintptr_t release_target = base + g_config.touch_release_otk_offset;
    g_release_stub = shadowhook_hook_sym_addr(
            (void *) release_target,
            (void *) proxy_touch_release_otk,
            (void **) &g_release_orig);
    if (g_release_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(TouchReleaseOTK) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        // 按下 side 已装上：卸掉它，避免"半装"状态（点按进入锁定态却永远
        // 收不到抬起 → 无法用点按关闭）。
        shadowhook_unhook(g_press_stub);
        g_press_stub = NULL;
        g_press_orig = NULL;
        return false;
    }

    LOGI("Hooked TouchPressOTK at 0x%" PRIxPTR " / TouchReleaseOTK at 0x%" PRIxPTR
         " (DisableOTK at 0x%" PRIxPTR ", latch_overtake=%d)",
         press_target, release_target, (uintptr_t) g_disable_otk_addr, g_latch_active);

    g_hooks_installed = 1;
    return true;
}

void overtake_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_press_stub != NULL) {
        shadowhook_unhook(g_press_stub);
        g_press_stub = NULL;
        g_press_orig = NULL;
    }
    if (g_release_stub != NULL) {
        shadowhook_unhook(g_release_stub);
        g_release_stub = NULL;
        g_release_orig = NULL;
    }
    g_disable_otk_addr = NULL;
    g_latch_active = 0;
    g_latched_hc = NULL;
    g_hooks_installed = 0;
    g_config.enable_latch_overtake = false;
}

void overtake_set_active(int active) {
    g_latch_active = active ? 1 : 0;
    // 关掉功能时清锁定态：否则玩家此刻正按着 OTK 的话，抬起会被继续吞掉
    //（功能已关却仍锁定，无法用点按关闭）——抬起必须还原成"真的抬起"。
    if (!g_latch_active) {
        g_latched_hc = NULL;
    }
}