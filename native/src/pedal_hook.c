#include "pedal_hook.h"
#include "hide_pedals_hook.h"
#include "native_log.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

static pedal_hook_config_t g_config = {0};

static volatile float g_throttle_value = 0.0f;
static volatile float g_brake_value = 0.0f;

static volatile int g_throttle_active = 0;
static volatile int g_brake_active = 0;
static volatile int g_disable_auto_gear = 0;

// ★ 玩家车 controller 的单一可靠来源。
// 只由 proxy_player_controls_update 设置（IRDSPlayerControls 只挂玩家车 GameObject）。
// 其他所有 hook（proxy_set_throttle/proxy_set_brake/proxy_fixed_update）都跑在
// 所有车的实例上，不再设置此变量，避免 AI 车误判污染。
static volatile void *g_player_controller = NULL;

// g_last_controller 保留为后向兼容（pedal_shift_up/pedal_shift_down/pedal_set_throttle_value
// 等 JNI 入口仍用它），但不作为 writer 线程的输入源。
// writer 线程写 g_player_controller。
static volatile void *g_last_controller = NULL;
static volatile void *g_player_controls = NULL;  // IRDSPlayerControls 实例
static volatile uintptr_t g_player_drivetrain = 0;

static void *g_throttle_stub = NULL;
static void *g_brake_stub = NULL;
static void *g_shift_up_stub = NULL;
static void *g_shift_down_stub = NULL;
static void *g_fixed_update_stub = NULL;
static void *g_drivetrain_fixed_update_stub = NULL;
static void *g_drivetrain_do_gear_shifting_stub = NULL;
static void *g_player_controls_update_stub = NULL;
static void *g_traction_filter_stub = NULL;
static void *g_handle_abs_stub = NULL;
static void *g_car_controller_stub = NULL;

static void *g_throttle_orig = NULL;
static void *g_brake_orig = NULL;
static void *g_shift_up_orig = NULL;
static void *g_shift_down_orig = NULL;
static void *g_fixed_update_orig = NULL;
static void *g_drivetrain_fixed_update_orig = NULL;
static void *g_drivetrain_do_gear_shifting_orig = NULL;
static void *g_player_controls_update_orig = NULL;
static void *g_traction_filter_orig = NULL;
static void *g_handle_abs_orig = NULL;
static void *g_car_controller_orig = NULL;

static volatile int g_hooks_installed = 0;

// Background writer thread. It periodically writes the current desired input
// values into the IL2CPP instance fields, so the car responds even if the
// game's physics loop does not call the hooked setters.
static pthread_t g_writer_thread;
static volatile int g_writer_running = 0;

static inline void write_float_field(void *instance, uintptr_t offset, float value) {
    if (instance == NULL || offset == 0) return;
    *(volatile float *) ((uintptr_t) instance + offset) = value;
}

static inline void write_bool_field(void *instance, uintptr_t offset, bool value) {
    if (instance == NULL || offset == 0) return;
    *(volatile bool *) ((uintptr_t) instance + offset) = value;
}

static inline uintptr_t read_ptr(void *instance, uintptr_t offset) {
    if (instance == NULL || offset == 0) return 0;
    return *(volatile uintptr_t *) ((uintptr_t) instance + offset);
}

// 玩家车判据：读 IRDSCarControllInput.playerControls 字段（偏移 0x108）。
// IRDSPlayerControls 组件只挂在玩家车 GameObject 上——AI 车的
// IRDSCarControllInput 实例此字段为 null。这是区分玩家车与 AI 车的
// 可靠依据，替代原先不可靠的 carPilot (0x68) 判定。
//
// 赛道上每辆车都有一个 IRDSCarControllInput 实例（玩家 + 19 辆 AI），
// proxy_set_throttle/proxy_set_brake 会被所有车的 setter 调用。只有
// 玩家车的 controller 才应被 g_last_controller 捕获，否则 writer 线程
// 会把模块输入写到 AI 车字段（多车模式失效的根因）。
static inline int is_player_controller(void *instance) {
    if (instance == NULL) return 0;
    void *player_controls = *(void **) ((uintptr_t) instance + 0x108);
    return player_controls != NULL ? 1 : 0;
}

