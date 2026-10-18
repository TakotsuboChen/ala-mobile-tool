// ═══════════════════════════════════════════════════════════════════════════
// steer_limit_hook.c — 解除最大转向角度限制（enableUnlockSteerAngle）
//
// 游戏按车速把每只轮子的最大转向角往下压，车速越高能打的角度越小。本功能
// 解除这个随车速的收紧，让每只轮子始终保有静态基础最大转向角。
//
// ── 反汇编实证（8.0.6 / 200150，build/v8.0.6-official/v8.0.6-coex-dec）──
//
// IRDSWheel 上两个成对字段：
//   maxSteeringAngle    0x90  —— 静态基础值。只在 Awake(0x1A7D07C) 与
//                                IRDSWishboneSuspension.Init(0x1A7D4CC) 从
//                                prefab 读取，运行期不再被改写。
//   maxSteeringAngleOld 0x94  —— 运行期生效值。Awake 时由 0x90 初始化
//                                (0x1A7D07C: ldr s0,[x19,#0x90]; str s0,[x19,#0x94])，
//                                此后由 SetMaxSteeringAngle 覆写。
//
// IRDSWheel.SetMaxSteeringAngle(float) @ RVA 0x1A7B49C
//   x0=this, s0=Value, x1=method_info
//   方法体尾部：
//     0x1A7B4EC  bl  Application.get_isPlaying()
//     0x1A7B4F0  tbz w0, #0, 0x1A7B4FC
//     0x1A7B4F4  str s8, [x19, #0x94]      ; isPlaying → 写运行期生效值
//     0x1A7B4FC  str s8, [x19, #0x90]      ; 否则     → 写静态基础值
//   ⇒ 游戏运行时改的是 **0x94**。
//
// 限角真正生效的位置：IRDSWheel.RoadForce(0x1A7DB3C) 的 0x1A7E020 处
//   ldr s1, [x19, #0x94]      ; maxSteeringAngleOld
//   ldr s2, [x19, #0xf4]      ; wheel.steering
//   fcmp s1, #0.0
//   b.ne 0x1A7E454
//   ...
//   0x1A7E454: ldr s0, [x19, #0x7c]; fdiv s0, s0, s1   ; 转角 / 最大转角
// ⇒ 0x94 就是分母，压低它 = 同样的输入得到更大的相对转角 = 物理上更"能转"。
//
// ── 为什么 hook SetMaxSteeringAngle 而不是 TiltSteerDynamicAssist ──
//
// carModifier.TiltSteerDynamicAssist(0x1769274) 确实也在按车速算角度
//   (angle = 21° − 12° × clamp((v−27)/56, 0, 1))，
// 但它被 carModifier.isTiltSteer(0x1C4) 门控，而该标志在 carModifier.Start
// 里由 PlayerPrefs 的 "isTilt" 键决定（HasKey → GetInt）——**判定是反的**：
// 没有该键或键值为 0 时才置 true ⇒ 它实际表示「未启用倾斜转向」，
// 该方法跑在多数（触摸/方向键）玩家身上。指令序列见 steer_limit_hook.h。
// SetMaxSteeringAngle 则是所有限角路径的**唯一落地口**（TiltSteer 那条也在
// 内），堵住它即覆盖全部。
//
// ── 调用图证据（升版必核）──
//
// SetMaxSteeringAngle 全 .so 三个 `bl` 调用者（8.0.6 实测）：
//   1. IRDSMotorCycle.Update(0x174C294)                    —— 摩托车玩法（本作无）
//   2. carModifier.Start(0x17635FC +0x1030)                —— 开局重置为 21.0f
//   3. carModifier.TiltSteerDynamicAssist(0x1769274 +0x18C) —— 倾斜转向限角
// ⚠️ 升版时必核：① 调用者集合是否仍是这三个（find_callers.py 0x1A7B49C）；
//   ② SetMaxSteeringAngle 内是否仍是 isPlaying 二选一写 0x90/0x94；
//   ③ IRDSWheel 的 0x90/0x94 两个字段偏移是否未变。
//
// ── 为什么不需要玩家车白名单 ──
//
// 本功能只放宽物理上限，不改变任何决策输入。AI 的转向由
// IRDSCarControllerAI 给出的 inputSteer 决定，maxSteeringAngleOld 只是把该
// 输入映射成轮胎侧偏角时的上限——AI 本就不会要求超过基础转角的角度，解限
// 对其行为无影响。同时避免"玩家超车时 AI 突然被限角"的不一致。
// 附带好处：hook 不依赖 g_player_controller（白名单只在
// proxy_player_controls_update 里写入），安装时机不受其就绪状态影响。
//
// ── 红线自查 ──
// 函数级 hook（shadowhook_hook_sym_addr），proxy 是普通 C 函数调用，可自由
// 使用浮点——与「指令级拦截回调必须 float-free」是两回事。只读一个字段、
// 写一个字段，不调用任何游戏方法（避免 IL2CPP 线程附加/GC 问题）。
// hook 恒装上（开关在回调内判），关掉时逐字节等于原版。
// ═══════════════════════════════════════════════════════════════════════════
#include "steer_limit_hook.h"
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

