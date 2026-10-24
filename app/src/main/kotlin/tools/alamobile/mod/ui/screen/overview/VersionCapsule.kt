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
import androidx.compose.material.icons.rounded.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.util.GameVersionStatus
import tools.alamobile.mod.util.NPatchManagerStatus
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 胶囊配色对齐激活卡片（OverviewPagerMiuix.kt ActivationCard）：
//   已适配 → 绿（同激活态：暗色 0xFF1A3825，亮色 0xFFDFFAE4）
//   未适配 → 红（同未激活态：暗色 0xFF3D1A1A，亮色 0xFFFAE4E4）
//   未安装 → 黄（浅黄，亮暗色分别取值）
//   打包修订号不符 → 黄（同未安装族；"需更新"非"错误"）

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
        is GameVersionStatus.OutdatedBuild -> CapsuleStyle(
            // 黄（同未安装族）：这是"该更新了"而非"出错了"，用暖色而非红色
            bg = if (isDark) Color(0xFF3D3A1A) else Color(0xFFFAF4D6),
            text = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface,
            iconTint = Color(0xFFFFB300)
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
 * @param label 前缀，如"Ala Mobile Pro"
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
        // 打包修订号不是当前适配值：显示其实际 versionName（如 "8.0.6 Fix 1"），提示需更新。
        is GameVersionStatus.OutdatedBuild -> Icons.Rounded.Update to "$label：${status.versionName} 需更新"
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

/**
 * NPatch 管理器检测胶囊（概览页第二个胶囊，2026-10-25 用户定案）。
 *
 * ## 为什么与 [VersionCapsule] 分开
 *
 * 游戏包版本与 NPatch 版本是**两个独立维度**：包可以是 Fix 2 却配着旧 NPatch，
 * 也可以反过来。合成一个胶囊就丢失了"哪个组件有问题"这个关键信息。
 *
 * ## 判据只看管理器版本
 *
 * 模块进程读不到框架信息（进程级 property，只有游戏进程写得进去），
 * 详见 [checkNPatchManagerStatus]。⚠️ 管理器版本 ≠ 游戏包内框架版本时本胶囊
 * 会显示"已适配"而游戏实际被拦——游戏内循环 Toast 才是权威提示。
 *
 * @param status 管理器检测结果；null = 检测中（不显示，避免闪烁）
 */
@Composable
fun NPatchCapsule(
    status: NPatchManagerStatus?,
    modifier: Modifier = Modifier,
) {
    if (status == null) return
    val isDark = isSystemInDarkTheme()

    // 配色与 [VersionCapsule] 同族：已适配绿 / 需更新暖黄（"该更新了"不是"出错了"）
    val greenBg = if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    val yellowBg = if (isDark) Color(0xFF3D3A1A) else Color(0xFFFAF4D6)
    val textColor = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface

    val (iconVector, text, bg, tint) = when (status) {
        is NPatchManagerStatus.Adapted ->
            CapsuleContent(Icons.Rounded.Check, "NPatch：${status.versionCode} 已适配",
                greenBg, Color(0xFF36D167))
        is NPatchManagerStatus.Outdated ->
            CapsuleContent(Icons.Rounded.Update, "NPatch：${status.versionCode} 需更新",
                yellowBg, Color(0xFFFFB300))
        NPatchManagerStatus.Missing ->
            CapsuleContent(Icons.AutoMirrored.Rounded.HelpOutline, "NPatch：未安装",
                yellowBg, Color(0xFFFFB300))
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint
        )
        Text(text = text, fontSize = 11.sp, color = textColor)
    }
}

/** [NPatchCapsule] 的四个视觉要素打包，避免 when 分支里写四个平行变量。 */
private data class CapsuleContent(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val bg: Color,
    val tint: Color,
)