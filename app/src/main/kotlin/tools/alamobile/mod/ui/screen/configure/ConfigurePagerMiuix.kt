package tools.alamobile.mod.ui.screen.configure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.ConfigUiState
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
    // ABS 自定义切换警示弹窗：每次从默认切到自定义时弹（不是只弹首次）。
    var showAbsWarnDialog by remember { mutableStateOf(false) }

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
                // 底部额外留出底栏高度，避免底栏挡住页面底部内容。
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomInnerPadding,
                    start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                    end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                ),
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Section 1: 游戏原生特性控制 ──
                        SmallTitle(
                            text = "游戏原生特性控制",
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
                                title = "隐藏油门和刹车按键",
                                summary = "隐藏原生油门和刹车，保留离合",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.VisibilityOff,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.hideGamePedals,
                                onCheckedChange = actions::setHideGamePedals
                            )
                            // TC 调节：游戏设置没有任何 TC 参数可调（仅手柄生效的
                            // 开关且被游戏每帧覆写），模块档位是移动端唯一调节途径。
                            // 游戏默认 = 纯透传；自定义展开强度/时机两个滑条。
                            // 分隔线成组逻辑：游戏默认（子卡片全收）时 TC 行与前后
                            // 行无分隔线，完全融入卡片；自定义时整块上下各一条线
                            //（下线在 Column 内部，跟随内层收回动画——TC 关闭时
                            // 贴削减强度下边缘，不关时贴介入时机下边缘）。
                            AnimatedVisibility(
                                visible = uiState.tcMode == ModConfig.TcMode.CUSTOM,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            OverlayDropdownPreference(
                                title = "牵引力控制",
                                summary = "调整游戏原生 TC",
                                items = ModConfig.TcMode.entries.map { tcModeName(it) },
                                startAction = {
                                    Icon(
                                        tools.alamobile.mod.ui.TcIcon,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = ModConfig.TcMode.entries.indexOf(uiState.tcMode),
                                onSelectedIndexChange = { index ->
                                    actions.setTcMode(ModConfig.TcMode.entries[index])
                                },
                            )
                            AnimatedVisibility(
                                visible = uiState.tcMode == ModConfig.TcMode.CUSTOM,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    // 强度档 = TractionFilter 返回值插值 mix
                                    //（等价于把游戏削减系数 0.85 缩放为 0.85×mix）。
                                    SliderPreference(
                                        title = "削减强度",
                                        summary = "修改 TractionFilter 返回值",
                                        value = ModConfig.TcStrength.entries.indexOf(uiState.tcStrength).toFloat(),
                                        onValueChange = { v ->
                                            actions.setTcStrength(
                                                ModConfig.TcStrength.entries[
                                                    v.roundToInt().coerceIn(0, ModConfig.TcStrength.entries.lastIndex)
                                                ]
                                            )
                                        },
                                        valueRange = 0f..(ModConfig.TcStrength.entries.lastIndex).toFloat(),
                                        displayFormat = { v ->
                                            tcStrengthName(
                                                ModConfig.TcStrength.entries[
                                                    v.roundToInt().coerceIn(0, ModConfig.TcStrength.entries.lastIndex)
                                                ]
                                            )
                                        },
                                        icon = Icons.Rounded.Tune
                                    )
                                    // 强度=关闭时 TC 整体不介入，时机无意义 → 卡片收回，
                                    // 走既有 enableTc=false 的 return accel 关闭路径。
                                    AnimatedVisibility(
                                        visible = uiState.tcStrength != ModConfig.TcStrength.OFF,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        SliderPreference(
                                            title = "介入时机",
                                            summary = "修改介入的滑移指标条件",
                                            value = ModConfig.TcTiming.entries.indexOf(uiState.tcTiming).toFloat(),
                                            onValueChange = { v ->
                                                actions.setTcTiming(
                                                    ModConfig.TcTiming.entries[
                                                        v.roundToInt().coerceIn(0, ModConfig.TcTiming.entries.lastIndex)
                                                    ]
                                                )
                                            },
                                            valueRange = 0f..(ModConfig.TcTiming.entries.lastIndex).toFloat(),
                                            displayFormat = { v ->
                                                tcTimingName(
                                                    ModConfig.TcTiming.entries[
                                                        v.roundToInt().coerceIn(0, ModConfig.TcTiming.entries.lastIndex)
                                                    ]
                                                )
                                            },
                                            icon = Icons.Rounded.Bolt
                                        )
                                    }
                                    // TC 区域下分隔线：放 Column 末尾使其跟随内层
                                    // 收回动画，下边缘自动贴住最后可见项。
                                    top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            // ABS 调节：游戏默认 ABS 方波泄压（b=0）过度保护——
                            // 全段几乎不锁死；且制动基数病态地强（关 ABS 100% 重刹
                            // 秒锁死）。游戏默认 = 纯透传；自定义展开干预强度滑条。
                            // "最大制动压力"是 ABS 下方独立项：制动基数修复与 ABS
                            // 模式正交（默认/关闭档下也生效）。分隔线成组逻辑同 TC 区，
                            // 上分隔线与 TC 区下分隔线条件互斥（TC 自定义时其内层
                            // Column 末尾已有下线，两条背靠背会叠成一条粗线）。
                            AnimatedVisibility(
                                visible = uiState.absMode == ModConfig.AbsMode.CUSTOM &&
                                    uiState.tcMode != ModConfig.TcMode.CUSTOM,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            OverlayDropdownPreference(
                                title = "防抱死制动系统",
                                summary = "调整游戏原生 ABS",
                                items = ModConfig.AbsMode.entries.map { absModeName(it) },
                                startAction = {
                                    Icon(
                                        tools.alamobile.mod.ui.AbsIcon,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                selectedIndex = ModConfig.AbsMode.entries.indexOf(uiState.absMode),
                                onSelectedIndexChange = { index ->
                                    val newMode = ModConfig.AbsMode.entries[index]
                                    // 每次从默认切到自定义：弹警示——减弱干预后重刹
                                    // 易锁死，提醒配合下调最大制动压力。
                                    if (uiState.absMode == ModConfig.AbsMode.DEFAULT &&
                                        newMode == ModConfig.AbsMode.CUSTOM
                                    ) {
                                        showAbsWarnDialog = true
                                    }
                                    actions.setAbsMode(newMode)
                                },
                            )
                            AnimatedVisibility(
                                visible = uiState.absMode == ModConfig.AbsMode.CUSTOM,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    // 干预强度 = pulse 释放深度 b(0x3E0) 覆写：
                                    // 游戏默认配平下 b=0，pulse 帧完全泄压，方波
                                    // [F_base·Ω, 0] 平均 0.5；抬 b 抬方波平均 (1+b)/2。
                                    SliderPreference(
                                        title = "干预强度",
                                        summary = "修改干预制动偏置",
                                        value = ModConfig.AbsStrength.entries.indexOf(uiState.absStrength).toFloat(),
                                        onValueChange = { v ->
                                            actions.setAbsStrength(
                                                ModConfig.AbsStrength.entries[
                                                    v.roundToInt().coerceIn(0, ModConfig.AbsStrength.entries.lastIndex)
                                                ]
                                            )
                                        },
                                        valueRange = 0f..(ModConfig.AbsStrength.entries.lastIndex).toFloat(),
                                        displayFormat = { v ->
                                            absStrengthName(
                                                ModConfig.AbsStrength.entries[
                                                    v.roundToInt().coerceIn(0, ModConfig.AbsStrength.entries.lastIndex)
                                                ]
                                            )
                                        },
                                        icon = Icons.Rounded.Tune
                                    )
                                    // ABS 区域下分隔线：放 Column 末尾跟随收回
                                    // 动画，下边缘自动贴住最后可见项（同 TC 区）。
                                    top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            // 最大制动压力：T_b(0x88) 等比缩放（非截断——踏板响应
                            // 曲线与纵轴完全不受影响，全链一致缩放）。0-100% 无级
                            //（0% 为观察极端：高速段 F_base→0，制动几乎消失）。
                            SliderPreference(
                                title = "最大制动压力",
                                summary = "调整游戏制动摩擦扭矩上限",
                                value = uiState.absPressure,
                                onValueChange = { v ->
                                    actions.setAbsPressure((v * 100).roundToInt() / 100f)
                                },
                                valueRange = 0f..1.0f,
                                displayFormat = { v -> "${(v * 100).roundToInt()}%" },
                                icon = tools.alamobile.mod.ui.BrakeCurveIcon
                            )
                        }

                        // ── Section 2: Overlay 控件 ──
                        SmallTitle(
                            text = "Overlay 控件",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(
                                title = "线性踏板",
                                summary = "悬浮窗踏板覆盖游戏输入",
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
                                    OverlayDropdownPreference(
                                        title = "踏板优先级",
                                        summary = "双踏板同时按下时的优先策略",
                                        items = ModConfig.PedalPriority.entries.map { priorityName(it) },
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.PriorityHigh,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        selectedIndex = ModConfig.PedalPriority.entries.indexOf(uiState.pedalPriority),
                                        onSelectedIndexChange = { index ->
                                            actions.setPedalPriority(ModConfig.PedalPriority.entries[index])
                                        },
                                    )
                                    // 根据油门数值判断 → 显示油门过渡点滑块
                                    AnimatedVisibility(
                                        visible = uiState.pedalPriority == ModConfig.PedalPriority.THROTTLE_VALUE,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        SliderPreference(
                                            title = "油门过渡点",
                                            summary = "油门值超过此点则油门优先，否则刹车优先",
                                            value = uiState.throttleTransition,
                                            onValueChange = actions::setThrottleTransition,
                                            valueRange = 0.01f..0.99f,
                                            displayFormat = { String.format("%.0f%%", it * 100) },
                                            icon = Icons.Rounded.SwapVert
                                        )
                                    }
                                    // 根据刹车数值判断 → 显示刹车过渡点滑块
                                    AnimatedVisibility(
                                        visible = uiState.pedalPriority == ModConfig.PedalPriority.BRAKE_VALUE,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        SliderPreference(
                                            title = "刹车过渡点",
                                            summary = "刹车值超过此点则刹车优先，否则油门优先",
                                            value = uiState.brakeTransition,
                                            onValueChange = actions::setBrakeTransition,
                                            valueRange = 0.01f..0.99f,
                                            displayFormat = { String.format("%.0f%%", it * 100) },
                                            icon = Icons.Rounded.SwapVert
                                        )
                                    }
                                    OverlayDropdownPreference(
                                        title = "踏板方向反转",
                                        summary = "反转踏板的行程填充方向",
                                        items = ModConfig.PedalInvert.entries.map { invertName(it) },
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.Flip,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        selectedIndex = ModConfig.PedalInvert.entries.indexOf(uiState.pedalInvert),
                                        onSelectedIndexChange = { index ->
                                            actions.setPedalInvert(ModConfig.PedalInvert.entries[index])
                                        },
                                    )
                                }
                            }

                            // ── Overlay 视觉属性（所有踏板模式通用）──
                            SliderPreference(
                                title = "控件透明度",
                                summary = "整个控件的透明度",
                                value = uiState.overlayAlpha,
                                onValueChange = actions::setOverlayAlpha,
                                valueRange = 0f..1f,
                                displayFormat = { String.format("%.0f%%", it * 100) },
                                icon = Icons.Rounded.Opacity
                            )
                            SliderPreference(
                                title = "边框粗细",
                                summary = "控件边框宽度，0 表示不显示边框",
                                value = uiState.overlayBorderWidth,
                                onValueChange = actions::setOverlayBorderWidth,
                                valueRange = 0f..10f,
                                displayFormat = { String.format("%.1f dp", it) },
                                icon = Icons.Rounded.BorderOuter
                            )
                            SliderPreference(
                                title = "边框圆角",
                                summary = "圆角半径 = 比例 × 短边/2，0% 时为直角",
                                value = uiState.overlayCornerRadius,
                                onValueChange = actions::setOverlayCornerRadius,
                                valueRange = 0f..1f,
                                displayFormat = { String.format("%.0f%%", it * 100) },
                                icon = Icons.Rounded.RoundedCorner
                            )
                        }

                        // ── Section 3: 响应曲线 ──
                        // 线性踏板关闭（OFF）时整个响应曲线 Section 也收回——
                        // 没有踏板就没有曲线可调。
                        AnimatedVisibility(
                            visible = uiState.pedalMode != ModConfig.PedalMode.OFF,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                        SmallTitle(
                            text = "响应曲线",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(
                                title = "油门响应曲线",
                                summary = "油门踏板控件行程到游戏原生油门的映射方式",
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
                            AnimatedVisibility(
                                visible = uiState.throttleCurve == ModConfig.PedalCurve.CUSTOM,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    // 油门响应曲线 与 图表 之间的分隔线。
                                    top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    CurveEditor(
                                        points = uiState.throttleCurvePoints,
                                        onPointsChange = actions::setThrottleCurvePoints,
                                    )
                                    // 图表 与 刹车响应曲线 之间的分隔线。
                                    top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            OverlayDropdownPreference(
                                title = "刹车响应曲线",
                                summary = "刹车踏板控件行程到游戏原生刹车的映射方式",
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
                            AnimatedVisibility(
                                visible = uiState.brakeCurve == ModConfig.PedalCurve.CUSTOM,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    // 刹车响应曲线 与 图表 之间的分隔线。
                                    top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    CurveEditor(
                                        points = uiState.brakeCurvePoints,
                                        onPointsChange = actions::setBrakeCurvePoints,
                                    )
                                }
                            }
                        }
                            }
                        }

                        // ── Section 4: 杂项 ──
                        SmallTitle(
                            text = "杂项",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
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
                            SwitchPreference(
                                title = "替换开场动画背景音",
                                summary = "更改为 V10 引擎声浪",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableV10Sound,
                                onCheckedChange = actions::setEnableV10Sound
                            )
                        }
                    }
                }
            }
        }
    }

    // ABS 自定义切换警示（每次从默认切到自定义时弹出）。标题居中（OverlayDialog
    // 自带）、正文左对齐、单按钮"我已了解"。文案说明制动基数与锁死风险的
    // 因果关系——减弱干预档 + 原厂病态强制动基数 = 重刹严重锁死。
    // 常驻组合树 + show 驱动进出动画（与 SupportDialog/EulaDialog 同模式）：
    // 不能用 if 条件挂载——关闭时组件被直接移出组合树，退出动画没有机会播放
    //（表现为闪现消失）。
    OverlayDialog(
        show = showAbsWarnDialog,
        title = "⚠️建议调整最大制动压力",
        onDismissRequest = { showAbsWarnDialog = false },
        onDismissFinished = { },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "由于 Ala Mobile 的制动摩擦扭矩上限显著超出最大垂直载荷下的抓地力极限，" +
                        "若调低 ABS 档位，重刹可能将会出现严重锁死情况，" +
                        "强烈建议您适当下调最大制动压力",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
                TextButton(
                    text = "我已了解",
                    onClick = { showAbsWarnDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
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
                // 标题/描述样式对齐 miuix BasicComponent 标准（headline1 17sp +
                // Medium / body2 14sp）——与 SwitchPreference/OverlayDropdownPreference
                // 等 miuix preference 组件完全一致，不靠字号字重区分层级。
                top.yukonga.miuix.kmp.basic.Text(
                    text = "$title：${displayFormat(value)}",
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                top.yukonga.miuix.kmp.basic.Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
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
    ModConfig.PedalCurve.CUSTOM -> "自定义"
}

private fun tcModeName(mode: ModConfig.TcMode): String = when (mode) {
    ModConfig.TcMode.DEFAULT -> "默认"
    ModConfig.TcMode.CUSTOM -> "自定义"
}

private fun tcStrengthName(strength: ModConfig.TcStrength): String = when (strength) {
    ModConfig.TcStrength.OFF -> "关闭 TC"
    ModConfig.TcStrength.WEAK -> "低"
    ModConfig.TcStrength.MEDIUM -> "中"
    ModConfig.TcStrength.STRONG -> "高"
    ModConfig.TcStrength.STOCK -> "最高（默认）"
}

private fun tcTimingName(timing: ModConfig.TcTiming): String = when (timing) {
    ModConfig.TcTiming.DEFAULT -> "较晚（默认）"
    ModConfig.TcTiming.EARLIER -> "较早"
    ModConfig.TcTiming.VERY_EARLY -> "非常早"
    ModConfig.TcTiming.REALTIME -> "实时"
}

private fun absModeName(mode: ModConfig.AbsMode): String = when (mode) {
    ModConfig.AbsMode.DEFAULT -> "默认"
    ModConfig.AbsMode.CUSTOM -> "自定义"
}

private fun absStrengthName(strength: ModConfig.AbsStrength): String = when (strength) {
    ModConfig.AbsStrength.OFF -> "关闭 ABS"
    ModConfig.AbsStrength.WEAK -> "低"
    ModConfig.AbsStrength.MEDIUM -> "中"
    ModConfig.AbsStrength.STRONG -> "高"
    ModConfig.AbsStrength.STOCK -> "最高（默认）"
}

/**
 * 自定义响应曲线编辑器：多控制点曲线图。
 *
 * 默认无控制点 = 线性（只有两端点 (0,0)、(1,1)）。交互：
 * - 单击图表空白处 → 添加控制点
 * - 单击控制点 → 删除该点
 * - 长按控制点 → 拖拽移动
 * 曲线是过 (0,0)、各控制点、(1,1) 的分段线性插值。
 *
 * 坐标约定：Canvas 原点在左上、y 向下。曲线图把"行程 x"映射到"输出 y"，
 * 视觉上 x 轴向右、y 轴向上（输出越大越高），所以绘制时 y 取反。
 */
@Composable
private fun CurveEditor(
    points: List<ModConfig.CurvePoint>,
    onPointsChange: (List<ModConfig.CurvePoint>) -> Unit,
) {
    // 拖拽期间用本地 state 跟手，松手后才 by onPointsChange 落盘。
    // 直接写 ViewModel 会触发 300ms debounce 的 ModConfig.write（Binder IPC + 文件写），
    // 拖拽高频触发会卡顿；本地 state 只重绘 Canvas。
    var localPoints by remember { mutableStateOf(points) }
    // 手指按下时，是否已命中某个控制点（用于拖拽）；未命中则可能是"单击添加/删除"。
    var draggingIndex by remember { mutableStateOf(-1) }
    // 手指按下位置（用于区分单击 vs 长按拖拽）。
    var pressDown by remember { mutableStateOf<Offset?>(null) }
    // 正在拖拽的点，用于绘制引线+坐标数值。
    var activePoint by remember { mutableStateOf<ModConfig.CurvePoint?>(null) }

    // 控制点列表变化时同步本地 state（外部重置/加载配置时）。
    androidx.compose.runtime.LaunchedEffect(points) {
        localPoints = points
    }

    val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)
    val diagColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
    val curveColor = MiuixTheme.colorScheme.primary
    val surfaceColor = MiuixTheme.colorScheme.surface
    val axisColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
    val textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        top.yukonga.miuix.kmp.basic.Text(
            text = "单击添加/删除控制点，长按拖拽调整曲线形状",
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = textColor
        )
        // 图表容器：ChartCanvas 占一个正方形（含留白），外面用 Box 包住并在
        // 四周标注"行程/输出"文字，不随拖拽刷新。
        // 在平板/横屏上父容器宽度可能很大，正方形边长 = 宽度会撑爆屏幕一半。
        // 这里加 heightIn(max = 屏幕高度 50%)：aspectRatio(1f) 会让边长取
        // min(可用宽度, 高度上限)，宽屏时边长 = 屏幕高度 50%，仍是正方形、
        // 居中显示；内部文字/线宽/padding 全部保持原 dp 不缩放。
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            // 坐标轴标签（绘制在 Canvas 上，避免额外节点）。
            ChartCanvas(
                modifier = Modifier
                    .heightIn(max = screenHeightDp * 0.5f)
                    .aspectRatio(1f)
                    .pointerInput(localPoints) {
                        // pointerInput scope 里 this.size 是 IntSize，转成 Float Size。
                        val floatSize = Size(size.width.toFloat(), size.height.toFloat())
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                // 与 ChartCanvas 的 pad 保持一致（36dp），命中检测和坐标换算才对齐。
                                val padPx = 36.dp.toPx()
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.changedToDownIgnoreConsumed()) {
                                    // 命中检测：靠近某控制点 → 记下 index（供单击删除/长按拖拽）。
                                    // 未命中（空白区）→ 不消费，让父级 LazyColumn/HorizontalPager 正常滚动。
                                    val hit = hitControlPoint(change.position, localPoints, floatSize, padPx, 24.dp.toPx())
                                    draggingIndex = hit
                                    // 无论命中与否都记录按下位置，用于抬起时区分"单击"vs"拖动（页面滚动）"。
                                    pressDown = change.position
                                    if (hit >= 0) {
                                        // 命中控制点：消费按下，独占手势（拖拽/删除）。
                                        change.consume()
                                    }
                                }
                                if (change.pressed) {
                                    if (draggingIndex >= 0) {
                                        // 长按拖拽移动中：消费位移（阻止页面滚动），更新该点坐标。
                                        change.consume()
                                        localPoints = movePoint(localPoints, draggingIndex, change.position, floatSize, padPx)
                                        if (draggingIndex < localPoints.size) {
                                            activePoint = localPoints[draggingIndex]
                                        }
                                    }
                                    // 空白区：不消费，页面可正常滚动。
                                } else {
                                    // 手指抬起。
                                    val down = pressDown
                                    val moved = down != null &&
                                        (change.position - down).getDistance() > 12.dp.toPx()
                                    if (draggingIndex >= 0) {
                                        if (!moved) {
                                            // 单击控制点 → 删除。
                                            localPoints = removePoint(localPoints, draggingIndex)
                                        }
                                        onPointsChange(localPoints)
                                    } else if (!moved) {
                                        // 空白区单击（无位移）→ 添加；拖动（页面滚动）不添加。
                                        localPoints = addPoint(localPoints, change.position, floatSize, padPx)
                                        onPointsChange(localPoints)
                                    }
                                    activePoint = null
                                    draggingIndex = -1
                                    pressDown = null
                                }
                            }
                        }
                    },
                localPoints = localPoints,
                activePoint = activePoint,
                gridColor = gridColor,
                diagColor = diagColor,
                curveColor = curveColor,
                surfaceColor = surfaceColor,
                axisColor = axisColor,
                textColor = textColor,
            )
        }
    }
}

