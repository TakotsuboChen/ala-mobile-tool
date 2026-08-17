package tools.alamobile.mod.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel

/**
 * 照搬 KernelSU `SettingPager`（SettingsScreen.kt:16）wrapper 模式：
 * 实例化 ViewModel、收集 uiState、LifecycleResumeEffect 触发 refresh、
 * dispatch 到 Miuix composable。
 */
@Composable
fun SettingsPager(
    bottomInnerPadding: Dp
) {
    val viewModel = viewModel<ConfigViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        // Ala Mobile 的 ConfigViewModel 在构造时已 loadSettings，无额外 refresh 需求；
        // 保留 LifecycleResumeEffect 对齐 KernelSU 结构。
        onPauseOrDispose { }
    }

    SettingsPagerMiuix(
        uiState = uiState,
        actions = viewModel,
        bottomInnerPadding = bottomInnerPadding,
    )
}
