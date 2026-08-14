package tools.alamobile.mod.ui.screen.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.ui.navigation3.Navigator
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel

/**
 * 照搬 KernelSU `HomePager`（HomeScreen.kt:32）wrapper 模式：
 * 实例化 ViewModel、收集 uiState、构造 actions、dispatch 到 Miuix composable。
 *
 * Ala Mobile 只用 miuix（无 Material 分支），但保留 wrapper 层级对齐 KernelSU。
 */
@Composable
fun OverviewPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val viewModel = viewModel<ConfigViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasActivated by remember { mutableStateOf(false) }
    if (isCurrentPage) hasActivated = true

    if (hasActivated) {
        LaunchedEffect(Unit) {
            // 触发首次加载（ConfigViewModel 在构造时已 loadSettings，这里保留对齐结构）
        }
    }

    OverviewPagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}