/** 曲线图本体：网格 + 对角线 + 曲线 + 控制点 + 引线/数值 + 轴标签。 */
@Composable
private fun ChartCanvas(
    modifier: Modifier,
    localPoints: List<ModConfig.CurvePoint>,
    activePoint: ModConfig.CurvePoint?,
    gridColor: Color,
    diagColor: Color,
    curveColor: Color,
    surfaceColor: Color,
    axisColor: Color,
    textColor: Color,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 加大留白，让"输出/行程"标签和坐标数值能画在图表外、Canvas 内，不被裁剪。
        val pad = 36.dp.toPx()
        val plotLeft = pad
        val plotRight = w - pad
        val plotTop = pad
        val plotBottom = h - pad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        // 辅助函数：归一化 (0..1) → 像素坐标。
        fun px(x: Float) = plotLeft + plotW * x.coerceIn(0f, 1f)
        fun py(y: Float) = plotBottom - plotH * y.coerceIn(0f, 1f)

        // 网格背景。
        for (i in 1..4) {
            val fx = plotLeft + plotW * i / 4f
            drawLine(gridColor, Offset(fx, plotTop), Offset(fx, plotBottom), strokeWidth = 1f)
        }
        for (i in 1..4) {
            val fy = plotTop + plotH * i / 4f
            drawLine(gridColor, Offset(plotLeft, fy), Offset(plotRight, fy), strokeWidth = 1f)
        }

        // 对角线（线性参考）。
        drawLine(
            diagColor,
            Offset(plotLeft, plotBottom),
            Offset(plotRight, plotTop),
            strokeWidth = 1f
        )

        // 坐标轴边框（四边：底、左、右、上）。
        drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1f)
        drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), strokeWidth = 1f)
        drawLine(axisColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), strokeWidth = 1f)
        drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotRight, plotTop), strokeWidth = 1f)

        // 轴标签：沿对角线从角延伸出去，竖直/水平都不与图表重叠。
        // "输出"在左上角外（左上方），"行程"在右下角外（右下方）。
        drawText("输出", Offset(plotLeft - 30.dp.toPx(), plotTop - 20.dp.toPx()), textColor, 11.dp.toPx())
        drawText("行程", Offset(plotRight + 4.dp.toPx(), plotBottom + 8.dp.toPx()), textColor, 11.dp.toPx())

        // 曲线：保单调三次样条（Fritsch–Carlson），过 (0,0)、各控制点、(1,1)。
        // 均匀采样 + 控制点 x 位置，确保曲线精确经过每个控制点圆心
        // （极端曲线下均匀网格采样点可能跳过控制点 x，导致附近线段偏离圆心）。
        val points = localPoints.sortedBy { it.x }
        val sampleXs = ((0..128).map { it / 128f } + points.map { it.x }).sorted()
        val curvePath = Path().apply {
            var prevX = -1f
            var first = true
            for (x in sampleXs) {
                if (x - prevX < 0.0001f) continue // 去重，避免零长度线段
                prevX = x
                val y = ModConfig.monotoneCubic(points, x)
                val pxv = px(x)
                val pyv = py(y)
                if (first) { moveTo(pxv, pyv); first = false } else lineTo(pxv, pyv)
            }
        }
        drawPath(curvePath, curveColor, style = Stroke(width = 2.dp.toPx()))

        // 引线 + 坐标数值（仅当有点被按下/拖拽时显示）。
        val active = activePoint
        if (active != null) {
            // 水平引线到 Y 轴、垂直引线到 X 轴。
            drawLine(
                axisColor,
                Offset(plotLeft, py(active.y)),
                Offset(px(active.x), py(active.y)),
                strokeWidth = 1f
            )
            drawLine(
                axisColor,
                Offset(px(active.x), py(active.y)),
                Offset(px(active.x), plotBottom),
                strokeWidth = 1f
            )
            // 坐标数值：
            // - Y 轴数值：在 Y 轴左边、不碰 Y 轴线，中心高度对齐 Y 坐标（与"输出"同列）。
            // - X 轴数值：在 X 轴正下方、居中，与"行程"同行。
            drawText(
                "${(active.y * 100).toInt()}%",
                Offset(plotLeft - 32.dp.toPx(), py(active.y) - 8.dp.toPx()),
                textColor,
                11.dp.toPx()
            )
            drawText(
                "${(active.x * 100).toInt()}%",
                Offset(px(active.x) - 16.dp.toPx(), plotBottom + 8.dp.toPx()),
                textColor,
                11.dp.toPx()
            )
        }

        // 控制点。
        for (p in points) {
            val cpx = px(p.x)
            val cpy = py(p.y)
            drawCircle(curveColor, radius = 8.dp.toPx(), center = Offset(cpx, cpy))
            drawCircle(surfaceColor, radius = 4.dp.toPx(), center = Offset(cpx, cpy))
        }
    }
}

