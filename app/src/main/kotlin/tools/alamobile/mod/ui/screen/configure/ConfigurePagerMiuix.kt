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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
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
                        // ── Section 1: 游戏原生功能控制 ──
                        SmallTitle(
                            text = "游戏原生功能控制",
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
                        }

                        // ── Section 2: Overlay 控件 ──
                        SmallTitle(
                            text = "Overlay 控件",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
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
    ModConfig.PedalCurve.CUSTOM -> "自定义"}

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
            fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
            color = textColor
        )
        // 图表容器：ChartCanvas 占一个正方形（含留白），外面用 Box 包住并在
        // 四周标注"行程/输出"文字，不随拖拽刷新。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            // 坐标轴标签（绘制在 Canvas 上，避免额外节点）。
            ChartCanvas(
                modifier = Modifier
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
        val points = localPoints.sortedBy { it.x }
        val curvePath = Path().apply {
            val steps = 64
            for (i in 0..steps) {
                val x = i / steps.toFloat()
                val y = ModConfig.monotoneCubic(points, x)
                val pxv = px(x)
                val pyv = py(y)
                if (i == 0) moveTo(pxv, pyv) else lineTo(pxv, pyv)
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

/** 把 index 处的控制点移动到 [pos]。 */
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
    val x = ((pos.x - padPx) / (w - 2 * padPx)).coerceIn(0f, 1f)
    val y = (1f - (pos.y - padPx) / (h - 2 * padPx)).coerceIn(0f, 1f)
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
