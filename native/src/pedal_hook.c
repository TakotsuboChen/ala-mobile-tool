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
// TC 时机档接管状态：>0 表示当前正在覆写 TCLSlip/TCLminSPD（用于切回
// "游戏默认"时的一次性基线恢复，见 proxy_traction_filter 注释）。
static int g_tc_taking_over = 0;
// 游戏真实 TC 参数基线（v1.4）：TractionFilter 首次覆写前捕获的 0x34/0x38
// 值。游戏进赛道时经 SetPlayerSettings 写入真正参数（实测 ε=0.40、
// minSPD=11.0，与 ctor 默认 0.45/1.0 不同），且之后不每帧重写——所以恢复
// 必须写回捕获的基线，不能写 ctor 默认。-1 = 尚未捕获（兜底用实测常量）。
static float g_tc_base_slip = -1.0f;
static float g_tc_base_minspd = -1.0f;
// ABS 档位接管状态：>0 表示当前正在覆写对应字段（用于切回"游戏默认"时的
// 一次性基线恢复）。v5 起制动压力改为输入端缩放（brake_scale），不再
// 字段覆写 T_b——仅 b 通道需要 taking_over 跟踪。
static int g_abs_b_taking_over = 0;
// 游戏真实 ABS 参数基线（ABS_LEVEL_DESIGN v2/v5）：首次覆写前捕获的
// b(0x3E0) per-wheel 值。游戏经 SetBrakeBiasValues 装车时写入
// 真实值（b=clamp01((bias−60)/10)×0.3），事件驱动不
// 每帧重写——所以恢复必须写回捕获基线。-1 = 尚未捕获。
// restart/换车时 wheels 数组指针变化 → 重置重捕。
// v5：T_b(0x88) 通道整体退役（制动压力不再触碰 0x88）。
static float g_abs_base_b[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
static void *g_abs_last_wheels = NULL;
// usesABS(0x3CE) 残留恢复：关闭路径写过 false 后，游戏**永远不会自己写回**
//（原生唯一写者 Awake 装车写一次）——切回开启时必须由模块恢复基线，否则
// 任何档位（含总开关回默认）都永远停在关闭状态（实机实测 2026-08-28）。
static int g_abs_uses_taking_over = 0;
static int g_abs_base_uses[4] = {-1, -1, -1, -1};  // -1=未捕获，0/1=基线

// ── TC/ABS 介入指示灯信号（Java 主线程 JNI 轮询读，volatile 保证可见性）──
// ABS 介入信号 = **游戏原生执行点的直击**：inline 拦截 RoadForce 内
// "tempBrakeF 释放/管理写入"指令（base+0x1A7B7DC，str s0,[x19,#0x3EC]——
// 反汇编实证：只有滑移超阈帧才流经此处；未超阈 b.le 直接绕过；0x1A7B768
// 的另一写入点是每帧必经的普通路径，不含介入语义）。命中即 = 游戏此刻
// 正在对某轮施加 ABS 滑移管理（含 pulse 泄压与 kP 管理两相位）——这是
// 游戏自己的执行流在发声，非字段条件复算。玩家车过滤：x19(wheel) 与
// g_player_controller 的 4 轮指针比对（AI 车 RoadForce 同样命中，必须滤）。
// TC：无内建方波（连续 smoothstep 削减律），电平 = 削减(f<accel) &&
// g_frame_phase（25Hz 帧时钟）。
static volatile int g_tc_active = 0;
static volatile int g_abs_active = 0;
// 25Hz 帧相位 + 帧号：玩家白名单必经点（proxy_fixed_update，不受 TC/ABS
// 开关影响）每物理帧 seq++ 且 phase = seq&1。
static volatile int g_frame_phase = 0;
static volatile long long g_frame_seq = 0;
// ABS 介入 = "最近帧内拦截器命中过"：命中时记 g_abs_hit_seq = 当前帧号，
// 查询时 (g_frame_seq - g_abs_hit_seq) <= 容差 即介入。**不做清零**——
// RoadForce 与 CC.FixedUpdate 是独立 MonoBehaviour 物理回调，Unity 不保证
// 先后（实机实证 2026-08-30：帧头清零会把同帧稍早的命中抹掉，灯恒灭）。
static volatile long long g_abs_hit_seq = -1000;
// RoadForce 指令拦截器已安装（0 = 未装/失败，1 = 已装）。
static volatile int g_abs_rf_intercept_installed = 0;
// 诊断：拦截器命中计数 + 玩家车过滤命中计数（物理线程写，诊断日志读）。
static volatile long long g_abs_rf_hits_total = 0;
static volatile long long g_abs_rf_hits_player = 0;

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

// 严格玩家车判定（白名单实例比对）：instance 必须就是 g_player_controller——
// 由 proxy_player_controls_update 设置（IRDSPlayerControls 组件只挂玩家车
// GameObject，实机验证过的单一可靠来源）。
//
// ⚠️ 拦截类 hook（关 TC/关 ABS 的入口直接返回）必须用本函数，不能用
// is_player_controller 做拦截判定：AI 车的 playerControls (0x108) 可能非空
// （实机实证：关 TC 时 19 辆 AI 车的 TractionFilter 一并被跳过，AI 全体
// 失去 TC 保护 → 打滑失控 → 失误率暴增）。TractionFilter 是每辆车每物理帧
// 的必经路径，误判必现；setter 路径 AI 未必经过，所以当年油门修复后此缺陷
// 一直潜伏到 TC hook 上线才暴露。
// is_player_controller 仅保留两类用途：setter 透传的宽松过滤、
// 白名单实例的野指针二次校验。
static inline int is_target_player_car(void *instance) {
    return instance != NULL && instance == (void *) g_player_controller;
}

// ABS 档位覆写与诊断——定义在 proxy_handle_abs 之后，此处前向声明
//（proxy_fixed_update 白名单分支先于定义调用）。
static void abs_apply_gear(void *this);
static void abs_diag_log(void *this);
static void abs_remap_brake_request(void *this);

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
        // v6：制动压力不走输入端线性缩放（见 abs_remap_brake_request——
        // 0xF0 饱和重映射），writer 直写原始手指值，经 CC 统一分发后由
        // remap 层统一处理。
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

    // 只在玩家车时吞掉游戏输入的刹车值——AI 车须透传 orig。
    if (is_player && g_brake_active) {
        return;
    }

    typedef void (*orig_t)(void *, float);
    if (g_brake_orig != NULL) {
        // v6：透传不缩放（制动压力走 abs_remap_brake_request 的 0xF0 层）。
        ((orig_t) g_brake_orig)(this, value);
    }
}