// 把当前模块 desired 输入值写到 controller 实例字段。
// 只在对应 active 时写字段——不踩时绝不写，避免覆盖游戏自带输入
// （方向键 ButtonsSteering 模式的转向辅助依赖油门/刹车值）。
// 松开瞬间的清零由 pedal_set_throttle_value/pedal_set_brake_value
// 主动调 clear_*_field 完成，不在此处轮询清零。
static void apply_inputs_to_controller(void *controller) {
    if (controller == NULL) return;

    if (g_throttle_active) {
        write_float_field(controller, g_config.throttle_field_offset, g_throttle_value);
        if (g_config.actual_throttle_field_offset != 0) {
            write_float_field(controller, g_config.actual_throttle_field_offset, g_throttle_value);
        }
    }

    if (g_brake_active) {
        write_float_field(controller, g_config.brake_field_offset, g_brake_value);
        if (g_config.actual_brake_field_offset != 0) {
            write_float_field(controller, g_config.actual_brake_field_offset, g_brake_value);
        }
    }
}

// 用户松开踏板时主动清零一次对应字段，避免值卡在最后值。
// 只清对应的那个字段（油门或刹车），不互相干扰。
static void clear_throttle_field(void *controller) {
    if (controller == NULL) return;
    write_float_field(controller, g_config.throttle_field_offset, 0.0f);
    if (g_config.actual_throttle_field_offset != 0) {
        write_float_field(controller, g_config.actual_throttle_field_offset, 0.0f);
    }
}

static void clear_brake_field(void *controller) {
    if (controller == NULL) return;
    write_float_field(controller, g_config.brake_field_offset, 0.0f);
    if (g_config.actual_brake_field_offset != 0) {
        write_float_field(controller, g_config.actual_brake_field_offset, 0.0f);
    }
}

// (Legacy IPC file path helper removed: the file-based IPC fallback was
//  removed in favor of the JNI direct path, which is reliably available in
//  both original and coexistence builds thanks to the dual-ClassLoader
//  guard in AlaMobileModule.)

// Background writer thread. It periodically writes the current desired input
// values (set by JNI: pedal_set_throttle_value / pedal_set_brake_value) into
// the IL2CPP instance fields, so the car responds even if the game's physics
// loop does not call the hooked setters.
//
// NOTE: this thread NO LONGER reads from an IPC file. The previous file-based
// IPC path raced with RandomAccessFile.seek+write (non-atomic) and with the
// JNI direct path, causing throttle/brake values to flicker between new and
// stale values — the "pedal stutter" bug. With the dual-ClassLoader guard
// in AlaMobileModule, JNI is reliably available in both original and
// coexistence builds, so the file IPC fallback is no longer needed.
//
// 关键:用户没踩踏板时（两个 active 都为 0）整体早返回，不写任何字段。
// 原实现每 2ms 持续往 throttle/brake 字段写 0.0f，会覆盖游戏自带输入——
// ButtonsSteering（屏幕方向键）模式下的转向辅助（steerHelp、
// LockSteerAtVelocity、TractionFilter）依赖油门/刹车值决定辅助力度，
// 被持续清零后转向辅助失效，方向键表现为"不转向"。陀螺仪模式不依赖
// 油门/刹车做转向，所以不受影响。松开瞬间的清零由
// pedal_set_throttle_value/pedal_set_brake_value 在 active→inactive
// 转变时主动调 clear_throttle_field/clear_brake_field 完成，不依赖
// writer 轮询。
static void *input_writer_thread(void *arg) {
    (void) arg;

    while (g_writer_running) {
        // 用户完全没操作时不写字段——让游戏自带油门/刹车/方向键输入
        // 不被覆盖。只有任一 active 为真（用户按住模块踏板）才写。
        if (g_throttle_active || g_brake_active) {
            // 只用 g_player_controller（由 player_controls_update 设置，只挂玩家车）。
            // 不再用 g_last_controller——它可能被 AI 车 setter 调用的
            // is_player_controller 误判污染（AI 车 0x108 可能非空）。
            void *controller = (void *) g_player_controller;
            if (controller != NULL && is_player_controller(controller)) {
                apply_inputs_to_controller(controller);
            }
        }
        usleep(1000 * 2); // 2 ms -> ~500 Hz
    }

    return NULL;
}

