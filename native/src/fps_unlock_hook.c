// ═══════════════════════════════════════════════════════════════════════════
// fps_unlock_hook.c — 强制全局 120 FPS（enableForce120Fps）
//
// 完整设计说明、hook 点性质（ICall thunk）、五个调用点、以及为什么改「入参」
// 而不是改「字段」，全部写在 fps_unlock_hook.h 的头注释里 —— 改本文件前先读它。
//
// 一句话：hook Application.set_targetFrameRate(int)，开关开启时把入参
// 30 / 60 改写为 120，其它值原样透传。不碰任何游戏状态字段。
// ═══════════════════════════════════════════════════════════════════════════
#include "fps_unlock_hook.h"
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

static fps_unlock_hook_config_t g_config = {0};

// ── libil2cpp.so 基址解析 ──
// 项目惯例是每个 hook 文件各带一份 static 实现（intro/music/hide_pedals/
// dlc_guard 均如此），不抽公共头——保持单文件自足、避免跨文件符号耦合。
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

// ── 运行时开关：单一事实源 ──
// install 期从 config 取初值写进 g_force_120_active，此后运行时回调**只判它**，
// g_config.enable_force_120_fps 只作初值来源、运行时永不读。
// ⚠️ 绝不要写成 `g_force_120_active || g_config.enable_force_120_fps` ——
//    config 在 install 后固化、运行时没有写点，`||` 会让功能永远关不掉
//    （本项目已在 steer_limit / drs / overtake / next_lap 四处踩过并修复，
//    见 memory `runtime-switch-single-source-of-truth`）。
static volatile int g_force_120_active = 0;

static inline int force_120_enabled(void) {
    return g_force_120_active;
}

static void *g_set_fps_stub = NULL;
static void *g_set_fps_orig = NULL;
static int g_hooks_installed = 0;

// 游戏的三档硬编码立即数（odometerHandler.setGameAndGraphicsVariables）：
//   _graphicsFramerate == 0 → mov w0, #0x1e (30)
//   _graphicsFramerate == 1 → mov w0, #0x3c (60)
//   _graphicsFramerate == 2 → mov w0, #0x78 (120)
// 移动端 UI 只给前两档，故值域实际只有 30 / 60。
#define FPS_SRC_LOW  30
#define FPS_SRC_HIGH 60
#define FPS_TARGET   120

// UnityEngine.Application::set_targetFrameRate(int value)
//   ICall thunk @ RVA 0x3257B6C —— 这是 thunk 不是托管方法：函数体尾部
//   `br x1` 尾跳 il2cpp_resolve_icall 解析出的 Unity 原生实现。
//   ABI: w0 = value, x1 = method_info（静态方法，无 this）
//   ⚠️ x1 恒为 NULL（5 个调用点全部 `mov x1, xzr`），且函数体内 0x325b78
//      会立刻把它覆盖成 ICall 缓存指针 ⇒ proxy 不得读它，原样透传。
static void proxy_set_target_frame_rate(int value, void *method_info) {
    typedef void (*t)(int, void *);

    int out = value;
    if (force_120_enabled() && (value == FPS_SRC_LOW || value == FPS_SRC_HIGH)) {
        out = FPS_TARGET;
    }

    // 只在真正改写时打日志 —— **可观测性刚需**：
    // 「开关没生效」与「生效了但设备跑不满 120」必须能从日志区分，
    // 否则本机测不出差别时无法归因（见 memory
    // `device-dependent-change-needs-fingerprint-log`）。
    // 调用频率极低（每次进赛道一次 + 设置页确认一次），
    // 不违反「透传 hook 保持静默」红线。
    if (out != value) {
        LOGI("fps_unlock: targetFrameRate %d -> %d", value, out);
    }

    ((t) g_set_fps_orig)(out, method_info);
}

// 补设一次目标帧率（走 hook 后的入口，因而也经过 proxy 的开关判定）。
// 定义在 set_active 附近，此处前置声明供 install 使用。
static void apply_target_fps(void);

