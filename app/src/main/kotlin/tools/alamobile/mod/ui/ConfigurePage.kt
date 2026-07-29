package tools.alamobile.mod.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.config.ModConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ConfigurePage(
    uiState: ConfigUiState,
    onSave: () -> Unit,
    bottomBarHeight: Dp = 0.dp
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "配置",
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            // 底部留出底栏高度，否则最后一个 item 会被 NavigationBar 挡住。
            // 叠在外层 Scaffold 给的 topBar padding 之下，两者互不干扰。
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + bottomBarHeight
            ),
            overscrollEffect = null
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallTitle(
                        text = "功能开关",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchRow(
                            title = "解锁付费内容",
                            summary = "强制解锁 DLC 和 IAP",
                            icon = Icons.Rounded.LockOpen,
                            checked = uiState.enableUnlock.value,
                            onCheckedChange = {
                                uiState.enableUnlock.value = it
                                onSave()
                            }
                        )
                        SwitchRow(
                            title = "自动 DRS（开发中）",
                            summary = "在 DRS 区域自动开启 DRS",
                            icon = Icons.Rounded.Traffic,
                            checked = uiState.enableAutoDrs.value,
                            enabled = false,
                            onCheckedChange = {
                                uiState.enableAutoDrs.value = it
                                onSave()
                            }
                        )
                    }

                    SmallTitle(
                        text = "Overlay 控件",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchRow(
                            title = "显示悬浮窗",
                            summary = "在游戏中显示踏板和换挡悬浮窗",
                            icon = Icons.Rounded.DisplaySettings,
                            checked = uiState.showOverlay.value,
                            onCheckedChange = {
                                uiState.showOverlay.value = it
                                onSave()
                            }
                        )
                        OverlayDropdownPreference(
                            title = "线性踏板",
                            summary = "悬浮窗踏板替代游戏默认输入",
                            items = ModConfig.PedalMode.entries.map { modeName(it) },
                            selectedIndex = ModConfig.PedalMode.entries.indexOf(uiState.pedalMode.value),
                            onSelectedIndexChange = { index ->
                                uiState.pedalMode.value = ModConfig.PedalMode.entries[index]
                                onSave()
                            },
                            insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        )
                        // 死区和过渡点只在单踏板模式下有意义：单踏板上下分区靠
                        // 过渡点分界、靠死区消除中间误触；双踏板各自全行程无需这两项。
                        // 用 AnimatedVisibility 做向下展开/向上收起的优雅过渡，
                        // 切到双踏板/关闭时收起，切回单踏板时弹出。
                        AnimatedVisibility(
                            visible = uiState.pedalMode.value == ModConfig.PedalMode.SINGLE,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                SliderRow(
                                    title = "死区",
                                    summary = "踏板中间过渡区域的无效范围",
                                    value = uiState.deadzone.value,
                                    onValueChange = {
                                        uiState.deadzone.value = it
                                        onSave()
                                    },
                                    valueRange = 0f..0.2f,
                                    displayFormat = { String.format("%.0f%%", it * 100) },
                                    icon = Icons.Rounded.Straighten
                                )
                                SliderRow(
                                    title = "过渡点",
                                    summary = "油门和刹车的分界位置",
                                    value = uiState.transition.value,
                                    onValueChange = {
                                        uiState.transition.value = it
                                        onSave()
                                    },
                                    valueRange = 0.2f..0.8f,
                                    displayFormat = { String.format("%.0f%%", it * 100) },
                                    icon = Icons.Rounded.SwapVert
                                )
                            }
                        }
                        SwitchRow(
                            title = "手动换挡（开发中）",
                            summary = "启用换挡悬浮窗并关闭游戏自动换挡",
                            icon = Icons.Rounded.Bolt,
                            checked = uiState.enableManualShift.value,
                            enabled = false,
                            onCheckedChange = {
                                uiState.enableManualShift.value = it
                                onSave()
                            }
                        )
                    }

                    SmallTitle(
                        text = "响应曲线",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        OverlayDropdownPreference(
                            title = "油门响应曲线",
                            summary = "油门行程到实际输出的映射方式",
                            items = ModConfig.PedalCurve.entries.map { curveName(it) },
                            selectedIndex = ModConfig.PedalCurve.entries.indexOf(uiState.throttleCurve.value),
                            onSelectedIndexChange = { index ->
                                uiState.throttleCurve.value = ModConfig.PedalCurve.entries[index]
                                onSave()
                            },
                            insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        )
                        OverlayDropdownPreference(
                            title = "刹车响应曲线",
                            summary = "刹车行程到实际输出的映射方式",
                            items = ModConfig.PedalCurve.entries.map { curveName(it) },
                            selectedIndex = ModConfig.PedalCurve.entries.indexOf(uiState.brakeCurve.value),
                            onSelectedIndexChange = { index ->
                                uiState.brakeCurve.value = ModConfig.PedalCurve.entries[index]
                                onSave()
                            },
                            insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.38f)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayFormat: (Float) -> String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "$title: ${displayFormat(value)}", fontSize = 15.sp)
                Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun curveName(curve: ModConfig.PedalCurve): String = when (curve) {
    ModConfig.PedalCurve.LINEAR -> "不修改（线性）"
    ModConfig.PedalCurve.EXPONENTIAL -> "拟真（指数）"
}

private fun modeName(mode: ModConfig.PedalMode): String = when (mode) {
    ModConfig.PedalMode.OFF -> "关闭"
    ModConfig.PedalMode.SINGLE -> "单踏板模式"
    ModConfig.PedalMode.DUAL -> "双踏板模式"
}