static steer_limit_hook_config_t g_config = {0};

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

static void *g_set_angle_stub = NULL;
static void *g_set_angle_orig = NULL;
static volatile int g_hooks_installed = 0;

// 运行时开关（Java 配置同步）。与 g_config.enable_unlock_steer_angle 二选一
// 为真即启用（同 next_lap_hook.c / overtake_hook.c 的双判据惯例）。
static volatile int g_unlock_active = 0;

static inline int unlock_enabled(void) {
    return g_unlock_active || g_config.enable_unlock_steer_angle;
}

// 直接按偏移读写 float 字段。不用 IL2CPP 的 Field 访问器：那需要
// il2cpp_runtime_invoke / 线程附加，在物理帧回调里代价过高且易踩 GC。
static inline float field_get_f32(void *base, uint32_t offset) {
    return *(volatile float *) ((uint8_t *) base + offset);
}

static inline void field_set_f32(void *base, uint32_t offset, float value) {
    *(volatile float *) ((uint8_t *) base + offset) = value;
}

// IRDSWheel::SetMaxSteeringAngle(float Value)
//   x0=this, s0=Value, x1=method_info
static void proxy_set_max_steering_angle(void *this, float value, void *method_info) {
    typedef void (*t)(void *, float, void *);

    // 开关开启时**丢弃入参**，把静态基础最大转向角写进运行期生效值。
    // 效果 = 这只轮子永远保有 prefab 里的原始转角，不再随车速收紧。
    //
    // ⚠️ 只在基础值有效（> 0）时替换：0x90 在 Awake 之前是 0，若此时
    //    SetMaxSteeringAngle 被调用（carModifier.Start 就是 21.0f），把 0
    //    写进 0x94 会让分母为 0（RoadForce 里 fdiv 0/0 = NaN），轮胎物理
    //    直接崩掉。基础值为 0 时一律透传原值。
    if (unlock_enabled() && this != NULL) {
        float base = field_get_f32(this, g_config.wheel_max_steering_angle_field);
        if (base > 0.0f) {
            // 日志只在值真的被改写时打（低频：游戏只在开局/TiltSteer 每帧
            // 驱动时调用，且 TiltSteer 只对开启倾斜转向的玩家生效）。
            // 打日志是**可观测性刚需**：本 hook 改变游戏原生判定，必须能
            // 从日志确认它真的拦下了限角（原始 value < base 就是铁证）。
            if (value < base) {
                LOGI("unlock_steer_angle: SetMaxSteeringAngle(%.3f) → base %.3f "
                     "(speed-based limit bypassed)", value, base);
            }
            field_set_f32(this, g_config.wheel_max_steering_angle_old_field, base);
            return;
        }
    }
    ((t) g_set_angle_orig)(this, value, method_info);
}

bool steer_limit_install_hooks(const steer_limit_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }
    g_unlock_active = g_config.enable_unlock_steer_angle ? 1 : 0;

    if (g_hooks_installed) {
        return true;
    }

    if (g_config.set_max_steering_angle_offset == 0) {
        LOGE("Steer-limit hook skipped: set_max_steering_angle_offset=0");
        return false;
    }
    if (g_config.wheel_max_steering_angle_field == 0 ||
        g_config.wheel_max_steering_angle_old_field == 0) {
        LOGE("Steer-limit hook skipped: wheel field offsets unset (0x%" PRIx32 "/0x%" PRIx32 ")",
             g_config.wheel_max_steering_angle_field,
             g_config.wheel_max_steering_angle_old_field);
        return false;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address for steer-limit hook");
        return false;
    }

    uintptr_t target = base + g_config.set_max_steering_angle_offset;
    g_set_angle_stub = shadowhook_hook_sym_addr(
            (void *) target,
            (void *) proxy_set_max_steering_angle,
            (void **) &g_set_angle_orig);
    if (g_set_angle_stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_sym_addr(SetMaxSteeringAngle) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        return false;
    }

    crash_hook_register("SetMaxSteeringAngle", (void *) target);
    LOGI("Hooked IRDSWheel.SetMaxSteeringAngle at 0x%" PRIxPTR
         " (unlock_steer_angle=%d, base_field=0x%" PRIx32 ", old_field=0x%" PRIx32 ")",
         target, g_unlock_active,
         g_config.wheel_max_steering_angle_field,
         g_config.wheel_max_steering_angle_old_field);

    g_hooks_installed = 1;
    return true;
}

void steer_limit_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }
    if (g_set_angle_stub != NULL) {
        shadowhook_unhook(g_set_angle_stub);
        g_set_angle_stub = NULL;
        g_set_angle_orig = NULL;
    }
    g_unlock_active = 0;
    g_hooks_installed = 0;
    g_config.enable_unlock_steer_angle = false;
}

void steer_limit_set_active(int active) {
    g_unlock_active = active ? 1 : 0;
}
