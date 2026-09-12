package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.PaddockGuide
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

    // 围场指南自动弹出（用户要求）：仅「已登录 + 落在围场主页（root，非二级页）+
    // 未读过当前版本」时。两个必须的排除：
    //   ① 注册后首次登录会先跳头像上传页（backStack 变深 → navAtRoot=false）→ 不弹；
    //      从头像页返回主页（navAtRoot 复为 true）才弹。
    //   ② needsAvatar 在头像跳转前仍为 true → 直接跳过，杜绝"跳到头像页那一帧
    //      自动弹指南"的时序竞争（guide 效果与 Avatar 跳转效果同批重组，靠此值
    //      稳定排除）。needsAvatar 清 false 后本效果重启，此时 backStack 已变深，
    //      仍被 navAtRoot 挡住；等返回主页才真正弹。
    val context = LocalContext.current
    LaunchedEffect(isCurrentPage, navAtRoot, uiState.loggedIn, uiState.needsAvatar) {
        if (isCurrentPage && navAtRoot && uiState.loggedIn && !uiState.needsAvatar &&
            !PaddockGuide.isSeen(context)
        ) {
            viewModel.setShowGuide(true)
        }
    }

    PaddockPagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}