static void start_input_writer(void) {
    if (g_writer_running) return;
    g_writer_running = 1;
    pthread_create(&g_writer_thread, NULL, input_writer_thread, NULL);
}

static void stop_input_writer(void) {
    g_writer_running = 0;
    if (g_writer_thread) {
        pthread_join(g_writer_thread, NULL);
        g_writer_thread = 0;
    }
}

static void disable_automatic_gear(void *drivetrain) {
    if (drivetrain == NULL || g_config.drivetrain_automatic_field_offset == 0) {
        return;
    }
    write_bool_field(drivetrain, g_config.drivetrain_automatic_field_offset, false);
}

static void proxy_set_throttle(void *this, float value) {
    // 只有玩家车的 controller 才捕获——AI 车（playerControls==null）的
    // setter 调用直接透传给原函数，不污染 g_last_controller。
    // 旧实现无条件 g_last_controller = this，导致多车模式下 19 辆 AI 车
    // 每帧 19 次覆盖 g_last_controller，writer 线程把模块输入写到 AI 车字段。
    int is_player = is_player_controller(this);

    if (is_player) {
        g_last_controller = this;
    }

    // 只在玩家车时吞掉游戏输入（模块踏板控制中）。
    // AI 车必须透传 orig——否则游戏 AI 写入的油门值被吞掉，
    // AI 车油门卡在用户踩下踏板那一刻的值，所有 AI 车一起加速。
    if (is_player && g_throttle_active) {
        return;
    }

    typedef void (*orig_t)(void *, float);
    if (g_throttle_orig != NULL) {
        ((orig_t) g_throttle_orig)(this, value);
    }
}

static void proxy_set_brake(void *this, float value) {
    // 只有玩家车的 controller 才捕获——同 proxy_set_throttle。
    int is_player = is_player_controller(this);
    if (is_player) {
        g_last_controller = this;
    }

    // 只在玩家车时吞掉游戏输入的刹车值——AI 车须透传 orig。
    if (is_player && g_brake_active) {
        return;
    }

    typedef void (*orig_t)(void *, float);
    if (g_brake_orig != NULL) {
        ((orig_t) g_brake_orig)(this, value);
    }
}

static void proxy_shift_up(void *this) {
    typedef void (*orig_t)(void *);
    LOGI("proxy_shift_up called");
    if (g_shift_up_orig != NULL) {
        ((orig_t) g_shift_up_orig)(this);
    }
}

static void proxy_shift_down(void *this) {
    typedef void (*orig_t)(void *);
    LOGI("proxy_shift_down called");
    if (g_shift_down_orig != NULL) {
        ((orig_t) g_shift_down_orig)(this);
    }
}