// 透传 hook：proxy 只把调用转给 orig，无行为改写。挂在所有车的
// shiftUp/shiftDown 上（AI 车换挡也经过），任何这里的 LOGI 都会造成
// 日志洪水（实测 21 分钟 18810 条，enableManualShift=false 时同样打），
// 并淹没其他诊断日志——保持无日志纯透传。
static void proxy_shift_up(void *this) {
    typedef void (*orig_t)(void *);
    if (g_shift_up_orig != NULL) {
        ((orig_t) g_shift_up_orig)(this);
    }
}

static void proxy_shift_down(void *this) {
    typedef void (*orig_t)(void *);
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

        // 指示灯帧号推进：每物理帧 seq++（相位 = seq&1）。ABS 信号不再
        // 清零——命中帧号与当前帧号比对（见 g_abs_hit_seq 注释）。
        g_frame_seq++;
        g_frame_phase ^= 1;

        // ★ ABS 档位每帧覆写（干预强度 b + 制动压力 T_b 缩放 + usesABS 残留
        // 恢复）。**必须在关闭块之前执行**：基线捕获要求字段尚未被模块碰过
        //（关闭路径先跑会把 usesABS 写 false，污染基线）。白名单同上。
        // 覆写与 RoadForce 的读取时序：FixedUpdate 先于物理步进，本帧写入
        // 本帧生效（与 TC 字段覆写同模式）。
        if (is_target_player_car(this)) {
            abs_apply_gear(this);
            abs_diag_log(this);
        }

        // ★ ABS 被编译器内联到 carController 里，HandleABS 方法从不被调用，
        // 所以 hook HandleABS 入口没用。但内联的 ABS 逻辑读 absEnable(0xC4)
        // 门控——在这里（FixedUpdate 入口，carController 之前）设 absEnable=0，
        // 内联的 ABS 检查就会跳过。比 hook 内联代码可靠得多。
        // ⚠️ 在宽松 is_player 之上再叠白名单：is_player_controller 对 AI 车
        // 可能误判（见 is_target_player_car 注释），不加白名单会误关 AI 车 ABS。
        if (!g_config.enable_abs && is_target_player_car(this)) {
            write_bool_field(this, 0xC4, false);

            // per-wheel: 写 usesABS=false (0x3CE)，直接禁用每个轮子的 ABS。
            // absEnable=false 不足以阻止内联的 ABS 逻辑——内联代码同时检查
            // 车辆级 absEnable 和轮子级 usesABS 做双重门控。
            // wheels 数组在偏移 0x28 (IRDSWheel[])，IL2CPP 数组数据从 0x20 开始。
            // ⚠️ 置位 taking_over：关闭路径写 false 后游戏不会自己写回
            //（原生唯一写者 Awake 装车写一次），切回开启时由 abs_apply_gear
            // 恢复基线——否则任何档位（含总开关回默认）都永远停在关闭状态
            //（实机实测 2026-08-28）。
            void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
            if (wheels_arr != NULL) {
                for (int i = 0; i < 4; i++) {
                    void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
                    if (wheel != NULL) {
                        write_bool_field(wheel, 0x3CE, false);
                    }
                }
                g_abs_uses_taking_over = 1;
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

    // v6 制动压力重映射：必须在 orig 之后——carController（orig 内部尾跳）
    // 每帧把玩家刹车请求广播到 wheels[0..3] 的 0xF0，orig 后此值即本帧
    // 原始请求；此处写回重映射值，随后的物理步进里 RoadForce 读 0xF0
    // 计算制动扭矩（TECHNICAL_ANALYSIS §2.2.1 时序）。白名单同上（0xF0
    // 全车必经，误写 AI 车制动=瘫全场事故级）。
    if (is_player && is_target_player_car(this)) {
        abs_remap_brake_request(this);
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
// TC 诊断插桩：玩家车白名单分支内限频调用（每 25 次 ≈ 0.5s 一条，LOGI 走
// native_log_print——logcat 恒打 + logEnabled 门控文件写入）。读回运行时
// TCLSlip/TCLminSPD（写前值，可观测游戏侧复位行为）+ 各驱动轮 σ/α/maxSlip/
// maxAngle 与 W、削减比值。标定完成后可整段移除。
static void tc_diag_log(void *this, float filtered_out, float accel_in,
                        float slip_pre, float minspd_pre) {
    static int diag_counter = 0;
    if (++diag_counter < 25) {
        return;
    }
    diag_counter = 0;

    float w = 0.0f, sig = 0.0f, s_max = 0.0f, ang = 0.0f, a_max = 0.0f;
    void *dt = *(void **) ((char *) this + 0x98);
    if (dt != NULL) {
        void *arr = *(void **) ((char *) dt + 0x58);   // poweredIRDSWheels
        if (arr != NULL) {
            int n = *(int *) ((char *) arr + 0x18);
            void **data = (void **) ((char *) arr + 0x20);
            for (int i = 0; i < n; i++) {
                void *wheel = data[i];
                if (wheel == NULL) continue;
                float s = *(float *) ((char *) wheel + 0x104);
                float sm = *(float *) ((char *) wheel + 0x1a8);
                float a = *(float *) ((char *) wheel + 0x170);
                float am = *(float *) ((char *) wheel + 0x1ac);
                // 与 orig 一致：先 fabs 再比较（先比符号再取绝对值会漏掉大负数）。
                float wr = (sm != 0.0f) ? s / sm : 0.0f;
                float wa = (am != 0.0f) ? a / am : 0.0f;
                if (wr < 0.0f) wr = -wr;
                if (wa < 0.0f) wa = -wa;
                float wi = wr > wa ? wr : wa;
                if (wi > w) { w = wi; sig = s; s_max = sm; ang = a; a_max = am; }
            }
        }
    }
    int gear = 0;
    float sigma_bar = 0.0f;
    if (dt != NULL) {
        gear = *(int *) ((char *) dt + 0xc0);
        sigma_bar = *(float *) ((char *) dt + 0xcc);   // 传动系聚合滑移（触发层信号源）
    }
    LOGI("TCdiag: spd=%.1f gear=%d tclEn=%d mix=%.2f epsCfg=%.3f slipPre=%.3f minSpdPre=%.3f "
         "W=%.2f sig=%.3f smax=%.3f ang=%.3f amax=%.3f sbar=%.3f in=%.3f out=%.3f",
         *(float *) ((char *) this + 0x84),
         gear,
         (int) *(unsigned char *) ((char *) this + 0xc6),
         g_config.tc_mix,
         g_config.tc_eps,
         slip_pre,
         minspd_pre,
         w, sig, s_max, ang, a_max, sigma_bar, accel_in, filtered_out);
}

// 签名: float TractionFilter(void *this, float accel)
// 返回削减后的 accel。当模块 TC 开关关闭时，直接返回原始 accel（不削减），
// 绕过游戏自带 TC。比写字段更可靠——不依赖 tclEnable 是否被游戏覆盖。
// 只作用玩家车（白名单比对，见 is_target_player_car 注释——本 hook 会被
// 所有车每物理帧调用，用 is_player_controller 会误拦 AI 车的 TC）。
static float proxy_traction_filter(void *this, float accel) {
    typedef float (*orig_t)(void *, float);
    if (is_target_player_car(this)) {
        // 强度=关闭（tc_mix<=0，含旧 enable_tc=false 路径）：直接返回原始
        // accel，不调 orig（跳过 TC 削减）。清指示灯信号——此路径不走到
        // 下面的写点，残留 1 会让灯在 TC 关闭后仍然闪烁。
        if (!g_config.enable_tc || g_config.tc_mix <= 0.0f) {
            g_tc_active = 0;
            return accel;
        }
        // TC 时机档（仅在用户选了非默认时机时覆写）：成对写 TCLSlip (0x34)
        // 和 TCLminSPD (0x38)。
        // **v1.4 关键修复**：反汇编确认 TractionFilter 门控顺序是
        // ①carSpeed<TCLminSPD → 透传（在读 ε 之前）→ ②TCLSlip==0 → ③tclEnable
        // → ④(1-ε)·W>1。游戏运行时 minSPD=11.0（≈40km/h）：只调 ε 时起步打滑
        // 区间被门控①整段挡死，这是 v1.1/v1.3 "调时机无效果"的根因。故时机档
        // 必须 (eps, minspd) 成对覆写。**v1.3 教训保留**：不无条件写——只在
        // 用户配置偏离原厂时写，切回"游戏默认"一次性恢复基线。
        // 必须每帧写绝对值，勿缩放写（防复利衰减）。
        float slip_pre = *(float *) ((char *) this + 0x34);
        float minspd_pre = *(float *) ((char *) this + 0x38);
        if (g_tc_base_slip < 0.0f) {
            // 首次见到玩家车 TractionFilter：此刻字段尚未被模块碰过，值就是
            // 游戏 SetPlayerSettings 写入的真实参数——记为恢复基线。
            g_tc_base_slip = slip_pre;
            g_tc_base_minspd = minspd_pre;
            LOGI("TCdiag: baseline captured slip=%.3f minspd=%.3f",
                 g_tc_base_slip, g_tc_base_minspd);
        }
        if (g_config.tc_eps > 0.0f || g_config.tc_minspd > 0.0f) {
            *(float *) ((char *) this + 0x34) = g_config.tc_eps;
            *(float *) ((char *) this + 0x38) = g_config.tc_minspd;
            g_tc_taking_over = 1;
        } else if (g_tc_taking_over) {
            // 从自定义时机切回"游戏默认"：一次性回写捕获的游戏真实基线
            // （非 ctor 默认——实测 ε=0.40/minSPD=11.0），清掉残留后字段
            // 交还游戏自己管理。
            float slip_r = g_tc_base_slip >= 0.0f ? g_tc_base_slip : 0.40f;
            float minspd_r = g_tc_base_minspd >= 0.0f ? g_tc_base_minspd : 11.0f;
            *(float *) ((char *) this + 0x34) = slip_r;
            *(float *) ((char *) this + 0x38) = minspd_r;
            LOGI("TCdiag: baseline restored slip=%.3f minspd=%.3f",
                 slip_r, minspd_r);
            g_tc_taking_over = 0;
        }
        float f = accel;
        if (g_traction_filter_orig != NULL) {
            f = ((orig_t) g_traction_filter_orig)(this, accel);
            // 强度档：TractionFilter 返回值线性插值 f_m = τ + (f−τ)·mix。
            // 代入 carController 内联合成 τ' = 0.15τ + 0.85·f_m，化简得
            // τ' = τ·(1 − 0.85·mix·S)：mix=1 逐位等同原厂，mix=0 全关。
            if (g_config.tc_mix < 1.0f) {
                f = accel + (f - accel) * g_config.tc_mix;
            }
        }
        tc_diag_log(this, f, accel, slip_pre, minspd_pre);

        // 介入指示灯信号：电平 = 削减判定(f<accel) && 25Hz 相位（只读！
        // 相位时钟单一翻转点在 proxy_fixed_update 帧头——此处若再翻会
        // 双重翻转抵消，本写点读到的相位恒 0，TC 灯死，实机实证）。
        // 混合档插值 f=accel+(f−accel)·mix 只在 f<accel 时再往下拉，不影响
        // 0→1 判定。
        g_tc_active = (f < accel && g_frame_phase) ? 1 : 0;
        return f;
    }
    // 非玩家车（AI）：透传，绝不拦截——白名单比对见 is_target_player_car 注释，
    // 本 hook 被所有车每物理帧调用，误拦 AI 车会全场失控（历史事故）。
    if (g_traction_filter_orig != NULL) {
        return ((orig_t) g_traction_filter_orig)(this, accel);
    }
    return accel;
}

// ABS 诊断插桩（proxy_fixed_update 白名单分支内限频调用，每 25 次 ≈ 0.5s
// 一条）：读回 0 号轮运行时 b/T_b/pulseBrakes/tempBrakeF/brakePressure 与
// bias、车速——标定档位数值（b/T_b/bias 运行时真值）+ 观测方波行为。
// 标定完成后可整段移除。
static void abs_diag_log(void *this) {
    static int diag_counter = 0;
    if (++diag_counter < 25) {
        return;
    }
    diag_counter = 0;

    void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
    if (wheels_arr == NULL) return;
    void *wheel0 = *(void **)((uintptr_t)wheels_arr + 0x20);
    void *wheel2 = *(void **)((uintptr_t)wheels_arr + 0x20 + 2 * 8);
    if (wheel0 == NULL) return;
    // v6 重映射校验：轮 0/轮 2 的 tb/p0（front/rear 供轮序假设核对）+
    // 0xF0 重映射结果（1=未触发/100% 档）。
    LOGI("ABSdiag: spd=%.1f mix=%.1f bCfg=%.2f bsCfg=%.2f "
         "b=%.3f tb=%.1f pulse=%d tf=%.1f bp=%.3f",
         *(float *)((uintptr_t)this + 0x84),
         g_config.abs_mix,
         g_config.abs_b_override,
         g_config.brake_scale,
         *(float *)((uintptr_t)wheel0 + 0x3E0),   // rawBrakeBiasValue（pulse 释放深度 b）
         *(float *)((uintptr_t)wheel0 + 0x88),    // brakeFrictionTorque（T_b 基数）
         (int)*(unsigned char *)((uintptr_t)wheel0 + 0x408),  // pulseBrakes（介入标志）
         *(float *)((uintptr_t)wheel0 + 0x3EC),   // tempBrakeF
         *(float *)((uintptr_t)wheel0 + 0x418));  // brakePressure（踏板输入副本）
    // 拦截器诊断：total=全部命中（含 AI 车），player=玩家车过滤后命中。
    // 每 0.5s 增量——若 total 不涨 = 拦截器没命中（地址错/未装）；total 涨
    // 而 player 不涨 = 玩家车过滤失败（wheels 指针链错）。
    static long long diag_total_last = 0, diag_player_last = 0;
    long long dt = g_abs_rf_hits_total - diag_total_last;
    long long dp = g_abs_rf_hits_player - diag_player_last;
    diag_total_last = g_abs_rf_hits_total;
    diag_player_last = g_abs_rf_hits_player;
    long long age = g_frame_seq - g_abs_hit_seq;
    LOGI("ABSdiag: rfHits total=%lld player=%lld hitAge=%lld lvl=%d phase=%d",
         dt, dp, age, (age >= 0 && age <= 2) ? 1 : 0, g_frame_phase);
    if (wheel2 != NULL) {
        LOGI("ABSdiag: w0 tb=%.1f p0f=%.1f p0r=%.1f | w2 tb=%.1f p0f=%.1f p0r=%.1f 0xF0=%.3f",
             *(float *)((uintptr_t)wheel0 + 0x88),
             *(float *)((uintptr_t)wheel0 + 0x3E4),
             *(float *)((uintptr_t)wheel0 + 0x3E8),
             *(float *)((uintptr_t)wheel2 + 0x88),
             *(float *)((uintptr_t)wheel2 + 0x3E4),
             *(float *)((uintptr_t)wheel2 + 0x3E8),
             *(volatile float *)((uintptr_t)wheel2 + 0xF0));
    }
}

// ABS 档位每帧覆写（proxy_fixed_update 白名单分支调用，玩家车 50Hz）。
// b 覆写（干预强度）：abs_mix>0 且 abs_b_override>=0 时，写每轮
//   b(0x3E0)=档位值（绝对值，勿缩放写防复利衰减，TC v1.2 教训）。
//   抬 b 直接抬 pulse 方波平均 (1+b)/2——修"全段几乎不锁死"过度保护。
// ⚠️ v5：制动压力（"最大制动压力"滑条）不走字段覆写——改为输入端
//   等比缩放（brake_scale，见 apply_inputs_to_controller / proxy_set_brake），
//   tempBrakeF 链（F_base/T_b/p₀）全程原生不碰。T_b(0x88) 覆写通道整体
//   退役（v2/v3/v4 尝试：全局缩 T_b 会联动压缩 F_base 曲线；任意门控方案
//   又把滑条与 ABS 状态耦合——均被用户否决）。
// 切回恢复：want_b 从真变假（切"游戏默认"/关闭档）→ 一次性回写捕获基线，
// 字段交还游戏（SetBrakeBiasValues 事件驱动，正常圈驾不重写）。
// ⚠️ 全程 is_target_player_car 白名单内（RoadForce 全车必经，误写 AI 车
// 重演"关 TC 瘫全场"事故）；换车（wheels 指针变化）时重置基线重捕。
static void abs_apply_gear(void *this) {
    void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
    if (wheels_arr == NULL) return;

    // restart/换车检测：wheels 数组指针变化 → 旧基线作废，重置重捕。
    if (wheels_arr != g_abs_last_wheels) {
        if (g_abs_last_wheels != NULL) {
            LOGI("ABSdiag: wheels changed, resetting baseline");
        }
        g_abs_last_wheels = wheels_arr;
        for (int i = 0; i < 4; i++) {
            g_abs_base_b[i] = -1.0f;
            g_abs_base_uses[i] = -1;
        }
        g_abs_b_taking_over = 0;
        g_abs_uses_taking_over = 0;
    }

    int want_b = (g_config.abs_mix > 0.0f && g_config.abs_b_override >= 0.0f);
    int want_abs_off = !g_config.enable_abs;

    for (int i = 0; i < 4; i++) {
        void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
        if (wheel == NULL) continue;
        // 基线捕获先于一切覆写：此刻字段尚未被模块碰过，值即游戏装车真值
        //（b/usesABS 同轮同帧独立捕获；usesABS 若关闭路径先跑会被
        // 写 false 污染——本函数必须排在关闭块之前）。
        if (g_abs_base_b[i] < 0.0f) {
            g_abs_base_b[i] = *(float *)((uintptr_t)wheel + 0x3E0);
            g_abs_base_uses[i] = *(unsigned char *)((uintptr_t)wheel + 0x3CE) ? 1 : 0;
            LOGI("ABSdiag: baseline[%d] captured b=%.3f uses=%d",
                 i, g_abs_base_b[i], g_abs_base_uses[i]);
        }
        if (want_b) {
            *(volatile float *)((uintptr_t)wheel + 0x3E0) = g_config.abs_b_override;
        }
    }
    if (want_b) g_abs_b_taking_over = 1;

    // usesABS 残留恢复（一次性）：关闭路径写过 false 后，游戏永远不会自己
    // 写回（原生唯一写者 Awake 装车写一次）——enable_abs 回 true 时这里
    // 恢复捕获基线（通常 true）。不恢复的话切到任何档位（含总开关回默认）
    // 都会永远停在关闭状态（实机实测 2026-08-28）。
    if (!want_abs_off && g_abs_uses_taking_over) {
        for (int i = 0; i < 4; i++) {
            void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
            if (wheel == NULL || g_abs_base_uses[i] < 0) continue;
            write_bool_field(wheel, 0x3CE, g_abs_base_uses[i] != 0);
        }
        LOGI("ABSdiag: usesABS baseline restored (%d)", g_abs_base_uses[0]);
        g_abs_uses_taking_over = 0;
    }

    // 切回恢复（一次性）：b 通道混入默认。
    if (!want_b && g_abs_b_taking_over) {
        for (int i = 0; i < 4; i++) {
            void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
            if (wheel == NULL || g_abs_base_b[i] < 0.0f) continue;
            *(volatile float *)((uintptr_t)wheel + 0x3E0) = g_abs_base_b[i];
        }
        LOGI("ABSdiag: b baseline restored");
        g_abs_b_taking_over = 0;
    }
}

// ── ABS 介入原生信号：RoadForce tempBrakeF 释放/管理写入指令拦截器 ──
// 拦截地址 base+0x1A7B7DC（str s0, [x19, #0x3EC]）。反汇编实证
//（build/abs_scan/roadforce.asm + TECHNICAL_ANALYSIS §2.3）：该指令只在
// 滑移超阈帧执行（未超阈走 0x1A7B770 b.le 绕过），是游戏 ABS 真实介入的
// 执行点——命中即游戏此刻正在对该轮施加滑移管理。x19 = IRDSWheel。
// 高频路径（全车每物理帧滑移超阈时命中）：只做 4 次指针比对 + 一次写。
static void abs_rf_intercept_pre(shadowhook_cpu_context_t *ctx, void *data) {
    (void) data;
    g_abs_rf_hits_total++;
    void *wheel = (void *) ctx->regs[19];  // x19 = IRDSWheel
    void *ctrl = (void *) g_player_controller;
    if (ctrl == NULL || wheel == NULL) return;
    // 玩家车过滤：wheel 必须属于 g_player_controller 的 wheels[0..3]。
    void *wheels_arr = *(void **) ((uintptr_t) ctrl + 0x28);
    if (wheels_arr == NULL) return;
    for (int i = 0; i < 4; i++) {
        if (*(void **) ((uintptr_t) wheels_arr + 0x20 + i * 8) == wheel) {
            g_abs_rf_hits_player++;
            // 物理效果过滤：滑移超阈帧油门打滑时也会流经此处（ABS 调制段
            // 执行条件不含"正在刹车"——实机实证起步红绿齐闪）。释放泄压
            // 只有乘上该轮制动压力(0xF0)才产生实际制动扭矩；0xF0≈0 时
            // 本次执行无制动效果，不算介入。
            if (*(volatile float *) ((uintptr_t) wheel + 0xF0) > 0.01f) {
                g_abs_hit_seq = g_frame_seq;
                g_abs_active = 1;
            }
            return;
        }
    }
}

// 安装 RoadForce 指令拦截器。offset 固定 0x1A7B7DC（8.0.4 专用，与
// TractionFilter 等同受 VersionGate 门控）。失败仅记日志——指示灯失效
// 不影响任何 gameplay 功能。
static void abs_rf_intercept_install(uintptr_t base) {
    uintptr_t target = base + 0x1A7B7DC;
    void *stub = shadowhook_intercept_instr_addr(
            (void *) target, abs_rf_intercept_pre, NULL,
            SHADOWHOOK_INTERCEPT_DEFAULT);
    if (stub == NULL) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_intercept_instr_addr(RoadForce 0x1A7B7DC) failed: %d (%s)",
             err, shadowhook_to_errmsg(err));
        g_abs_rf_intercept_installed = 0;
    } else {
        LOGI("Intercepted RoadForce ABS write at 0x%" PRIxPTR, target);
        g_abs_rf_intercept_installed = 1;
    }
}

// v6 制动压力：0xF0 饱和重映射（2026-08-29 用户终审）。
// 语义：滑条 s 把踏板行程 0-100% 重映射到 0-s·T_b（牛米标尺），任何车速下
// 允许的压力上限**封顶在原生 F_base(v)**——即输出 = min(s·T_b·p, F_base(v))。
// 90% 档例：满标 4050；100 km/h 处 2916/4050 = 72% 行程即达 2916（碰到原生
// 上限封顶），288 km/h 以上永远可到 4500。与 ABS 档位/开关零耦合。
// 落点：RoadForce 读 wheel.brake(0xF0)（p_brake∈[0,1]）计算
// τ = tempBrakeF × p_brake；carController 每帧广播 0xF0 ← actualBrake
//（TECHNICAL_ANALYSIS §2.2.1/§2.2.2）。本函数在 proxy_fixed_update 的 orig
// **之后**调用：此刻 0xF0 = CC 本帧刚分发的原始请求（每帧被重置 → 无复利），
// 写回重映射值，物理步进的 RoadForce 同帧读到。
// 分支：
// - ABS 段（usesABS && spd>0）：0xF0' = min(1, s·T_b·p_raw / F_base(v))。
//   σ≤0.15 直线时 tempBrakeF=F_base → 输出精确 = min(sTb·p, F_base)；
//   打滑 pulse/弯中让渡（tempBrakeF=F_base·Ω·(·b)）的调制按同比例保留。
// - 跳过段（关 ABS/静止）：tempBrakeF=T_b 恒、无速度限压 → 线性
//   0xF0' = p_raw·s，满踩 = s·T_b（"90% 踩不到 4500"由标尺本身体现）。
// F_base 用字段**现值**计算（v6 起模块完全不写 0x88/0x3E4/0x3E8，字段=原生）：
//   fb = p₀ + (2r−r²)(T_b−p₀)，r = clamp01(spd/80)（TECHNICAL_ANALYSIS §2.3.4）。
// p₀ 选择：比较 wheel 指针与 controller.wheelRL(0xB0)/wheelRR(0xB4)（ESC 瞄准
//   的后轮引用，§3.8.2 证据）——后轮取 Rear(0x3E8)、前轮取 Front(0x3E4)。
// ⚠️ 已知边界：ESC 干预（触发时写单侧后轮 0xF0, §3.8）发生在本函数上游，
//   其制动量会一并被重映射（p_raw 读到 ESC 值）；ESC 为游戏辅助且多数用户
//   关闭，影响面接受，实机若发现 ESC 校准异常再作通道隔离（二期）。
static void abs_remap_brake_request(void *this) {
    if (g_config.brake_scale >= 0.9999f || g_config.brake_scale <= 0.0f) {
        // 100%（原生透传）或异常值：不重映射。
        return;
    }
    void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
    if (wheels_arr == NULL) return;

    float spd = *(float *)((uintptr_t)this + 0x84);
    uintptr_t wheel_rl = read_ptr(this, 0xB0);
    uintptr_t wheel_rr = read_ptr(this, 0xB4);

    for (int i = 0; i < 4; i++) {
        void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
        if (wheel == NULL) continue;
        float p_raw = *(volatile float *)((uintptr_t)wheel + 0xF0);
        // CC 本帧已广播 0xF0；读到的即原始请求（含模块 writer 直写的手指值）。
        int is_rear = ((uintptr_t)wheel == wheel_rl || (uintptr_t)wheel == wheel_rr);
        float p0 = *(float *)((uintptr_t)wheel + (is_rear ? 0x3E8 : 0x3E4));
        float tb = *(float *)((uintptr_t)wheel + 0x88);
        unsigned char uses = *(unsigned char *)((uintptr_t)wheel + 0x3CE);
        float new_p;
        if (uses && spd > 0.0f && tb > 0.0f && p0 >= 0.0f && p0 < tb) {
            // ABS 段：封顶 = 原生 F_base(v)。
            float r = spd / 80.0f;
            if (r > 1.0f) r = 1.0f;
            float wgt = 2.0f * r - r * r;
            float fb = p0 + wgt * (tb - p0);
            float target = g_config.brake_scale * tb * p_raw;
            new_p = target / fb;
            if (new_p > 1.0f) new_p = 1.0f;
        } else {
            // 跳过段（关 ABS/静止）：线性缩放、无封顶（tempBrakeF 保持 T_b）。
            new_p = p_raw * g_config.brake_scale;
        }
        *(volatile float *)((uintptr_t)wheel + 0xF0) = new_p;
    }
}

// IRDSCarControllInput::HandleABS() — ABS 入口。
// 签名: void HandleABS(void *this)
// 当模块 ABS 开关关闭时，直接返回（不调 orig），跳过游戏自带 ABS。
// 只作用玩家车（白名单比对；HandleABS 实测为死方法，此 hook 是保险层）。
static void proxy_handle_abs(void *this) {
    if (!g_config.enable_abs && is_target_player_car(this)) {
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
    // ⚠️ 白名单比对（is_target_player_car）：此 hook 对所有车每物理帧触发，
    // 用 is_player_controller 会把 absEnable=false + usesABS=false 写到 AI 车
    // 上（误关 AI 的 ABS），与关 TC 波及 AI 是同一根因。
    if (!g_config.enable_abs && is_target_player_car(this)) {
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
    // TC 档位兜底：g_config={0} 零初始化会把 tc_mix 置 0.0（语义是"关闭"
    // 而非"游戏默认"）——必须在 install 时显式兜底为游戏默认，真实档位随后
    // 经 pedal_set_tc_params 从 Java 下发。tc_eps/tc_minspd=0 = 不覆写字段。
    g_config.tc_mix = 1.0f;
    g_config.tc_eps = 0.0f;
    g_config.tc_minspd = 0.0f;

    // ABS 档位兜底：同 TC 教训。abs_b_override 零初始化为 0.0（语义是
    // "覆写为 0"=游戏原厂泄压，而非"不覆写"）；brake_scale 零初始化语义
    // 歧义——显式兜底为不覆写/不缩放（1.0），真实档位随后经
    // pedal_set_abs_params 从 Java 下发。
    g_config.abs_mix = 1.0f;
    g_config.abs_b_override = -1.0f;
    g_config.brake_scale = 1.0f;

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

    // ABS 介入指示灯：RoadForce 指令级拦截器（游戏原生介入执行点）。
    abs_rf_intercept_install(base);

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
    g_player_controller = NULL;  // 清白名单，防残留旧实例指针误命中新场景的车
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

// TC 档位 setter。低频（仅配置变更/启动时），LOGI 允许；透传路径内严禁日志。
void pedal_set_tc_params(float mix, float eps, float minspd) {
    if (mix < 0.0f) mix = 0.0f;
    if (mix > 1.0f) mix = 1.0f;
    g_config.tc_mix = mix;
    if (eps <= 0.0f || minspd <= 0.0f) {
        // "游戏默认"档：不覆写（置 0 = 不写标志）；残留由 proxy 的
        // once-restore 基线回写清理。两参数视为一体：任一 <=0 都整对禁写。
        g_config.tc_eps = 0.0f;
        g_config.tc_minspd = 0.0f;
    } else {
        // 防御性 clamp：ε=0 会直接踩门控②（TCLSlip≠0）导致 TC 失效；
        // ε≥1 数学上等于永久关闭。minSPD 抬高会让门控①更难穿透，clamp
        // 防止误配成"永不介入"。
        if (eps < 0.01f) eps = 0.01f;
        if (eps > 0.9f) eps = 0.9f;
        if (minspd > 30.0f) minspd = 30.0f;
        g_config.tc_eps = eps;
        g_config.tc_minspd = minspd;
    }
    LOGI("pedal_set_tc_params: mix=%.2f eps=%.3f minspd=%.2f",
         g_config.tc_mix, g_config.tc_eps, g_config.tc_minspd);
}

// ABS 档位 setter。低频（仅配置变更/启动时），LOGI 允许；覆写路径内严禁日志。
// 第三参 v5 起语义为 brake_scale（刹车输入等比缩放，原 T_b 缩放通道退役）。
void pedal_set_abs_params(float mix, float b_override, float brake_scale) {
    if (mix < 0.0f) mix = 0.0f;
    if (mix > 1.0f) mix = 1.0f;
    g_config.abs_mix = mix;
    if (b_override < 0.0f) {
        // "游戏默认"（最高档）/关闭档：不覆写 b；残留由 abs_apply_gear 的
        // once-restore 基线回写清理。
        g_config.abs_b_override = -1.0f;
    } else {
        // 防御性 clamp：上限 0.9（不到 1.0——恒保留泄压相位兜底，不允许
        // "完全锁死自由"，那是关闭档的领域；行业同款：ACC ABS 1 / iRacing
        // Position 1 也不放任持续锁死）。
        if (b_override > 0.9f) b_override = 0.9f;
        g_config.abs_b_override = b_override;
    }
    if (brake_scale < 0.0f) {
        // 负值无效 → 不缩放。
        g_config.brake_scale = 1.0f;
    } else if (brake_scale > 1.0f) {
        g_config.brake_scale = 1.0f;
    } else {
        // 0-1.0 接受（Java 侧已收窄 0.5-1.0：0 表示请求清零，无刹车，不作为
        // 运行域；1.0 = 原生）。输入端缩放，无字段状态、无恢复需求。
        g_config.brake_scale = brake_scale;
    }
    LOGI("pedal_set_abs_params: mix=%.2f bOverride=%.3f brakeScale=%.2f",
         g_config.abs_mix, g_config.abs_b_override, g_config.brake_scale);
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

// ── TC/ABS 介入指示灯信号查询（Java 主线程 JNI 轮询，~60Hz）──
// TC：写点已合成 g_frame_phase，直读。
// ABS：g_abs_active 是"本帧是否介入"的电平——持续介入时逐帧置 1（帧头
// 清零后拦截器又置位），直读会常亮；此处与 g_frame_phase（25Hz，TC 侧
// 每物理帧翻转）合成闪烁——介入期间按 25Hz 方波闪，与 pulse 泄压节奏
// 同源。读侧只读 volatile 标量，无锁。
void pedal_query_tc_abs_indicator(int *tc_active, int *abs_active) {
    *tc_active = g_tc_active;
    // ABS：最近 2 帧内拦截器命中过即介入（容差 2 帧覆盖 RoadForce 与
    // FixedUpdate 的任意先后），叠加 25Hz 相位闪烁。
    long long age = g_frame_seq - g_abs_hit_seq;
    int engaged = (age >= 0 && age <= 2) ? 1 : 0;
    *abs_active = (engaged && g_frame_phase) ? 1 : 0;
}
