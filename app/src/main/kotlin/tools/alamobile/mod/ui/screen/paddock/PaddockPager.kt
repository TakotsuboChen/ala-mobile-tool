package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import tools.alamobile.mod.ui.viewmodel.PaddockViewModel

/**
 * 围场 pager 页 wrapper（与 SettingsPager 同模式）：
 * 实例化 ViewModel、收集 uiState、dispatch 到 PaddockPagerMiuix。
 *
 * 进页自动刷新（2026-09-06 定案）：以「本页落定为当前页 && 导航栈归位（无二级页）」
 * 为键触发 refresh()——覆盖横滑进页、冷启动落在围场页、从排行榜/头像页返回三条路径。
 * backStack 归位条件不可少：否则从排行榜返回时 isCurrentPage 恒 true 不会重新触发。
 */
@Composable
fun PaddockPager(
    isCurrentPage: Boolean,
    bottomInnerPadding: Dp
) {
    val viewModel = viewModel<PaddockViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val navAtRoot = navigator.backStack.size <= 1

    LaunchedEffect(isCurrentPage, navAtRoot) {
        if (isCurrentPage && navAtRoot && uiState.loggedIn) {
            viewModel.refresh()
        }
    }

    // 注册后首次登录（needsAvatar）→ 跳头像上传页
    LaunchedEffect(uiState.loggedIn, uiState.needsAvatar) {
        if (uiState.loggedIn && uiState.needsAvatar) {
            viewModel.markAvatarDone()
            navigator.push(Route.Avatar)
        }
    }

    PaddockPagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}