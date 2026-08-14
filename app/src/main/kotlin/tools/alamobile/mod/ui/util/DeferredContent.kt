package tools.alamobile.mod.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * 照搬 KernelSU `DeferredContent.kt`：仅在导航过渡动画完成 + 一帧缓冲后才返回 true，
 * 用于延迟 `beyondViewportPageCount` 放开，避免动画期间渲染重内容导致掉帧。
 */
@Composable
fun rememberContentReady(): Boolean {
    val scope = LocalNavAnimatedContentScope.current
    val transitionRunning = scope.transition.isRunning
    val ready = remember { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    return ready.value
}
