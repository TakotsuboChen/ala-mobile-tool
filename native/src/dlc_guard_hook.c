// ═══════════════════════════════════════════════════════════════════════════
// dlc_guard_hook.c — 修复 DLC 更新检查超时卡加载（enableFixDlcStuck）
//
// ── 症状 ──
// 游戏启动卡在 "Synchronizing additional content..." 进度条 10%，主场景
//（约 450MB）永不加载。实机日志特征：进程活着、无 ANR、无崩溃，rss 停在
// ~470MB（成功启动时 ~930MB），UnityMain 线程 CPU < 1%（在等，不是在算）。
//
// ── 完整因果链（8.0.6 / 200150 反汇编实证）──
//
//   IntroLogoManager.<LoadSequence>d__14.MoveNext()  @ RVA 0x1A61834
//     0x1a61ad0: bl 0x171b758      ; CarsDLCManager.CheckAndUpdateInstalledDLCs()
//     0x1a61ae0: bl 0x32a1850      ; StartCoroutine(...)
//     0x1a61af8: ldrb w9, [x8,#0x39]   ; ← 读 CarsDLCManager.IsSynchronizing
//     0x1a61afc: cbz  w9, #0x1a61c34   ; ★ false 才放行
//   ⇒ 只要 IsSynchronizing 还是 true，本协程每帧 yield、主场景永不加载。
//
//   CarsDLCManager.<CheckAndUpdateInstalledDLCs>d__28.MoveNext() @ RVA 0x171B8C8
//     0x171ba68: sturh w8, [x21,#-3]   ; IsSynchronizing=1, IsDownloading=1
//     0x171ba74: bl 0x171b814          ; IsActiveAeroInstalled()
//     0x171ba78: tbz w0,#0,#0x171c034  ; 未装 DLC → 直接完成（不走网络）
//     0x171ba7c: mov w8,#0xcccd/movk 0x3dcc  ; SynchronizationProgress = 0.1f ★截图位置
//     0x171baac: bl 0x310e6a8          ; Addressables.CheckForCatalogUpdates()
//   ⇒ 该 await 用的 AsyncOperationHandle 没有超时。CDN 不响应（TCP 连上但
//     数据不来）就永久挂起；进度永远停在 0.1，IsSynchronizing 永远为 true。
//
//   ⚠️ 关键区分：**网络"失败"是容错的，"悬着"才是死局**。
//     0x171bef4 的失败分支只调 Debug.LogWarning 然后继续走 0.7f 分支，
//     不 return、不 throw。⇒ 关掉网络反而能进游戏（立即失败→跳过）；
//     网络差但没断（弱信号/丢包/DNS 慢）才会卡死。这与玩家社群反馈一致。
//
// ── 为什么 hook LoadSequence.MoveNext 而不是别的点 ──
// 这个点同时拿得到三样东西，是唯一能做到"只在真卡住时干预"的位置：
//   ① 协程 `this`（状态机对象）—— 每帧被 resume，天然是个时钟；
//   ② `this+0x28` = `<dlcManager>5__2`（CarsDLCManager 实例指针）；
//   ③ 通过 ② 读到 IsSynchronizing(0x39) 与 SynchronizationProgress(0x3C)。
//
// 已否决的候选：
//   • hook CarsDLCManager.IsActiveAeroInstalled(0x171B814) 返回 false ——
//     能让游戏走 0x171c034 的跳过分支，但那是**无条件跳过**，无法区分
//     "真卡住"与"网络只是慢"。用户明确选择超时兜底而非默认跳过。
//   • hook IsSynchronizing 的 getter(0x171B484) —— **无效**。
//     LoadSequence 读的是裸字段 `ldrb [x8,#0x39]`，绕不过 getter。
//   • 改 PlayerPrefs 的 "dlc_activeaero_era_installed" —— 破坏性，
//     下次启动游戏会以为 DLC 没装、可能要求重下。
//   • 直接写状态机 `<>1__state` 终结协程 —— 会跳过协程自己的 Dispose 路径，
//     AsyncOperationHandle 回收时机变得不确定，且有调度器竞态风险。
//     ⇒ 只清 IsSynchronizing，让协程自己跑完。
//
// ── 为什么"只清 IsSynchronizing"是安全的 ──
// 反汇编核对 CheckAndUpdateInstalledDLCs 的全部 7 个 state：
//   state 0 置 IsSynchronizing=1（0x171ba68）；
//   state 1 只写 state 与 progress=0.25（0x171bb20），**不重设 IsSynchronizing**；
//   state 2~6 均不写 0x39；唯一的另一处写点是结尾清零（0x171c178）。
// ⇒ 清掉之后游戏自己不会再置回 1，不存在"二次卡住"。
//
// 清标志后 LoadSequence 走 0x1a61c34 分支：
//   0x1a61c68 → 0x1a61c88  更新 UI（SetActive / 清 slider）
//   0x1a61c8c → 0x1a61ca4  progress = 0
//   0x1a61cc4 → 0x1a61cf4  DLCDownloadChecker.SetActive(false)
//   0x1a61d04 → 0x1a61d14  SceneManager.LoadSceneAsync(nextSceneName)
// ⇒ 主场景正常加载。这就是解卡点。
//
// ── 判据为什么是 progress < 0.3 ──
// 0.1f = 纯目录检查（可能悬死）；0.25f 起是 UpdateCatalogs / 真下载。
// 真下载慢是**正常**的（几百 MB），绝不能干预。0.3 作为分界：
// 覆盖 0.1 与 0.0（首次进入尚未赋值），排除 0.25 之后的全部下载阶段。
//
// ── 副作用（用户已知悉并接受）──
// 超时后本次启动跳过 Addressables 目录更新检查。若远端确有新 catalog，
// 本次不会应用，下次启动再试。对已装好 DLC 的用户无感知影响。
// 玩家可随时在配置页关掉本开关。
//
// ── 红线自查 ──
// 函数级 hook（shadowhook_hook_sym_addr），proxy 是普通 C 函数调用，可自由用
// 浮点。只在 LoadSequence 的协程 tick（Unity 主线程）里读写字段，与游戏自身
// 写这些字段的是同一线程 ⇒ 无需加锁。hook 恒装上（开关在回调内判），
// 关掉时逐字节等于原版。
// ═══════════════════════════════════════════════════════════════════════════
#include "dlc_guard_hook.h"
#include "native_log.h"
#include "crash_hook.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <link.h>
#include <string.h>
#include <time.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

