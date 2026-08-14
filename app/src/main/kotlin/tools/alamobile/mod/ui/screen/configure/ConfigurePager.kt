package tools.alamobile.mod.ui.screen.configure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.ui.navigation3.Navigator
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel

/**
 * 照搬 KernelSU `SettingPager` wrapper 模式。
 */
@Composable
fun ConfigurePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val viewModel = viewModel<ConfigViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ConfigurePagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}
