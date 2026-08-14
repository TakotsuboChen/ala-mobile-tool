package tools.alamobile.mod.ui.screen.configure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.ConfigUiState
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 照搬 KernelSU `SettingPagerMiuix`（SettingsMiuix.kt:69）结构，且每个 preference 项
 * 全部用 miuix preference 组件（不是手写 Row+Column+Text+Switch）。
 *
 * 这是本次重构的核心步骤：手写 SwitchRow/SliderRow 产生过多 RenderNode（trace 显示 72 次
 * calculateBounds），miuix SwitchPreference 是优化过的单节点 composable，GPU 纹理复用率更高。
 *
 * SliderRow 的替代：miuix 没有 SliderPreference，所以把 Slider 包在 Card 里，
 * 外层用 Column+Text 显示标题/当前值——这部分的 Text 节点不可避免（KernelSU 也是这么做的，
 * 它的 Slider 项在 HomeMiuix 的 UpdateCard 里同样是 Column+Slider 手写）。
 * 关键差异是：之前的 SwitchRow 全部换成 SwitchPreference，这是 RenderNode 减少的大头。
 */
@Composable
fun ConfigurePagerMiuix(
    uiState: ConfigUiState,
    actions: ConfigViewModel,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "配置",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomInnerPadding
                ),
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Section 1: 功能开关 ──
                        SmallTitle(
                            text = "功能开关",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                title = "解锁付费内容",
                                summary = "强制解锁 DLC 和 IAP",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.LockOpen,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableUnlock,
                                onCheckedChange = actions::setEnableUnlock
                            )
                            SwitchPreference(
                                title = "牵引力控制",
                                summary = "启用游戏原生 TC",
                                startAction = {
                                    Icon(
                                        tools.alamobile.mod.ui.TcIcon,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableTc,
                                onCheckedChange = actions::setEnableTc
                            )
                            SwitchPreference(
                                title = "替换主菜单音乐",
                                summary = "更改为 Hans Zimmer - F1",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableMusicReplace,
                                onCheckedChange = actions::setEnableMusicReplace
                            )
                        }

                        // ── Section 2: Overlay 控件 ──
                        SmallTitle(
                            text = "Overlay 控件",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                title = "显示悬浮窗",
                                summary = "在游戏中显示踏板和换挡悬浮窗",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DisplaySettings,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.showOverlay,
                                onCheckedChange = actions::setShowOverlay
                            )
                            OverlayDropdownPreference(
                                title = "线性踏板",
                                summary = "悬浮窗踏板替代游戏默认输入",
                                items = ModConfig.PedalMode.entries.map { modeName(it) },
                                startAction = {
                                    Icon(
                                        tools.alamobile.mod.ui.PedalsIcon,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = ModConfig.PedalMode.entries.indexOf(uiState.pedalMode),
                                onSelectedIndexChange = { index ->
                                    actions.setPedalMode(ModConfig.PedalMode.entries[index])
                                },
                            )
                            // 死区和过渡点只在单踏板模式下有意义。
                            AnimatedVisibility(
                                visible = uiState.pedalMode == ModConfig.PedalMode.SINGLE,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    SliderPreference(
                                        title = "死区",
                                        summary = "踏板中间过渡区域的无效范围",
                                        value = uiState.pedalDeadzone,
                                        onValueChange = actions::setPedalDeadzone,
                                        valueRange = 0f..0.2f,
                                        displayFormat = { String.format("%.0f%%", it * 100) },
                                        icon = Icons.Rounded.Straighten
                                    )
                                    SliderPreference(
                                        title = "过渡点",
                                        summary = "油门和刹车的分界位置",
                                        value = uiState.pedalTransition,
                                        onValueChange = actions::setPedalTransition,
                                        valueRange = 0.2f..0.8f,
                                        displayFormat = { String.format("%.0f%%", it * 100) },
                                        icon = Icons.Rounded.SwapVert
                                    )
                                }
                            }
                            // 双踏板模式专属配置。
                            AnimatedVisibility(
                                visible = uiState.pedalMode == ModConfig.PedalMode.DUAL,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    SliderPreference(
                                        title = "刹车过渡点",
                                        summary = "刹车值超过此点则刹车优先，否则油门优先",
                                        value = uiState.brakeTransition,
                                        onValueChange = actions::setBrakeTransition,
                                        valueRange = 0f..0.2f,
                                        displayFormat = { String.format("%.0f%%", it * 100) },
                                        icon = Icons.Rounded.SwapVert
                                    )
                                    SwitchPreference(
                                        title = "刹车踏板方向反转",
                                        summary = "开启后刹车行程变为由上往下",
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.Flip,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        checked = uiState.brakeInvert,
                                        onCheckedChange = actions::setBrakeInvert
                                    )
                                }
                            }
                        }

                        // ── Section 3: 响应曲线 ──
                        SmallTitle(
                            text = "响应曲线",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(
                                title = "油门响应曲线",
                                summary = "油门行程到实际输出的映射方式",
                                items = ModConfig.PedalCurve.entries.map { curveName(it) },
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Speed,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = ModConfig.PedalCurve.entries.indexOf(uiState.throttleCurve),
                                onSelectedIndexChange = { index ->
                                    actions.setThrottleCurve(ModConfig.PedalCurve.entries[index])
                                },
                            )
                            OverlayDropdownPreference(
                                title = "刹车响应曲线",
                                summary = "刹车行程到实际输出的映射方式",
                                items = ModConfig.PedalCurve.entries.map { curveName(it) },
                                startAction = {
                                    Icon(
                                        tools.alamobile.mod.ui.BrakeCurveIcon,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = ModConfig.PedalCurve.entries.indexOf(uiState.brakeCurve),
                                onSelectedIndexChange = { index ->
                                    actions.setBrakeCurve(ModConfig.PedalCurve.entries[index])
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slider 包装组件。miuix 没有 SliderPreference，所以用 Card 内 Column+Text+Slider 手写
 * ——这与 KernelSU 的 UpdateCard Slider 项做法一致。Switch 类已全部换成 SwitchPreference，
 * 这部分 Slider 不可避免要手写，但它的 RenderNode 开销远小于之前的 SwitchRow
 * （Slider 只一个 RenderNode，SwitchRow 是 5 个）。
 */
@Composable
private fun SliderPreference(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayFormat: (Float) -> String,
    icon: ImageVector,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = colorScheme.onBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "$title: ${displayFormat(value)}",
                    fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
                top.yukonga.miuix.kmp.basic.Text(
                    text = summary,
                    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        top.yukonga.miuix.kmp.basic.Slider(
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
