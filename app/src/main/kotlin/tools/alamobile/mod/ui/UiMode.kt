package tools.alamobile.mod.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 照搬 KernelSU `UiMode.kt`：UI 模式枚举。Ala Mobile 只用 miuix，
 * 但保留 Material 分支以对齐 KernelSU 结构（未来可扩展）。
 */
enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }

        val DEFAULT_VALUE = Miuix.value
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
