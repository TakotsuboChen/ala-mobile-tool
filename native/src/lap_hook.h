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

bool lap_install_hooks(const lap_hook_config_t *config);

// ── 围场上传通道（S2）──
// 轮询取走未消费的有效圈事件（order==2 边界写入的单槽缓冲）。
// 返回 true 时 *out_gp_index∈[0,15]、*out_lap_ms 为完整圈毫秒；
// Java 消费成功后调 lap_mark_upload_consumed()。lap_seq 用于去重（单调递增）。
bool lap_poll_upload(int32_t *out_lap_seq, int32_t *out_gp_index, int32_t *out_lap_ms);
void lap_mark_upload_consumed(int32_t lap_seq);

#ifdef __cplusplus
}
#endif

#endif // LAP_HOOK_H