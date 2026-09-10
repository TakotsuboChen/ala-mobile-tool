package tools.alamobile.mod.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 围场登录门控的跨页面状态协调（2026-09-10）。
 *
 * 需求（用户定案 2026-09-10，交互时序）：未登录时弹「登录围场以使用模块」弹窗，
 * **点「去登录」之后才锁死在围场页**直到登录完成——弹窗期间不锁页（与前置弹窗
 * 不打架的关键）。该门控优先级**最低**——低于更新弹窗、用户协议（EULA）、
 * NPatch 激活询问弹窗。这三类弹窗都挂在概览页（OverviewPagerMiuix /
 * ActivationCard），而「去登录」后要把用户锁到围场页——若门控先于它们激活，
 * 用户会被拽走导致那些弹窗「看不见地卡住」。所以概览页流程各方在此埋点汇报
 * 进度，门控弹窗只在 EULA 已同意 + 更新检查已出结果（且无强制更新挡路）+
 * 激活弹窗不在展示时弹出。
 *
 * 各埋点：
 * - [eulaDone]：EULA 已同意（概览页 onAccept / 冷启动已同意初始化）。
 * - [updateCheckDone]：启动自动更新检查已出结果（无论有无更新）。
 * - [updateBlocking]：强制更新弹窗待处理——更新弹窗不可关闭且挡住全部流程，
 *   门控在它消失前不激活（更新优先于登录）。
 * - [activationPending]：NPatch 激活确认弹窗正在展示（用户点「是/否」后消失）。
 *
 * 登录态本身不由这里持有：调用方从 [tools.alamobile.mod.ui.viewmodel.PaddockViewModel]
 * 的 uiState.loggedIn 读取（Activity 作用域单例，围场页登录/登出/401 自动同步），
 * 传给 [canGate]。「点去登录 → 锁页」的状态机在 MainScreen（gateLocked），
 * 不经此对象。
 */
object LoginGateCoordinator {

    /** EULA 已同意。 */
    var eulaDone: Boolean by mutableStateOf(false)

    /** 启动自动更新检查已出结果（首次运行 EULA 未同意时为 false，同意后置位）。 */
    var updateCheckDone: Boolean by mutableStateOf(false)

    /** 强制更新弹窗待处理（更新优先于登录，挡住门控）。 */
    var updateBlocking: Boolean by mutableStateOf(false)

    /** NPatch 激活确认弹窗展示中（激活询问优先于登录，挡住门控）。 */
    var activationPending: Boolean by mutableStateOf(false)

    /**
     * 登录门控是否可激活（锁围场页 + 弹窗）。
     * @param loggedIn 围场登录态（PaddockViewModel.uiState.loggedIn）。
     */
    fun canGate(loggedIn: Boolean): Boolean =
        !loggedIn && eulaDone && updateCheckDone && !updateBlocking && !activationPending
}
