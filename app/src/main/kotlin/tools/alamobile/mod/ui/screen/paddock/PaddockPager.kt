package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.ui.viewmodel.PaddockViewModel

/**
 * 围场 pager 页 wrapper（与 SettingsPager 同模式）：
 * 实例化 ViewModel、收集 uiState、dispatch 到 PaddockPagerMiuix。
 */
@Composable
fun PaddockPager(
    bottomInnerPadding: Dp
) {
    val viewModel = viewModel<PaddockViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PaddockPagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}