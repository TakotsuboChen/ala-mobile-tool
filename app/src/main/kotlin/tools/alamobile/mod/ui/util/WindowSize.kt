package tools.alamobile.mod.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * 照搬 KernelSU `WindowSize.kt`：宽度 >= 840dp，或 >= 600dp 且高/宽 < 1.2 时显示分栏。
 */
@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val deviceDensity = LocalResources.current.displayMetrics.density
    val widthDp = windowInfo.containerSize.width / deviceDensity
    val heightDp = windowInfo.containerSize.height / deviceDensity
    return widthDp >= 840f || (widthDp >= 600f && heightDp / widthDp < 1.2f)
}
