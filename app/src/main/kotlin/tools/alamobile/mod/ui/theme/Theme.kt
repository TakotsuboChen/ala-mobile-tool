package tools.alamobile.mod.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 照搬 KernelSU `Theme.kt` 的 CompositionLocal 定义。
 * Ala Mobile 暂不实现 MaterialKolor / ColorPalette 切换，只保留 dark/light 跟随系统。
 * LocalColorMode: 0=跟随系统, 1=强制浅色, 2=强制深色。
 */
val LocalColorMode = staticCompositionLocalOf { 0 }

/** 是否启用顶栏/底栏毛玻璃。照搬 KernelSU：默认 false，由 ViewModel 从配置读取后 provide。 */
val LocalEnableBlur = staticCompositionLocalOf { false }

/** 是否启用浮动底栏。照搬 KernelSU。 */
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

/** 浮动底栏是否启用模糊。照搬 KernelSU。 */
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

/** 是否启用底栏角标。照搬 KernelSU。Ala Mobile 无角标需求，默认 false。 */
val LocalEnableNavigationBadge = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}
