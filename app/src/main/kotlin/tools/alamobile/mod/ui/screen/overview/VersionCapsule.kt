package tools.alamobile.mod.ui.screen.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.util.GameVersionStatus
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 胶囊配色对齐激活卡片（OverviewPagerMiuix.kt ActivationCard）：
//   已适配 → 绿（同激活态：暗色 0xFF1A3825，亮色 0xFFDFFAE4）
//   未适配 → 红（同未激活态：暗色 0xFF3D1A1A，亮色 0xFFFAE4E4）
//   未安装 → 黄（浅黄，亮暗色分别取值）

private data class CapsuleStyle(
    val bg: Color,
    val text: Color,
    val iconTint: Color,
)

@Composable
private fun capsuleStyle(status: GameVersionStatus, isDark: Boolean): CapsuleStyle {
    return when (status) {
        is GameVersionStatus.Adapted -> CapsuleStyle(
            bg = if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4),
            text = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface,
            iconTint = Color(0xFF36D167)
        )
        is GameVersionStatus.NotAdapted -> CapsuleStyle(
            bg = if (isDark) Color(0xFF3D1A1A) else Color(0xFFFAE4E4),
            text = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface,
            iconTint = Color(0xFFFF5252)
        )
        GameVersionStatus.NotInstalled -> CapsuleStyle(
            // 浅黄，与激活卡片同族配色风格
            bg = if (isDark) Color(0xFF3D3A1A) else Color(0xFFFAF4D6),
            text = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface,
            iconTint = Color(0xFFFFB300)
        )
    }
}

/**
 * 单个游戏版本检测胶囊。小字，胶囊形（圆角 Row），左侧小图标 + 右侧文字。
 *
 * @param label 前缀，如"官版"或"共存版"
 * @param status 该包名的检测结果
 */
@Composable
fun VersionCapsule(
    label: String,
    status: GameVersionStatus,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val style = capsuleStyle(status, isDark)

    val (iconVector, text) = when (status) {
        is GameVersionStatus.Adapted -> Icons.Rounded.Check to "$label：${status.versionName} 已适配"
        is GameVersionStatus.NotAdapted -> Icons.Rounded.Close to "$label：${status.versionName} 未适配"
        GameVersionStatus.NotInstalled -> Icons.AutoMirrored.Rounded.HelpOutline to "$label：未安装"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(style.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = style.iconTint
        )
        Text(
            text = text,
            fontSize = 11.sp,
            color = style.text
        )
    }
}