bool fps_unlock_install_hooks(const fps_unlock_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_force_120_active = g_config.enable_force_120_fps ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.set_target_frame_rate_offset == 0) {
        LOGE("FPS unlock hook skipped: set_target_frame_rate_offset=0");
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for FPS unlock hook");
        return false;
    }

    uintptr_t target = base + g_config.set_target_frame_rate_offset;
    g_set_fps_stub = shadowhook_hook_sym_addr(
            (void *) target,
            (void *) proxy_set_target_frame_rate,
            (void **) &g_set_fps_orig);
    if (g_set_fps_stub == NULL) {
        // ⚠️ 本函数体只有 56 字节（0x325b6c–0x325ba4），且前 4 条指令里有一条
        //    `adrp x20, #0x390c000`（PC 相对寻址）。若 ShadowHook 需要在入口
        //    写入 16 字节绝对跳转，就会覆盖这条 adrp，必须做重定位。
        //    现代 ShadowHook 有 ARM64 PC-relative 重定位逻辑，但**这是本 hook
        //    唯一的安装失败风险点**。失败码从这里取，先查重定位相关错误。
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(set_targetFrameRate) failed: %d (%s)"
             " [target=0x%" PRIxPTR ", fn size ~56B, adrp@+0x8 may need reloc]",
             err, shadowhook_to_errmsg(err), target);
        return false;
    }

    crash_hook_register("set_targetFrameRate", (void *) target);
    LOGI("Hooked UnityEngine.Application.set_targetFrameRate at 0x%" PRIxPTR
         " (force_120=%d target=%d)", target, g_force_120_active, FPS_TARGET);

    g_hooks_installed = 1;

    // ── 安装后补设一次（修复「刚进主界面锁 30」）──
    //
    // set_targetFrameRate 的实际调用点只有一个：odometerHandler.Start()
    // → setGameAndGraphicsVariables()。**主菜单场景没有 odometerHandler**，
    // 所以：
    //   • 首次进主界面：游戏在 hook 装上之前已经设过一次 30 ⇒ 漏掉；
    //   • 退出赛道回菜单：odometerHandler 随赛道场景销毁，没人重置 ⇒ 保持 120。
    // 实机现象正是「刚启动锁 30，玩过再回主菜单才是 120」（用户实测，
    // 且**原生设 60 时同样如此** ⇒ 本来就是游戏行为，不是本 hook 引入的）。
    //
    // 修法 = 安装完成后主动补调一次，把已错的 30 纠正过来。
    // 只在开关开启时补设 —— 关掉时逐字节等于原版。
    if (g_force_120_active) {
        apply_target_fps();
    }

    return true;
}

void fps_unlock_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_set_fps_stub != NULL) {
        shadowhook_unhook(g_set_fps_stub);
        g_set_fps_stub = NULL;
        g_set_fps_orig = NULL;
    }
    g_force_120_active = 0;
    g_hooks_installed = 0;
}

// 补设一次目标帧率（走 hook 后的入口，因而也经过 proxy 的开关判定）。
// 调用前提：g_hooks_installed 且 g_config.set_target_frame_rate_offset 有效。
// 传 NULL 作 method_info 安全 —— 反汇编实证 0x3257b78 的
// `ldr x1, [x20, #0xba0]` 会立刻用 ICall 缓存指针覆盖 x1。
static void apply_target_fps(void) {
    if (!g_hooks_installed || g_config.set_target_frame_rate_offset == 0) {
        return;
    }
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        return;
    }
    typedef void (*set_fps_t)(int, void *);
    ((set_fps_t)(base + g_config.set_target_frame_rate_offset))(FPS_TARGET, NULL);
    LOGI("fps_unlock: applied targetFrameRate(%d)", FPS_TARGET);
}

void fps_unlock_set_active(int active) {
    int was = g_force_120_active;
    g_force_120_active = (active != 0) ? 1 : 0;
    LOGI("fps_unlock set_active %d", g_force_120_active);

    // 运行中**打开**开关时立即补设一次：此刻游戏已经按 30/60 在跑，
    // 而 set_targetFrameRate 的唯一调用点是进赛道（odometerHandler.Start），
    // 不补设就得等下一次进赛道才生效。关掉时不需要补设 —— 游戏会在下一次
    // 进赛道时按用户自己的档位重新设置。
    if (g_force_120_active && !was) {
        apply_target_fps();
    }
}
