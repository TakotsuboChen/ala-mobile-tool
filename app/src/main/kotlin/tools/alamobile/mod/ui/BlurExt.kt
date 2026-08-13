package tools.alamobile.mod.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 是否启用顶栏/底栏毛玻璃。照搬 KernelSU：true 时在支持 RenderEffect 的设备
 * （Android 12+）启用 blur，不支持时自动降级为纯 surface 色。
 */
val LocalEnableBlur = staticCompositionLocalOf { true }

/**
 * 照搬 KernelSU `rememberBlurBackdrop`：创建内容 backdrop（捕获 surface + 内容），
 * 供 [BlurredBar] 做背景模糊。不支持 blur 时返回 null。
 */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 照搬 KernelSU `BlurredBar`（BlurExt.kt:25-49）：把 [content] 包在 [backdrop]
 * 的毛玻璃层上。blur 半径与 KernelSU 一致（25f）。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.87f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
