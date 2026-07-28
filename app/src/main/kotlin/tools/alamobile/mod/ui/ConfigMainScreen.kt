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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tools.alamobile.mod.config.ModConfig
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Top-level entry for the KernelSU-style miuix configuration UI.
 */
@Composable
fun ConfigMainScreen(
    onFinish: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { ModConfig.read(context) }

    val enableControlReplacementState = remember { mutableStateOf(settings.enableControlReplacement) }
    val enableAutoDrsState = remember { mutableStateOf(settings.enableAutoDrs) }
    val showOverlayState = remember { mutableStateOf(settings.showOverlay) }
    val disableAutoGearState = remember { mutableStateOf(settings.disableAutoGear) }
    val deadzoneState = remember { mutableStateOf(settings.pedalDeadzone) }
    val transitionState = remember { mutableStateOf(settings.pedalTransition) }
    val curveState = remember { mutableStateOf(settings.pedalCurve) }
    val logEnabledState = remember { mutableStateOf(settings.logEnabled) }

    var enableControlReplacement by enableControlReplacementState
    var enableAutoDrs by enableAutoDrsState
    var showOverlay by showOverlayState
    var disableAutoGear by disableAutoGearState
    var deadzone by deadzoneState
    var transition by transitionState
    var curve by curveState
    var logEnabled by logEnabledState

    val saveHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var saveRunnable: Runnable? = null

    val saveNow: () -> Unit = {
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            ModConfig.write(
                context,
                ModConfig.Settings(
                    enableControlReplacement = enableControlReplacement,
                    enableAutoDrs = enableAutoDrs,
                    showOverlay = showOverlay,
                    disableAutoGear = disableAutoGear,
                    pedalDeadzone = deadzone,
                    pedalTransition = transition,
                    pedalCurve = curve,
                    logEnabled = logEnabled
                )
            )
        }
        saveRunnable = runnable
        saveHandler.postDelayed(runnable, 300)
    }

    val switchSave: (() -> Unit) -> Unit = { action ->
        action()
        saveNow()
    }

    val uiState = remember {
        ConfigUiState(
            enableControlReplacement = enableControlReplacement,
            enableAutoDrs = enableAutoDrs,
            showOverlay = showOverlay,
            disableAutoGear = disableAutoGear,
            deadzone = deadzone,
            transition = transition,
            curve = curve,
            logEnabled = logEnabled
        )
    }

    DisposableEffect(
        enableControlReplacement,
        enableAutoDrs,
        showOverlay,
        disableAutoGear,
        deadzone,
        transition,
        curve,
        logEnabled
    ) {
        uiState.enableControlReplacement = enableControlReplacement
        uiState.enableAutoDrs = enableAutoDrs
        uiState.showOverlay = showOverlay
        uiState.disableAutoGear = disableAutoGear
        uiState.deadzone = deadzone
        uiState.transition = transition
        uiState.curve = curve
        uiState.logEnabled = logEnabled
        onDispose { }
    }

    val actions = remember {
        ConfigActions(
            onSetControlReplacement = { switchSave { enableControlReplacement = it } },
            onSetAutoDrs = { switchSave { enableAutoDrs = it } },
            onSetShowOverlay = { switchSave { showOverlay = it } },
            onSetDisableAutoGear = { switchSave { disableAutoGear = it } },
            onSetDeadzone = { switchSave { deadzone = it } },
            onSetTransition = { switchSave { transition = it } },
            onSetCurve = { switchSave { curve = it } },
            onSetLogEnabled = { switchSave { logEnabled = it } }
        )
    }

    ConfigMainScreenContent(
        uiState = uiState,
        actions = actions,
        onFinish = onFinish
    )
}

@Composable
private fun ConfigMainScreenContent(
    uiState: ConfigUiState,
    actions: ConfigActions,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { Tab.entries.size })
    val pagerStateHolder = rememberMainPagerState(pagerState)
    val useRail = false

    CompositionLocalProvider(
        LocalMainPagerState provides pagerStateHolder
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "Ala Mobile Tool"
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                val pagerContent: @Composable (bottomPadding: Dp) -> Unit = { bottomPadding ->
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = Tab.entries.size,
                        userScrollEnabled = true,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (Tab.entries[page]) {
                            Tab.HOME -> OverviewPage()
                            Tab.CONFIGURE -> ConfigurePage(
                                uiState = uiState,
                                actions = actions
                            )
                            Tab.SETTINGS -> SettingsPage(
                                logEnabled = uiState.logEnabled,
                                onLogEnabled = { actions.onSetLogEnabled(it) }
                            )
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
                                this.NavigationRailItem(
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
                            NavigationBar(
                                modifier = Modifier.fillMaxWidth(),
                                color = MiuixTheme.colorScheme.surface
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
    var enableControlReplacement: Boolean,
    var enableAutoDrs: Boolean,
    var showOverlay: Boolean,
    var disableAutoGear: Boolean,
    var deadzone: Float,
    var transition: Float,
    var curve: ModConfig.PedalCurve,
    var logEnabled: Boolean = false
)

class ConfigActions(
    var onSetControlReplacement: (Boolean) -> Unit = {},
    var onSetAutoDrs: (Boolean) -> Unit = {},
    var onSetShowOverlay: (Boolean) -> Unit = {},
    var onSetDisableAutoGear: (Boolean) -> Unit = {},
    var onSetDeadzone: (Float) -> Unit = {},
    var onSetTransition: (Float) -> Unit = {},
    var onSetCurve: (ModConfig.PedalCurve) -> Unit = {},
    var onSetLogEnabled: (Boolean) -> Unit = {}
)

class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope
) {
    var selectedPage by mutableStateOf(pagerState.currentPage)
        @Suppress("UNUSED") private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return
        navJob?.cancel()
        selectedPage = targetIndex
        navJob = coroutineScope.launch {
            pagerState.animateScrollToPage(targetIndex)
        }
    }

    fun syncPage() {
        if (selectedPage != pagerState.currentPage) {
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