// ── 卡死判定参数 ───────────────────────────────────────────────────────────
//
// 超时阈值：用户定案 2026-10-25 = 3 秒。
// ⚠️ 这个值偏激进：正常网络下目录 hash 只有几 KB，几百毫秒就该回来；
//    但弱网/切网瞬间（电梯、地铁出隧道）可能合法地慢到 2~5 秒，会被误判成
//    卡死并跳过本次目录检查。代价可接受（下次启动重试），用户已知悉。
#define DLC_STUCK_TIMEOUT_MS   3000

// progress 分界：低于此值视为"仍在目录检查阶段"（0.1f = 检查，0.25f 起 = 下载）。
#define DLC_PROGRESS_CHECK_MAX 0.3f

// ── IL2CPP 字段偏移（8.0.6 / 200150，来自 dump.cs）──────────────────────
// IntroLogoManager.<LoadSequence>d__14
#define OFF_SM_STATE        0x10   // int  <>1__state
#define OFF_SM_DLC_MANAGER  0x28   // CarsDLCManager* <dlcManager>5__2
// CarsDLCManager
#define OFF_MGR_IS_SYNC     0x39   // bool  <IsSynchronizing>k__BackingField
#define OFF_MGR_PROGRESS    0x3C   // float <SynchronizationProgress>k__BackingField

// LoadSequence 里"等待 DLC 同步"的 state。state 4 是启动 CheckAndUpdate
// 的那一帧，state 5 是随后的每帧 yield。两者都要覆盖（进 4 的那一帧
// 就已经把 IsSynchronizing 置 1 了）。
#define SM_STATE_WAIT_DLC_MIN 4
#define SM_STATE_WAIT_DLC_MAX 5

static dlc_guard_hook_config_t g_config = {0};

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

static void *g_move_next_stub = NULL;
static void *g_move_next_orig = NULL;
static volatile int g_hooks_installed = 0;

// 运行时开关（Java 配置同步）。**这是唯一权威**：g_config.enable_fix_dlc_stuck
// 只在 install 时提供初值，此后运行时永不读取。
//
// ⚠️ 不要写成 `g_fix_active || g_config.enable_fix_dlc_stuck` 双判据——
//    本功能**默认开**，用户从「开」改「关」时 install 已把 g_config 固化为
//    true，运行时 setter 只清 g_fix_active ⇒ `0 || true` 恒真 ⇒ 关掉开关不
//    生效，必须重启游戏。同一形态曾在 4 个 hook 文件里逐字复制，已一并修复
//    （见 memory `runtime-switch-single-source-of-truth`）。**不要恢复 `||`**。
static volatile int g_fix_active = 0;

// 卡死计时起点（CLOCK_MONOTONIC 毫秒）。0 = 尚未开始计时。
// 只在 MoveNext 协程 tick（Unity 主线程）里读写 ⇒ 无需加锁。
static int64_t g_stuck_since_ms = 0;

