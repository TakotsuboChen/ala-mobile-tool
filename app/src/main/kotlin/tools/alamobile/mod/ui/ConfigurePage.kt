package tools.alamobile.mod.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.DonutSmall
import androidx.compose.material.icons.rounded.Egg
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
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
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "配置",
                    color = barColor,
                    scrollBehavior = scrollBehavior
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
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
                        // 原生牵引力控制（TC）开关。默认开启。借"游戏手柄已连接"
                        // 机制，强制玩家车 tclEnable 生效——只作用玩家车，不破坏
                        // 陀螺仪/触摸转向，也根治 M18 的 AI 误控。
                        SwitchRow(
                            title = "牵引力控制",
                            summary = "启用游戏原生 TC",
                            icon = TcIcon,
                            checked = uiState.enableTc.value,
                            onCheckedChange = {
                                uiState.enableTc.value = it
                                onSave()
                            }
                        )
                        // 原生防抱死（ABS）开关。默认开启。
                        // 暂时注释：ABS hook 未生效（HandleABS 是死代码），待找到正确的 ABS 入口点后恢复。
                        /*
                        SwitchRow(
                            title = "防抱死系统",
                            summary = "启用原生防抱死（ABS）",
                            icon = AbsIcon,
                            checked = uiState.enableAbs.value,
                            onCheckedChange = {
                                uiState.enableAbs.value = it
                                onSave()
                            }
                        )
                        */
                        // 主菜单音乐替换开关：把游戏主菜单音乐替换为 Hans Zimmer - F1。
                        SwitchRow(
                            title = "替换主菜单音乐",
                            summary = "更改为 Hans Zimmer - F1",
                            icon = Icons.Rounded.MusicNote,
                            checked = uiState.enableMusicReplace.value,
                            onCheckedChange = {
                                uiState.enableMusicReplace.value = it
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
                                    imageVector = PedalsIcon,
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
                        // 双踏板模式：油门和刹车是两个独立 view，两指同时按下时
                        // 需要仲裁——刹车值超过此过渡点则刹车优先屏蔽油门，未超过
                        // 且油门>0 则油门优先屏蔽刹车。范围 0..20%，0=几乎不碰刹车
                        // 就让刹车优先（保守），20%=刹车踩过 1/5 行程才接管。
                        AnimatedVisibility(
                            visible = uiState.pedalMode.value == ModConfig.PedalMode.DUAL,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                SliderRow(
                                    title = "刹车过渡点",
                                    summary = "刹车值超过此点则刹车优先，否则油门优先",
                                    value = uiState.brakeTransition.value,
                                    onValueChange = {
                                        uiState.brakeTransition.value = it
                                        onSave()
                                    },
                                    valueRange = 0f..0.2f,
                                    displayFormat = { String.format("%.0f%%", it * 100) },
                                    icon = Icons.Rounded.SwapVert
                                )
                                // 刹车踏板方向反转：DUAL 模式下刹车 view 的红色填充
                                // 默认（关闭）从下往上生长（原行为，手指顶部=满刹车），
                                // 开启后改成从上往下生长（手指底部=满刹车），适配
                                // 用户"从上往下拉"的触感偏好。raw 与 mapped 都反转，
                                // 视觉与游戏内输入同步生效。
                                SwitchRow(
                                    title = "刹车踏板方向反转",
                                    summary = "开启后刹车行程变为由上往下",
                                    icon = Icons.Rounded.Flip,
                                    checked = uiState.brakeInvert.value,
                                    onCheckedChange = {
                                        uiState.brakeInvert.value = it
                                        onSave()
                                    }
                                )
                            }
                        }
                        // 手动换挡开关。
                        // 暂时注释：DoGearShifting hook 导致车出不了 P 房，待重新实现后恢复。
                        /*
                        SwitchRow(
                            title = "手动换挡",
                            summary = "启用换挡悬浮窗并关闭游戏自动换挡",
                            icon = GearboxIcon,
                            checked = uiState.enableManualShift.value,
                            onCheckedChange = {
                                uiState.enableManualShift.value = it
                                onSave()
                            }
                        )
                        */
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
                                    imageVector = BrakeCurveIcon,
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
