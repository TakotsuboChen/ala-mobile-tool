package tools.alamobile.mod.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    actions: ConfigActions
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
            contentPadding = innerPadding,
            overscrollEffect = null
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallTitle(text = "功能开关")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchRow(
                            title = "踏板覆盖",
                            summary = "用悬浮窗踏板替代游戏默认输入",
                            icon = Icons.Rounded.Menu,
                            checked = uiState.enableControlReplacement,
                            onCheckedChange = { actions.onSetControlReplacement(it) }
                        )
                        SwitchRow(
                            title = "自动 DRS",
                            summary = "在 DRS 区域自动开启 DRS",
                            icon = Icons.Rounded.Traffic,
                            checked = uiState.enableAutoDrs,
                            onCheckedChange = { actions.onSetAutoDrs(it) }
                        )
                        SwitchRow(
                            title = "显示悬浮窗",
                            summary = "在游戏中显示踏板和换挡悬浮窗",
                            icon = Icons.Rounded.DisplaySettings,
                            checked = uiState.showOverlay,
                            onCheckedChange = { actions.onSetShowOverlay(it) }
                        )
                        SwitchRow(
                            title = "关闭自动换挡",
                            summary = "禁用车载自动换挡逻辑",
                            icon = Icons.Rounded.Bolt,
                            checked = uiState.disableAutoGear,
                            onCheckedChange = { actions.onSetDisableAutoGear(it) }
                        )
                    }

                    SmallTitle(text = "踏板映射")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SliderRow(
                            title = "死区",
                            summary = "踏板中间过渡区域的无效范围",
                            value = uiState.deadzone,
                            onValueChange = { actions.onSetDeadzone(it) },
                            valueRange = 0f..0.2f,
                            displayFormat = { String.format("%.0f%%", it * 100) },
                            icon = Icons.Rounded.Straighten
                        )
                        SliderRow(
                            title = "过渡点",
                            summary = "油门和刹车的分界位置",
                            value = uiState.transition,
                            onValueChange = { actions.onSetTransition(it) },
                            valueRange = 0.2f..0.8f,
                            displayFormat = { String.format("%.0f%%", it * 100) },
                            icon = Icons.Rounded.SwapVert
                        )
                        OverlayDropdownPreference(
                            title = "响应曲线",
                            summary = "选择油门/刹车的响应方式",
                            items = ModConfig.PedalCurve.entries.map { curveName(it) },
                            selectedIndex = ModConfig.PedalCurve.entries.indexOf(uiState.curve),
                            onSelectedIndexChange = { index ->
                                actions.onSetCurve(ModConfig.PedalCurve.entries[index])
                            },
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp)
                Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
    ModConfig.PedalCurve.LINEAR -> "线性"
    ModConfig.PedalCurve.QUADRATIC -> "二次"
    ModConfig.PedalCurve.EXPONENTIAL -> "指数"
}
