package tools.alamobile.mod.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.EulaManager
import tools.alamobile.mod.config.ModConfig
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/**
 * Top-level entry for the KernelSU-style miuix configuration UI.
 *
 * backdrop 分层照搬 KernelSU MainActivity.kt:280-323：
 * - 外层 [blurBackdrop] 给底栏 BlurredBar 用，同时包裹整个 pager。
 * - 子页面（OverviewPage 等）各自再 rememberBlurBackdrop 给自己的 TopBar + content 用。
 * 这是嵌套两层 backdrop，不是一层共享——同一个 LayerBackdrop 实例多处 layerBackdrop 会 SIGSEGV。
 */
@Composable
fun ConfigMainScreen(
    onFinish: () -> Unit = {},
    onEulaDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settings = remember { ModConfig.read(context) }
    val saveScope = rememberCoroutineScope()

    val uiState = remember {
        ConfigUiState(
            pedalMode = mutableStateOf(settings.pedalMode),
            enableAutoDrs = mutableStateOf(settings.enableAutoDrs),
            showOverlay = mutableStateOf(settings.showOverlay),
            disableAutoGear = mutableStateOf(settings.disableAutoGear),
            enableManualShift = mutableStateOf(settings.enableManualShift),
            enableUnlock = mutableStateOf(settings.enableUnlock),
            enableTc = mutableStateOf(settings.enableTc),
            enableAbs = mutableStateOf(settings.enableAbs),
            enableMusicReplace = mutableStateOf(settings.enableMusicReplace),
            deadzone = mutableStateOf(settings.pedalDeadzone),
            transition = mutableStateOf(settings.pedalTransition),
            brakeTransition = mutableStateOf(settings.brakeTransition),
            brakeInvert = mutableStateOf(settings.brakeInvert),
            throttleCurve = mutableStateOf(settings.throttleCurve),
            brakeCurve = mutableStateOf(settings.brakeCurve),
            logEnabled = mutableStateOf(settings.logEnabled)
        )
    }

    // Debounced save：照搬 KernelSU 的 ViewModel + Dispatchers.IO 模式。
    // ModConfig.write 同步做了 JSON 序列化 + Binder IPC + 文件写 + 广播，
    // 全部放在 IO 线程，main looper 只负责调度。
    // 300ms debounce 防止滑块拖动连续触发。
    var saveJob: Job? by remember { mutableStateOf(null) }

    val saveNow: () -> Unit = {
        saveJob?.cancel()
        val snapshot = ModConfig.Settings(
            pedalMode = uiState.pedalMode.value,
            enableAutoDrs = uiState.enableAutoDrs.value,
            showOverlay = uiState.showOverlay.value,
            disableAutoGear = uiState.disableAutoGear.value,
            enableManualShift = uiState.enableManualShift.value,
            enableUnlock = uiState.enableUnlock.value,
            enableTc = uiState.enableTc.value,
            enableAbs = uiState.enableAbs.value,
            enableMusicReplace = uiState.enableMusicReplace.value,
            pedalDeadzone = uiState.deadzone.value,
            pedalTransition = uiState.transition.value,
            brakeTransition = uiState.brakeTransition.value,
            brakeInvert = uiState.brakeInvert.value,
            throttleCurve = uiState.throttleCurve.value,
            brakeCurve = uiState.brakeCurve.value,
            logEnabled = uiState.logEnabled.value
        )
        saveJob = saveScope.launch {
            kotlinx.coroutines.delay(300)
            withContext(Dispatchers.IO) {
                ModConfig.write(context, snapshot)
            }
        }
    }

    // EULA 弹窗状态：isAccepted 在 composition 内读取（CompositionLocal 作用域内），
    // 由 ConfigActivity 决定是否启用。默认已接受（非空 onEulaDismiss 表示需要显示）。
    var eulaAccepted by remember { mutableStateOf(onEulaDismiss == null) }

    // 弹窗渲染在 Scaffold 内部（popupHost 由 miuix Scaffold 提供），保证 OverlayDialog 可见。
    ConfigMainScreenContent(
        uiState = uiState,
        onSave = saveNow,
        onFinish = onFinish,
        eulaAccepted = eulaAccepted,
        eulaDialog = {
            if (!eulaAccepted) {
                EulaDialog(
                    sections = EulaManager.EULA_SECTIONS,
                    footer = EulaManager.EULA_FOOTER,
                    onAccept = {
                        EulaManager.accept(context)
                        eulaAccepted = true
                    },
                    onExit = onFinish
                )
            }
        }
    )
}

