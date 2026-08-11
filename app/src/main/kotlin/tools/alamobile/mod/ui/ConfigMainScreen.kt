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
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 */
@Composable
fun ConfigMainScreen(
    onFinish: () -> Unit = {},
    onEulaDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settings = remember { ModConfig.read(context) }

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

    val saveHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var saveRunnable: Runnable? = null

    val saveNow: () -> Unit = {
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            ModConfig.write(
                context,
                ModConfig.Settings(
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
            )
        }
        saveRunnable = runnable
        saveHandler.postDelayed(runnable, 300)
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
    val backdrop = rememberBlurBackdrop(enableBlur)

    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage) {
        pagerStateHolder.syncPage()
    }
    val currentPage = pagerState.currentPage
    LaunchedEffect(currentPage) {
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
                    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            userScrollEnabled = true,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            when (Tab.entries[page]) {
                                Tab.HOME -> OverviewPage(
                                    bottomBarHeight = bottomPadding,
                                    activationEnabled = eulaAccepted
                                )
                                Tab.CONFIGURE -> ConfigurePage(
                                    uiState = uiState,
                                    onSave = onSave,
                                    bottomBarHeight = bottomPadding
                                )
                                Tab.SETTINGS -> SettingsPage(
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
                            BlurredBar(backdrop) {
                                NavigationBar(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
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

        val distance = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
        val duration = 100 * distance + 100
        val layoutInfo = pagerState.layoutInfo
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val currentDistanceInPages = targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
        val scrollPixels = currentDistanceInPages * pageSize

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.animateScrollBy(
                    value = scrollPixels,
                    animationSpec = tween(easing = EaseInOut, durationMillis = duration)
                )
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