static int64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// IntroLogoManager.<LoadSequence>d__14.MoveNext()
//   返回 bool（true = 协程还有下一步）
static bool proxy_move_next(void *this_sm) {
    typedef bool (*t)(void *);
    bool result = ((t) g_move_next_orig)(this_sm);

    // 协程已结束：清计时器，不再判定。
    if (!result) {
        g_stuck_since_ms = 0;
        return false;
    }

    if (!g_fix_active) {
        g_stuck_since_ms = 0;
        return true;
    }

    if (this_sm == NULL) {
        return true;
    }

    // 只在"等待 DLC 同步"的两个 state 里判定，其他 state（等 loadOp、
    // 等开场视频等）的耗时与 DLC 无关，不参与计时。
    int state = *(int *) ((uint8_t *) this_sm + OFF_SM_STATE);
    if (state < SM_STATE_WAIT_DLC_MIN || state > SM_STATE_WAIT_DLC_MAX) {
        g_stuck_since_ms = 0;
        return true;
    }

    void *mgr = *(void **) ((uint8_t *) this_sm + OFF_SM_DLC_MANAGER);
    if (mgr == NULL) {
        g_stuck_since_ms = 0;
        return true;
    }

    // 未在同步（正常完成，或从未开始）→ 不是在卡。
    bool is_sync = *(volatile bool *) ((uint8_t *) mgr + OFF_MGR_IS_SYNC);
    if (!is_sync) {
        g_stuck_since_ms = 0;
        return true;
    }

    // 已进入真实下载阶段（0.25f 起）→ 慢是正常的，绝不干预。
    float progress = *(volatile float *) ((uint8_t *) mgr + OFF_MGR_PROGRESS);
    if (progress >= DLC_PROGRESS_CHECK_MAX) {
        g_stuck_since_ms = 0;
        return true;
    }

    // ── 停在目录检查阶段，开始/继续计时 ──
    int64_t now = monotonic_ms();
    if (g_stuck_since_ms == 0) {
        g_stuck_since_ms = now;
        return true;
    }
    int64_t elapsed = now - g_stuck_since_ms;
    if (elapsed < DLC_STUCK_TIMEOUT_MS) {
        return true;
    }

    // ── 超时：清 IsSynchronizing，让游戏自己的循环退出并加载主场景 ──
    // 反汇编已核实该字段清掉后协程不会自己置回 1（见文件头"为什么只清
    // IsSynchronizing 是安全的"）。
    // ⚠️ 此处打日志是**可观测性刚需**：本 hook 改变游戏启动流程，必须能从
    //    日志确认它真的拦下了卡死（elapsed + progress 就是铁证）。
    //    频率：每次启动至多一条，不构成刷屏。
    *(volatile bool *) ((uint8_t *) mgr + OFF_MGR_IS_SYNC) = false;
    LOGI("dlc_guard: stuck in catalog check for %" PRId64 "ms "
         "(progress=%.3f state=%d) → cleared IsSynchronizing, main scene will load",
         elapsed, progress, state);
    g_stuck_since_ms = 0;
    return true;
}

bool dlc_guard_install_hooks(const dlc_guard_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_fix_active = g_config.enable_fix_dlc_stuck ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.intro_load_sequence_move_next_offset == 0) {
        LOGE("DLC guard hook skipped: intro_load_sequence_move_next_offset=0");
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for DLC guard hook");
        return false;
    }

    uintptr_t target = base + g_config.intro_load_sequence_move_next_offset;
    g_move_next_stub = shadowhook_hook_sym_addr(
            (void *) target,
            (void *) proxy_move_next,
            (void **) &g_move_next_orig);
    if (g_move_next_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(LoadSequence.MoveNext) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        return false;
    }

    crash_hook_register("IntroLogoManager.LoadSequence.MoveNext", (void *) target);
    // 指纹日志：本功能的效果依赖网络状况，本机测不出差别是预期内的。
    // 把常量打出来，远端日志才能区分"没生效"与"生效但没触发"。
    LOGI("Hooked IntroLogoManager.<LoadSequence>d__14.MoveNext at 0x%" PRIxPTR
         " (fix_dlc_stuck=%d timeout=%dms progress_max=%.2f)",
         target, g_fix_active, DLC_STUCK_TIMEOUT_MS, DLC_PROGRESS_CHECK_MAX);

    g_hooks_installed = 1;
    return true;
}

void dlc_guard_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_move_next_stub != NULL) {
        shadowhook_unhook(g_move_next_stub);
        g_move_next_stub = NULL;
        g_move_next_orig = NULL;
    }
    g_fix_active = 0;
    g_stuck_since_ms = 0;
    g_hooks_installed = 0;
    g_config.enable_fix_dlc_stuck = false;
}

void dlc_guard_set_active(int active) {
    g_fix_active = active ? 1 : 0;
    if (!active) {
        g_stuck_since_ms = 0;
    }
}