static void proxy_fixed_update(void *this) {
    // 用 playerControls 字段（0x108）判定玩家车，替代不可靠的 carPilot (0x68)。
    // carPilot 是 autopilot/pit-pilot 标志，不是"是否玩家"的判据。
    int is_player = is_player_controller(this);

    if (is_player) {
        g_last_controller = this;
        uintptr_t drivetrain = read_ptr(this, g_config.drivetrain_offset);
        if (drivetrain != 0) {
            g_player_drivetrain = drivetrain;
        }

        // ★ ABS 被编译器内联到 carController 里，HandleABS 方法从不被调用，
        // 所以 hook HandleABS 入口没用。但内联的 ABS 逻辑读 absEnable(0xC4)
        // 门控——在这里（FixedUpdate 入口，carController 之前）设 absEnable=0，
        // 内联的 ABS 检查就会跳过。比 hook 内联代码可靠得多。
        if (!g_config.enable_abs) {
            write_bool_field(this, 0xC4, false);

            // per-wheel: 写 usesABS=false (0x3CE)，直接禁用每个轮子的 ABS。
            // absEnable=false 不足以阻止内联的 ABS 逻辑——内联代码同时检查
            // 车辆级 absEnable 和轮子级 usesABS 做双重门控。
            // wheels 数组在偏移 0x28 (IRDSWheel[])，IL2CPP 数组数据从 0x20 开始。
            void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
            if (wheels_arr != NULL) {
                for (int i = 0; i < 4; i++) {
                    void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
                    if (wheel != NULL) {
                        write_bool_field(wheel, 0x3CE, false);
                    }
                }
            }
        }

        // 隐藏游戏原生油门/刹车按钮——FixedUpdate 每物理步（50fps）调用，
        // 比 proxy_player_controls_update（Update）更稳定，计时赛加载期间
        // Update 可能每 2 秒才调用一次，但 FixedUpdate 始终 50fps。
        // 原子锁防止与 proxy_player_controls_update 中的 tick 竞态。
        hide_pedals_tick();

        // 注意：不再在此处调 apply_inputs_to_controller。
        // 确保模块输入由 writer 线程（~500Hz）写入——它只写 g_player_controller
        //（由只挂在玩家车上的 IRDSPlayerControls.Update 设置），不受 AI 车误判影响。
        // 这里只保留 TC/ABS 写入（写字段对 AI 车无害）。
    }

    typedef void (*orig_t)(void *);
    if (g_fixed_update_orig != NULL) {
        ((orig_t) g_fixed_update_orig)(this);
    }

    // 恢复：在 orig 后也写一次（确保模块值覆盖游戏 FixedUpdate 写入的值）。
    // 但只写玩家车。
    // 注：这行是有风险的——如果 is_player_controller 对 AI 车也返回 true，就会写 AI 车。
    // 保留此行是因为游戏每帧 FixedUpdate 会覆盖 throttle/brake 字段，不在这写，
    // 单靠 writer 线程的 2ms 间隔可能被 FixedUpdate 的 50Hz (~20ms) 周期覆盖。
    // 用户报告 AI 误控时，先注释，等诊断确认 is_player_controller 可靠性后再决定。
    /*
    if (is_player) {
        apply_inputs_to_controller(this);
    }
    */
}

static void proxy_drivetrain_fixed_update(void *this) {
    typedef void (*orig_t)(void *);

    // 记录玩家车 drivetrain（DoGearShifting hook 用它过滤非玩家车）。
    // 注：不在 FixedUpdate 写 automatic——FixedUpdate 每帧开头会用
    // 设置值覆盖 automatic，写在这里会被立刻冲掉。真正禁自动换挡由
    // proxy_drivetrain_do_gear_shifting 在 DoGearShifting 入口层完成。
    if (this != NULL && is_player_controller(this)) {
        g_player_drivetrain = (uintptr_t) this;
    }

    if (g_drivetrain_fixed_update_orig != NULL) {
        ((orig_t) g_drivetrain_fixed_update_orig)(this);
    }
}