@Composable
private fun ConfigMainScreenContent(
    uiState: ConfigUiState,
    onSave: () -> Unit,
    onFinish: () -> Unit,
    eulaAccepted: Boolean,
    eulaDialog: (@Composable () -> Unit)? = null
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { Tab.entries.size })
    val pagerStateHolder = rememberMainPagerState(pagerState)
    val useRail = false
    val enableBlur = LocalEnableBlur.current
    // 外层 backdrop：给底栏 BlurredBar 用，同时包裹整个 pager 内容。
    // 与 KernelSU MainActivity.kt:280 对齐。
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    // contentReady：照搬 KernelSU rememberContentReady（DeferredContent.kt）。
    // 动画运行中 contentReady=false，只渲染当前页的轻量内容；动画停稳 + 等 1 帧
    // 后才放开 beyondViewportPageCount，让重内容在动画已停止的静态画面上 compose，
    // stutter 不可见。原实现用 settledPage 判断，但 settledPage 动画一启动就跳到
    // 目标值，导致重内容在动画进行中就 compose → 挤占主线程 → 掉帧。
    var contentReady by remember { mutableStateOf(false) }
    val isAnimating = pagerState.currentPageOffsetFraction != 0f
    LaunchedEffect(isAnimating) {
        if (!isAnimating && !contentReady) {
            // 等一帧让首屏占位渲染完毕再加载重内容。
            withFrameNanos { }
            contentReady = true
        }
    }

    // 监听 settledPage 同步底栏选中态。照搬 KernelSU MainActivity.kt:287-295。
    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage) {
        pagerStateHolder.syncPage()
    }

    CompositionLocalProvider(
        LocalMainPagerState provides pagerStateHolder
    ) {
        Scaffold(
            popupHost = {
                // EULA 弹窗渲染在默认 miuixPopupHost 之前：这样 EULA 弹窗的 zIndex
                // 天然高于激活弹窗，且点同意前不渲染激活弹窗（见下 eulaAccepted 门控）。
                eulaDialog?.invoke()
                top.yukonga.miuix.kmp.utils.MiuixPopupUtils.MiuixPopupHost()
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                val pagerContent: @Composable (bottomPadding: Dp) -> Unit = { bottomPadding ->
                    // 整个 pager 包在外层 blurBackdrop 的 layerBackdrop 里。
                    // 底栏的 BlurredBar 用同一个 blurBackdrop 做模糊——它捕获的内容
                    // 就是这个 layerBackdrop 子树渲染出来的画面。照搬 KernelSU
                    // MainActivity.kt:306。
                    Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = if (contentReady) 1 else 0,
                            userScrollEnabled = true,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            // contentReady 前只渲染当前页，避免冷启动三页同时 compose。
                            // 照搬 KernelSU MainActivity.kt:314-321。
                            val isCurrent = page == settledPage
                            when (Tab.entries[page]) {
                                Tab.HOME -> if (isCurrent || contentReady) OverviewPage(
                                    bottomBarHeight = bottomPadding,
                                    activationEnabled = eulaAccepted
                                )
                                Tab.CONFIGURE -> if (isCurrent || contentReady) ConfigurePage(
                                    uiState = uiState,
                                    onSave = onSave,
                                    bottomBarHeight = bottomPadding
                                )
                                Tab.SETTINGS -> if (isCurrent || contentReady) SettingsPage(
                                    uiState = uiState,
                                    onSave = onSave,
                                    bottomBarHeight = bottomPadding
                                )
                            }
                        }
                    }
                }

                if (useRail) {
                    val startInsets = WindowInsets.systemBars
                        .only(WindowInsetsSides.Start)

                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            state = rememberNavigationRailState(),
                            color = MiuixTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Tab.entries.forEachIndexed { index, tab ->
                                NavigationRailItem(
                                    selected = pagerStateHolder.selectedPage == index,
                                    onClick = { pagerStateHolder.animateToPage(index) },
                                    icon = tab.selectedIcon,
                                    label = tab.title
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(innerPadding.calculateBottomPadding())
                        }
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            BlurredBar(blurBackdrop) {
                                NavigationBar(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
                                ) {
                                    Tab.entries.forEachIndexed { index, tab ->
                                        this.NavigationBarItem(
                                            selected = pagerStateHolder.selectedPage == index,
                                            onClick = { pagerStateHolder.animateToPage(index) },
                                            icon = tab.selectedIcon,
                                            label = tab.title
                                        )
                                    }
                                }
                            }
                        }
                    ) { scaffoldInnerPadding ->
                        pagerContent(scaffoldInnerPadding.calculateBottomPadding())
                    }
                }
            }
        }
    }
}

