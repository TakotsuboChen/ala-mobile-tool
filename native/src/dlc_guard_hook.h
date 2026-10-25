// ═══════════════════════════════════════════════════════════════════════════
// dlc_guard_hook.h — 修复 DLC 更新检查超时卡加载（enableFixDlcStuck）
//
// 游戏启动时卡在 "Synchronizing additional content..." 进度条 10%：
// CarsDLCManager.CheckAndUpdateInstalledDLCs 在 state 0 await
// Addressables.CheckForCatalogUpdates()，而 AsyncOperationHandle 没有超时，
// CDN 不响应就永久挂起；IntroLogoManager.LoadSequence 每帧检查
// CarsDLCManager.IsSynchronizing，只要它还是 true 就永远不加载主场景。
//
// 本模块 hook IntroLogoManager.<LoadSequence>d__14.MoveNext()，检测到
// "进度停在目录检查阶段超过 N 秒"时把 IsSynchronizing 置 0，
// 让游戏自己的循环退出、走 LoadSceneAsync 加载主场景。
//
// 详见 dlc_guard_hook.c 头注释的完整反汇编证据链与副作用说明。
// ═══════════════════════════════════════════════════════════════════════════
#ifndef DLC_GUARD_HOOK_H
#define DLC_GUARD_HOOK_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    /** 修复 DLC 更新检查超时卡加载总开关（配置项 enableFixDlcStuck，默认开）。 */
    bool enable_fix_dlc_stuck;
    /** IntroLogoManager.<LoadSequence>d__14.MoveNext() 的 RVA。 */
    uintptr_t intro_load_sequence_move_next_offset;
} dlc_guard_hook_config_t;

/** 安装 hook。hook 恒装上（开关在回调内判），返回是否成功。 */
bool dlc_guard_install_hooks(const dlc_guard_hook_config_t *config);

/** 卸载 hook。 */
void dlc_guard_uninstall_hooks(void);

/**
 * 运行时开关（不重装 hook）。语义 = 配置项 enableFixDlcStuck 的运行时同步，
 * 配置广播到达后调用，游戏运行中拨开关立即生效。
 */
void dlc_guard_set_active(int active);

#endif // DLC_GUARD_HOOK_H