// IRDSDrivetrain::DoGearShifting — 自动换挡入口（FixedUpdate 每帧调用）。
// ⚠️ 暂时禁用此 hook（回退到只靠 proxy_drivetrain_fixed_update 写 automatic=false）。
// 原因：设 overrideClutchManagement(0x15C)=1 + automatic(0xBC)=1 会让
// DoGearShifting 开头直接 return，但游戏起步需要 DoGearShifting 里
// 的逻辑来结合离合器/挂挡，整段跳过导致车出不了 P 房（一直 1 挡蠕动）。
// 手动换挡关自动换挡的正确方式待反汇编确认后重做。
static void proxy_drivetrain_do_gear_shifting(void *this) {
    // 暂不干预——直接调 orig，恢复游戏原逻辑。
    typedef void (*orig_t)(void *);
    if (g_drivetrain_do_gear_shifting_orig != NULL) {
        ((orig_t) g_drivetrain_do_gear_shifting_orig)(this);
    }
}

// IRDSPlayerControls::Update() 每帧调用，this 是 IRDSPlayerControls 实例。
// IRDSPlayerControls 只挂在玩家车 GameObject 上——天然身份过滤。
// 从 this+0x60 读 carInputs（IRDSCarControllInput*），刷新 g_last_controller。
// 这解决了"重新开始"后旧实例失效、g_last_controller 停在野指针的问题：
// 新场景的 IRDSPlayerControls.Update 立即把 g_last_controller 刷新到新实例。
//
// 同时：强制关闭 TC/ABS——非手柄模式下游戏默认开启辅助（打滑/抱死被抑制），
// 用户要的是"更专业"的无辅助操作。这里当模块开关关闭时，强制玩家车
// tclEnable(0xC6)/absEnable(0xC4)=false，真正关掉游戏自带的 TC/ABS。
// 开关开启（默认）时**不干预**，让游戏维持默认（通常开启）。
// 只作用玩家车（IRDSPlayerControls 组件只挂玩家车，天然身份过滤），
// AI 车辅助不受影响。
static void proxy_player_controls_update(void *this) {
    if (this != NULL) {
        g_player_controls = this;  // 记住 IRDSPlayerControls 实例
        void *car_inputs = *(void **) ((uintptr_t) this + 0x60);
        if (car_inputs != NULL) {
            // ★ 玩家车 controller 的唯一写入点。IRDSPlayerControls 只挂玩家车
            // GameObject，从这里读 carInputs 是最可靠的玩家车判据。
            // 同时刷新 g_last_controller（JNI 入口用）和 g_player_controller（writer 线程用）。
            g_last_controller = car_inputs;
            g_player_controller = car_inputs;

            // ★ ABS 被内联到 carController，hook HandleABS 方法没用。
            // 在 FixedUpdate 入口写 absEnable=0 更可靠（carController 紧随其后读），
            // 但 PlayerControls.Update 每帧也会被调，这里作为双重保险再写一次。
            if (!g_config.enable_abs && is_player_controller(car_inputs)) {
                write_bool_field(car_inputs, 0xC4, false);
            }
        }
    }

    typedef void (*orig_t)(void *);
    if (g_player_controls_update_orig != NULL) {
        ((orig_t) g_player_controls_update_orig)(this);
    }

    // 隐藏游戏原生油门/刹车按钮——在 Unity 主线程中调用（线程安全）。
    // hide_pedals_tick 内部用帧计数器降频，约每 2 秒执行一次。
    hide_pedals_tick();
}

// IRDSCarControllInput::TractionFilter(float accel) — TC 入口。
// 签名: float TractionFilter(void *this, float accel)
// 返回削减后的 accel。当模块 TC 开关关闭时，直接返回原始 accel（不削减），
// 绕过游戏自带 TC。比写字段更可靠——不依赖 tclEnable 是否被游戏覆盖。
// 只作用玩家车（is_player_controller 过滤）。
static float proxy_traction_filter(void *this, float accel) {
    if (!g_config.enable_tc && this != NULL && is_player_controller(this)) {
        // TC 关闭：直接返回原始 accel，不调 orig（跳过 TC 削减）
        return accel;
    }
    typedef float (*orig_t)(void *, float);
    if (g_traction_filter_orig != NULL) {
        return ((orig_t) g_traction_filter_orig)(this, accel);
    }
    return accel;
}

