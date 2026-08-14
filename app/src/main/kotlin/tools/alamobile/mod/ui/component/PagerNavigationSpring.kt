package tools.alamobile.mod.ui.component

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 照搬 KernelSU `PagerNavigationSpring.kt`：pager tab 切换共用的 spring 规格。
 */
internal val PagerNavigationSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
    visibilityThreshold = 0.5f,
)
