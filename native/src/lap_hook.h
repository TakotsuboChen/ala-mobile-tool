#ifndef LAP_HOOK_H
#define LAP_HOOK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 计时赛有效圈速监听 hook 配置。两个方法偏移都以 libil2cpp.so RVA 传入。
typedef struct {
    // IRDSLevelLoadVariables::Awake() — 捕获 LLV 单例实例（trackToRace
    // 赛道名字符串源，DontDestroyOnLoad 常驻）。每次触发重置会话最佳圈。
    uintptr_t llv_awake_offset;

    // odometerHandler::HandleSectorsTimes(int, float, int, bool, float) —
    // 游戏自己的圈段事件入口，带显式 validLap 参数（切弯/逆行判定的最终
    // 产物，模块不复算）。~3 次/圈，低频，允许打日志。
    uintptr_t odometer_handle_sectors_times_offset;
} lap_hook_config_t;

/// 有效圈上传载荷（一次 order==2 事件的完整快照，Java 轮询取走）。
typedef struct {
    int32_t lap_seq;    ///< 单调递增事件序号（0 = 无待传），Java 去重用
    int32_t gp_index;   ///< 0..15（buildIndex − 2）
    int32_t lap_ms;     ///< 完整圈时（毫秒）
    /// 整圈辅助配置码（0 = 缺失，-1 = 该维**无效**）。见 lap_set_assist_config。
    ///
    /// 三态语义（2026-10-30 起）：
    ///   - `0`（缺失）：根本没拿到配置（旧模块 / native 未就绪 / 该维未推过）。
    ///   - `-1`（无效）：配置拿到了，但**本圈中途变过**，无法确定整圈用的是哪档
    ///     ——不可作为成绩属性采信（改配置即等于改变了这一圈的竞赛条件）。
    ///   - `>= 1`（有效）：整圈全程未变，可安全上报。
    /// 三者必须区分：把"无效"折成"缺失"会让用户看不出是自己中途改了配置
    /// （这正是 2026-10-30 之前的行为，用户实机报障后才改的）。
    int32_t pedal_mode;   ///< 1=off(原生) 2=single 3=dual
    int32_t tc_mode;      ///< 1=default 2=custom
    int32_t tc_strength;  ///< 1=off 2=weak 3=medium 4=strong 5=stock
    int32_t abs_mode;     ///< 1=default 2=custom
    int32_t abs_strength; ///< 1=off 2=weak 3=medium 4=strong 5=stock

    // ── 本圈车型（2026-10-26）──
    // carId = carModifier.carUniqueID（= CarDefinition.carId）；era = carType
    // （0=GroundEffectEra 地效 / 1=DoubleDRSEra 主动空力）。
    // **-1 = 缺失**（探针尚未采到，例如不在赛道内/玩家车未建立）。
    // 服务端只存这两个整数，展示名由 Java 侧 CarCatalog 反查——改译名不必发服务端。
    int32_t car_id;
    int32_t car_era;

    // ── 本圈调教（2026-10-26）──
    // 直接来自 `GlobalVariables.currentCarSetup`（静态字段，零 hook）的原始
    // float。**存的就是 UI 显示值本身**（下压力 65.0 不是 0.65，外倾角 -3.0
    // 度不是弧度）——实机 2026-10-26 Monza 对拍确认，服务端与模块都不做换算。
    //
    // 用 `has_setup` 而非"14 个 NaN 判缺失"：整项缺失是**一个**语义事实
    // （静态字段解析失败 / 该模式不写此字段），用一个标志表达比逐个判 NaN 可靠。
    // has_setup = 0 时全部 14 个值无意义，接收方必须忽略它们。
    int32_t has_setup;
    float setup_front_wing_aero;   ///< 前翼下压力
    float setup_rear_wing_aero;    ///< 后翼下压力
    float setup_front_ride_height; ///< 前底盘高度
    float setup_rear_ride_height;  ///< 后底盘高度
    float setup_brake_bias;        ///< 刹车平衡**前**值；后值 = 100 − 前（UI 的 60/40 是派生量）
    float setup_front_stiffness;   ///< 前悬架刚度
    float setup_rear_stiffness;    ///< 后悬架刚度
    float setup_front_rollbar;     ///< 前防倾杆
    float setup_rear_rollbar;      ///< 后防倾杆
    float setup_front_camber;      ///< 前外倾角（度）
    float setup_rear_camber;       ///< 后外倾角（度）
    float setup_front_toe;         ///< 前束角（度）
    float setup_rear_toe;          ///< 后束角（度）
} lap_upload_t;

bool lap_install_hooks(const lap_hook_config_t *config);

// ── 围场上传通道（S2）──
// 轮询取走未消费的有效圈事件（order==2 边界写入的单槽缓冲）。
// 返回 true 时 *out_gp_index∈[0,15]、*out_lap_ms 为完整圈毫秒；
// Java 消费成功后调 lap_mark_upload_consumed()。lap_seq 用于去重（单调递增）。
//
// out_assist 为 5 个元素的不透明配置码（顺序：pedal / tc_mode / tc_strength /
// abs_mode / abs_strength），由 Java 经 lap_set_assist_config 推入；0 = 该维缺失。
// **全 0 或整圈内配置变动过** 时全填 0（见 lap_set_assist_config 的 epoch 说明）。
// 码→枚举字符串的映射在 Java 侧（ModConfig 是枚举的权威定义），native 只做
// 变更检测与透传，不认识语义。
bool lap_poll_upload(lap_upload_t *out);
void lap_mark_upload_consumed(int32_t lap_seq);

// ─ 整圈辅助配置一致性（2026-09-18，2026-10-30 改逐维）──
// 由 Java 在「启动时 + ConfigReceiver 收到广播时」推入当前配置的 5 个码
// （0 = 缺失/未知）。
//
// **逐维判定**（2026-10-30 起）：只有真正变过的维度才记「无效」（-1），
// 其余维度照常上报。旧实现是"任一维变过 → 五维全 0 = 缺失"，会让用户圈中
// 只改了 ABS、踏板与 TC 也一起丢失并导致整圈被屏蔽（实机报障后改）。
//
// 维度归属（5 码 → 3 逻辑维）：
//   pedal → 踏板维；tc_mode + tc_strength → TC 维；abs_mode + abs_strength → ABS 维。
//   TC/ABS 按**折叠后的生效档位**比对（`default` ≡ `custom/stock`），与展示层
//   服务端 `collapse_mode` 规则一致——否则"切到默认又切回自定义最高"这种计分
//   等价的操作用户会被误标无效。
//
// 实现：逐维维护 epoch；LLV.Awake（新会话=第一圈起点）与每次 order==2（上一圈
// 结束=下一圈起点）时把 epoch 快照到 boundary。圈完成时逐维比对。
// ⚠️ 用 epoch 而非逐维比对：「改了又改回」逐维比对会误判一致，epoch 会抓住。
void lap_set_assist_config(int pedal_mode, int tc_mode, int tc_strength,
                           int abs_mode, int abs_strength);

// ── 本圈模块踏板使用采样（2026-10-25）──
// 每物理帧由 pedal_hook.c 的 proxy_fixed_update 在**玩家车白名单分支内**调用。
// pedal_active = 本帧模块是否正在向游戏写油门/刹车值（pedal_hook 的
// g_throttle_active / g_brake_active）。圈完成时按活跃帧占比决定 pedal_mode
// 是否从设置值降级为 off（原生按键），见 lap_hook.c 的 PEDAL_USAGE_MIN_PCT。
void lap_note_pedal_frame(int pedal_active);

#ifdef __cplusplus
}
#endif

#endif // LAP_HOOK_H