// IRDSCarControllInput::HandleABS() — ABS 入口。
// 签名: void HandleABS(void *this)
// 当模块 ABS 开关关闭时，直接返回（不调 orig），跳过游戏自带 ABS。
// 只作用玩家车。
static void proxy_handle_abs(void *this) {
    if (!g_config.enable_abs && this != NULL && is_player_controller(this)) {
        // ABS 关闭：跳过整个 HandleABS
        return;
    }
    typedef void (*orig_t)(void *);
    if (g_handle_abs_orig != NULL) {
        ((orig_t) g_handle_abs_orig)(this);
    }
}

// IRDSCarControllInput::carController() — 车辆控制器主方法。
// HandleABS 被编译器内联到这里，所以 ABS 的实际门控检查在 carController 内部。
// proxy_fixed_update 在 orig 前写 absEnable=false，但 orig（FixedUpdate）内部
// 可能在调用 carController 前恢复了 absEnable=true，覆盖我们的 false。
// hook carController 入口：在 carController 执行前再写一次 absEnable=false，
// 确保内联的 ABS 逻辑读到 false 就跳过。
//
// carController RVA = FixedUpdate RVA + 0xA8（同类内方法统一偏移，
// 8.0.0 和 8.0.4 中差值一致）。
static void proxy_car_controller(void *this) {
    if (!g_config.enable_abs && this != NULL && is_player_controller(this)) {
        write_bool_field(this, 0xC4, false);

        // per-wheel: 写 usesABS=false (0x3CE)，直接禁用每个轮子的 ABS。
        // absEnable=false 不足以阻止内联的 ABS 逻辑，需要在轮子级也禁用。
        // wheels 数组在偏移 0x28 (IRDSWheel[])，IL2CPP 数组数据从 0x20 开始。
        void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
        if (wheels_arr != NULL) {
            for (int i = 0; i < 4; i++) {
                void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
                if (wheel != NULL) {
                    write_bool_field(wheel, 0x3CE, false);
                }
            }
        }
    }
    typedef void (*orig_t)(void *);
    if (g_car_controller_orig != NULL) {
        ((orig_t) g_car_controller_orig)(this);
    }
}

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

