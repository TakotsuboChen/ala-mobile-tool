package tools.alamobile.mod.ui.screen.configure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.alamobile.mod.ui.navigation3.Navigator
import tools.alamobile.mod.ui.viewmodel.LocalConfigViewModel

/**
 * 照搬 KernelSU `SettingPager` wrapper 模式，唯一差异：VM 由 **Activity 层**创建
 * 并 provide（[LocalConfigViewModel]），而不是本处 `viewModel<ConfigViewModel>()`。
 *
 * ⚠️ 不能用 `viewModel<ConfigViewModel>()`——`ViewModelStoreNavEntryDecorator` 给
 * 每个 NavEntry 独立的 ViewModelStoreOwner，Hub 页与四个二级页会拿到四份独立实例
 * （详见 [LocalConfigViewModel] 的注释：跨页覆盖 + 300ms debounce 写入被取消丢失）。
 */
@Composable
fun ConfigurePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val viewModel = LocalConfigViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ConfigurePagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}

// ── 配置二级页 wrapper（同样读 Activity 层共享的 VM）──

@Composable
fun ConfigureNativeFeaturesPager(bottomInnerPadding: Dp) {
    val viewModel = LocalConfigViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConfigureNativeFeaturesScreen(uiState, viewModel, bottomInnerPadding)
}

@Composable
fun ConfigureOverlayPager(bottomInnerPadding: Dp) {
    val viewModel = LocalConfigViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConfigureOverlayScreen(uiState, viewModel, bottomInnerPadding)
}

@Composable
fun ConfigureCurvesPager(bottomInnerPadding: Dp) {
    val viewModel = LocalConfigViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConfigureCurvesScreen(uiState, viewModel, bottomInnerPadding)
}

@Composable
fun ConfigureMiscPager(bottomInnerPadding: Dp) {
    val viewModel = LocalConfigViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConfigureMiscScreen(uiState, viewModel, bottomInnerPadding)
}
