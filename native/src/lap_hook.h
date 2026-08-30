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

#ifdef __cplusplus
}
#endif

#endif // LAP_HOOK_H