bool pedal_install_hooks(const pedal_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_control_replacement) {
        LOGI("Pedal control replacement disabled");
        return true;
    }

    if (g_hooks_installed) {
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address");
        return false;
    }
    LOGI("libil2cpp.so base address: 0x%" PRIxPTR, base);

    if (g_config.set_throttle_offset != 0) {
        uintptr_t target = base + g_config.set_throttle_offset;
        g_throttle_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_throttle,
                (void **) &g_throttle_orig);
        if (g_throttle_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setThrottle) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setThrottle at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.set_brake_offset != 0) {
        uintptr_t target = base + g_config.set_brake_offset;
        g_brake_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_brake,
                (void **) &g_brake_orig);
        if (g_brake_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setBrake) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setBrake at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.shift_up_offset != 0) {
        uintptr_t target = base + g_config.shift_up_offset;
        g_shift_up_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_shift_up,
                (void **) &g_shift_up_orig);
        if (g_shift_up_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(shiftUp) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked shiftUp at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.shift_down_offset != 0) {
        uintptr_t target = base + g_config.shift_down_offset;
        g_shift_down_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_shift_down,
                (void **) &g_shift_down_orig);
        if (g_shift_down_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(shiftDown) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked shiftDown at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.set_gear_offset != 0) {
        LOGI("setGear offset recorded but not installed");
    }

    if (g_config.fixed_update_offset != 0) {
        uintptr_t target = base + g_config.fixed_update_offset;
        g_fixed_update_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_fixed_update,
                (void **) &g_fixed_update_orig);
        if (g_fixed_update_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(FixedUpdate) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked FixedUpdate at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.drivetrain_fixed_update_offset != 0) {
        uintptr_t target = base + g_config.drivetrain_fixed_update_offset;
        g_drivetrain_fixed_update_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_drivetrain_fixed_update,
                (void **) &g_drivetrain_fixed_update_orig);
        if (g_drivetrain_fixed_update_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(DrivetrainFixedUpdate) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked DrivetrainFixedUpdate at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.drivetrain_do_gear_shifting_offset != 0) {
        uintptr_t target = base + g_config.drivetrain_do_gear_shifting_offset;
        g_drivetrain_do_gear_shifting_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_drivetrain_do_gear_shifting,
                (void **) &g_drivetrain_do_gear_shifting_orig);
        if (g_drivetrain_do_gear_shifting_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(DoGearShifting) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked DoGearShifting at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.traction_filter_offset != 0) {
        uintptr_t target = base + g_config.traction_filter_offset;
        g_traction_filter_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_traction_filter,
                (void **) &g_traction_filter_orig);
        if (g_traction_filter_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(TractionFilter) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked TractionFilter at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.handle_abs_offset != 0) {
        uintptr_t target = base + g_config.handle_abs_offset;
        g_handle_abs_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_handle_abs,
                (void **) &g_handle_abs_orig);
        if (g_handle_abs_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(HandleABS) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked HandleABS at 0x%" PRIxPTR, target);
        }
    }

    // carController = FixedUpdate + 0xA8（同类内方法统一偏移）。
    // HandleABS 被内联到 carController，proxy_fixed_update 在 orig 前写
    // absEnable=false 会被 FixedUpdate 内部恢复，所以必须在 carController
    // 入口再写一次 absEnable=false。
    if (g_config.fixed_update_offset != 0) {
        uintptr_t car_controller_offset = g_config.fixed_update_offset + 0xA8;
        uintptr_t target = base + car_controller_offset;
        g_car_controller_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_car_controller,
                (void **) &g_car_controller_orig);
        if (g_car_controller_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(carController) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked carController at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.player_controls_update_offset != 0) {
        uintptr_t target = base + g_config.player_controls_update_offset;
        g_player_controls_update_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_player_controls_update,
                (void **) &g_player_controls_update_orig);
        if (g_player_controls_update_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(PlayerControlsUpdate) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked PlayerControlsUpdate at 0x%" PRIxPTR, target);
        }
    }

    g_hooks_installed = 1;
    start_input_writer();
    return true;
}

void pedal_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }

    stop_input_writer();

    if (g_throttle_stub != NULL) {
        shadowhook_unhook(g_throttle_stub);
        g_throttle_stub = NULL;
        g_throttle_orig = NULL;
    }
    if (g_brake_stub != NULL) {
        shadowhook_unhook(g_brake_stub);
        g_brake_stub = NULL;
        g_brake_orig = NULL;
    }
    if (g_shift_up_stub != NULL) {
        shadowhook_unhook(g_shift_up_stub);
        g_shift_up_stub = NULL;
        g_shift_up_orig = NULL;
    }
    if (g_shift_down_stub != NULL) {
        shadowhook_unhook(g_shift_down_stub);
        g_shift_down_stub = NULL;
        g_shift_down_orig = NULL;
    }
    if (g_fixed_update_stub != NULL) {
        shadowhook_unhook(g_fixed_update_stub);
        g_fixed_update_stub = NULL;
        g_fixed_update_orig = NULL;
    }
    if (g_drivetrain_fixed_update_stub != NULL) {
        shadowhook_unhook(g_drivetrain_fixed_update_stub);
        g_drivetrain_fixed_update_stub = NULL;
        g_drivetrain_fixed_update_orig = NULL;
    }
    if (g_drivetrain_do_gear_shifting_stub != NULL) {
        shadowhook_unhook(g_drivetrain_do_gear_shifting_stub);
        g_drivetrain_do_gear_shifting_stub = NULL;
        g_drivetrain_do_gear_shifting_orig = NULL;
    }
    if (g_traction_filter_stub != NULL) {
        shadowhook_unhook(g_traction_filter_stub);
        g_traction_filter_stub = NULL;
        g_traction_filter_orig = NULL;
    }
    if (g_handle_abs_stub != NULL) {
        shadowhook_unhook(g_handle_abs_stub);
        g_handle_abs_stub = NULL;
        g_handle_abs_orig = NULL;
    }
    if (g_car_controller_stub != NULL) {
        shadowhook_unhook(g_car_controller_stub);
        g_car_controller_stub = NULL;
        g_car_controller_orig = NULL;
    }
    if (g_player_controls_update_stub != NULL) {
        shadowhook_unhook(g_player_controls_update_stub);
        g_player_controls_update_stub = NULL;
        g_player_controls_update_orig = NULL;
    }

    g_last_controller = NULL;
    g_player_drivetrain = 0;
    g_hooks_installed = 0;
    g_config.enable_control_replacement = false;
}