// ── CurveEditor 辅助（纯函数，在 pointer scope 之外）──

private fun Offset.getDistance() = kotlin.math.sqrt(x * x + y * y)

/**
 * 命中检测：返回离 [pos] 最近且距离 < 阈值（24dp）的控制点 index，否则 -1。
 */
private fun hitControlPoint(
    pos: Offset,
    points: List<ModConfig.CurvePoint>,
    size: androidx.compose.ui.geometry.Size,
    padPx: Float,
    thresholdPx: Float
): Int {
    val w = size.width
    val h = size.height
    val plotLeft = padPx
    val plotRight = w - padPx
    val plotTop = padPx
    val plotBottom = h - padPx
    val plotW = plotRight - plotLeft
    val plotH = plotBottom - plotTop
    val threshold = thresholdPx
    var best = -1
    var bestDist = threshold
    for (i in points.indices) {
        val p = points[i]
        val cpx = plotLeft + plotW * p.x
        val cpy = plotBottom - plotH * p.y
        val dx = pos.x - cpx
        val dy = pos.y - cpy
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** 在 [pos] 处添加控制点，保持按 x 排序。 */
private fun addPoint(
    points: List<ModConfig.CurvePoint>,
    pos: Offset,
    size: androidx.compose.ui.geometry.Size,
    padPx: Float
): List<ModConfig.CurvePoint> {
    val w = size.width
    val h = size.height
    val x = ((pos.x - padPx) / (w - 2 * padPx)).coerceIn(0f, 1f)
    val y = (1f - (pos.y - padPx) / (h - 2 * padPx)).coerceIn(0f, 1f)
    return (points + ModConfig.CurvePoint(x, y)).sortedBy { it.x }
}

/** 删除指定 index 的控制点。 */
private fun removePoint(points: List<ModConfig.CurvePoint>, index: Int): List<ModConfig.CurvePoint> {
    if (index < 0 || index >= points.size) return points
    return points.filterIndexed { i, _ -> i != index }
}

/**
 * 把 index 处的控制点移动到 [pos]。
 * x 被约束在相邻控制点之间：向左不能 ≤ 左边相邻点，向右不能 ≥ 右边相邻点。
 * epsilon 保证严格不等，防止两点 x 完全相同导致 sortedBy 交换顺序（瞬移重合）。
 */
private fun movePoint(
    points: List<ModConfig.CurvePoint>,
    index: Int,
    pos: Offset,
    size: androidx.compose.ui.geometry.Size,
    padPx: Float
): List<ModConfig.CurvePoint> {
    if (index < 0 || index >= points.size) return points
    val w = size.width
    val h = size.height
    val rawX = ((pos.x - padPx) / (w - 2 * padPx)).coerceIn(0f, 1f)
    val y = (1f - (pos.y - padPx) / (h - 2 * padPx)).coerceIn(0f, 1f)
    // 约束 x 不超过相邻控制点：向左不能 ≤ 左边相邻点，向右不能 ≥ 右边相邻点。
    val epsilon = 0.001f
    val minX = if (index > 0) points[index - 1].x + epsilon else 0f
    val maxX = if (index < points.size - 1) points[index + 1].x - epsilon else 1f
    if (minX > maxX) return points // 相邻点间距过小，不移动
    val x = rawX.coerceIn(minX, maxX)
    return points.mapIndexed { i, p -> if (i == index) ModConfig.CurvePoint(x, y) else p }
        .sortedBy { it.x }
}

/** 在 Canvas 上绘制文字（简化版，用 native Canvas drawText）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawText(
    text: String,
    topLeft: Offset,
    color: Color,
    textSizePx: Float,
) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        topLeft.x,
        topLeft.y + textSizePx,
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = textSizePx
            isAntiAlias = true
        }
    )
}

private fun modeName(mode: ModConfig.PedalMode): String = when (mode) {
    ModConfig.PedalMode.OFF -> "关闭"
    ModConfig.PedalMode.SINGLE -> "单踏板模式"
    ModConfig.PedalMode.DUAL -> "双踏板模式"
}

private fun invertName(invert: ModConfig.PedalInvert): String = when (invert) {
    ModConfig.PedalInvert.OFF -> "关闭"
    ModConfig.PedalInvert.THROTTLE -> "仅油门踏板"
    ModConfig.PedalInvert.BRAKE -> "仅刹车踏板"
    ModConfig.PedalInvert.BOTH -> "全部"
}

private fun priorityName(priority: ModConfig.PedalPriority): String = when (priority) {
    ModConfig.PedalPriority.FIRST_PRESSED -> "最早按住的踏板优先"
    ModConfig.PedalPriority.LAST_TOUCHED -> "最新触摸的踏板优先"
    ModConfig.PedalPriority.ALWAYS_THROTTLE -> "始终油门优先"
    ModConfig.PedalPriority.ALWAYS_BRAKE -> "始终刹车优先"
    ModConfig.PedalPriority.THROTTLE_VALUE -> "根据油门数值判断"
    ModConfig.PedalPriority.BRAKE_VALUE -> "根据刹车数值判断"
}