private enum class Tab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("概览", Icons.Filled.Home, Icons.Outlined.Home),
    CONFIGURE("配置", Icons.Filled.Build, Icons.Outlined.Build),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}

// @Stable 标注：所有字段是 MutableState（Compose 已知稳定），引用通过 remember 固定。
// 不标的话编译器推断 ConfigUiState 不稳定，传给 ConfigurePage/SettingsPage 时
// 整组 composable 被当作不稳定参数重组。照搬 KernelSU @Immutable 普及策略。
@androidx.compose.runtime.Stable
class ConfigUiState(
    val pedalMode: MutableState<ModConfig.PedalMode>,
    val enableAutoDrs: MutableState<Boolean>,
    val showOverlay: MutableState<Boolean>,
    val disableAutoGear: MutableState<Boolean>,
    val enableManualShift: MutableState<Boolean>,
    val enableUnlock: MutableState<Boolean>,
    val enableTc: MutableState<Boolean>,
    val enableAbs: MutableState<Boolean>,
    val enableMusicReplace: MutableState<Boolean>,
    val deadzone: MutableState<Float>,
    val transition: MutableState<Float>,
    val brakeTransition: MutableState<Float>,
    val brakeInvert: MutableState<Boolean>,
    val throttleCurve: MutableState<ModConfig.PedalCurve>,
    val brakeCurve: MutableState<ModConfig.PedalCurve>,
    val logEnabled: MutableState<Boolean>
)

class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()

        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(targetIndex)
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

/**
 * Spring 动画切页，照搬 KernelSU BottomBar.kt:69-112 的 springAnimateToPage。
 * 用 scroll + Animatable + spring spec 替代 tween + animateScrollBy：
 * - spring 有自然的减速曲线，tween 的 EaseInOut 在快速连续点击时显得机械
 * - 用 MutatePriority.UserInput 抢占手势优先级，快速切换时能立即打断旧动画
 * - 末尾 scrollToPage 兜底，保证最终落点精确（动画累积误差 < 0.5px 会被 snap 修正）
 */
private suspend fun PagerState.springAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    var shouldSnapToTarget = false
    scroll(androidx.compose.foundation.MutatePriority.UserInput) {
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distance = target - currentPage - currentPageOffsetFraction
        val scrollPixels = distance * pageSize
        if (abs(scrollPixels) <= 0.5f) return@scroll

        var consumedScroll = 0f
        var skipScroll = false
        androidx.compose.animation.core.Animatable(0f).animateTo(
            targetValue = scrollPixels,
            animationSpec = androidx.compose.animation.core.spring(
                stiffness = 322.2f,
                dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
                visibilityThreshold = 0.5f,
            ),
        ) {
            if (skipScroll) return@animateTo

            val delta = value - consumedScroll
            if (abs(delta) > 0.5f) {
                val consumed = scrollBy(delta)
                consumedScroll += consumed
                if (abs(delta - consumed) > 0.1f) {
                    shouldSnapToTarget = true
                    skipScroll = true
                }
            } else {
                consumedScroll = value
            }

            if (abs(velocity) < 0.1f && abs(scrollPixels - consumedScroll) < 1.0f) {
                skipScroll = true
            }
        }

        val remaining = scrollPixels - consumedScroll
        if (abs(remaining) > 0.5f) {
            scrollBy(remaining)
        }
    }

    if (shouldSnapToTarget || currentPage != target) {
        scrollToPage(target)
    }
}

@Composable
private fun rememberMainPagerState(pagerState: PagerState): MainPagerState {
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, coroutineScope) {
        MainPagerState(pagerState, coroutineScope)
    }
}

private val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}