void *pedal_get_controller(void) {
    return (void *) g_player_controller;
}

void pedal_shift_up(void) {
    LOGI("pedal_shift_up");
    void *controller = (void *) g_player_controller;
    if (controller != NULL && is_player_controller(controller) && g_shift_up_orig != NULL) {
        typedef void (*orig_t)(void *);
        ((orig_t) g_shift_up_orig)(controller);
    }
}

void pedal_shift_down(void) {
    LOGI("pedal_shift_down");
    void *controller = (void *) g_player_controller;
    if (controller != NULL && is_player_controller(controller) && g_shift_down_orig != NULL) {
        typedef void (*orig_t)(void *);
        ((orig_t) g_shift_down_orig)(controller);
    }
}

void pedal_set_disable_auto_gear(int disable) {
    g_disable_auto_gear = disable ? 1 : 0;
}

void pedal_set_tc_abs(int enable_tc, int enable_abs) {
    g_config.enable_tc = enable_tc ? true : false;
    g_config.enable_abs = enable_abs ? true : false;
    LOGI("pedal_set_tc_abs: enable_tc=%d enable_abs=%d", enable_tc, enable_abs);
}

void pedal_set_throttle_value(float value) {
    int was_active = g_throttle_active;

    if (value <= 0.0f) {
        g_throttle_active = 0;
        g_throttle_value = 0.0f;
    } else {
        g_throttle_active = 1;
        g_throttle_value = value;
    }

    // 用 g_player_controller（由 IRDSPlayerControls.Update 设置，只挂玩家车）。
    void *controller = (void *) g_player_controller;
    if (controller == NULL) return;
    if (!is_player_controller(controller)) return;  // 野指针/已销毁实例防护

    if (g_throttle_active) {
        // 用户按住/调整：写当前值。
        apply_inputs_to_controller(controller);
    } else if (was_active) {
        // active→inactive（松开）：主动清零一次，避免值卡住。
        // writer 线程和 FixedUpdate hook 在 !active 时不再写字段，
        // 所以这里必须主动清一次。
        clear_throttle_field(controller);
    }
}

void pedal_set_brake_value(float value) {
    int was_active = g_brake_active;

    if (value <= 0.0f) {
        g_brake_active = 0;
        g_brake_value = 0.0f;
    } else {
        g_brake_active = 1;
        g_brake_value = value;
    }

    // 用 g_player_controller（由 IRDSPlayerControls.Update 设置，只挂玩家车）。
    void *controller = (void *) g_player_controller;
    if (controller == NULL) return;
    if (!is_player_controller(controller)) return;  // 野指针/已销毁实例防护

    if (g_brake_active) {
        apply_inputs_to_controller(controller);
    } else if (was_active) {
        clear_brake_field(controller);
    }
}
