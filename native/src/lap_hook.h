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
    /// 整圈辅助配置码（0 = 缺失）。非 0 时表示**整圈全程未变过**，
    /// 可安全作为该圈的成绩属性上报。见 lap_set_assist_config 的 epoch 说明。
    int32_t pedal_mode;   ///< 1=off(原生) 2=single 3=dual
    int32_t tc_mode;      ///< 1=default 2=custom
    int32_t tc_strength;  ///< 1=off 2=weak 3=medium 4=strong 5=stock
    int32_t abs_mode;     ///< 1=default 2=custom
    int32_t abs_strength; ///< 1=off 2=weak 3=medium 4=strong 5=stock
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

// ─ 整圈辅助配置一致性（2026-09-18）──
// 由 Java 在「启动时 + ConfigReceiver 收到广播时」推入当前配置的 5 个码
// （0 = 缺失/未知）。native 只在**整圈全程配置未变过**时才把配置随圈上报——
// 圈中途改过配置的圈记「缺失」（无法证明整圈一致）。
//
// 实现：任何一维与上次不同 → epoch++；LLV.Awake（新会话=第一圈起点）与每次
// order==2（上一圈结束=下一圈起点）时把 epoch 快照到 boundary。圈完成时比对
// epoch == boundary_epoch，不等即全填 0。
// ⚠️ 用 epoch 而非逐维比对：「改了又改回」逐维比对会误判一致，epoch 会抓住。
void lap_set_assist_config(int pedal_mode, int tc_mode, int tc_strength,
                           int abs_mode, int abs_strength);

#ifdef __cplusplus
}
#endif

#endif // LAP_HOOK_H