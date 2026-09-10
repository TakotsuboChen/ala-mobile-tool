package tools.alamobile.mod.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import tools.alamobile.mod.ui.component.bottombar.MainPagerState
import tools.alamobile.mod.ui.component.bottombar.rememberMainPagerState
import tools.alamobile.mod.ui.component.bottombar.useNavigationRail
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import tools.alamobile.mod.ui.screen.configure.ConfigurePager
import tools.alamobile.mod.ui.screen.overview.OverviewPager
import tools.alamobile.mod.ui.screen.settings.SettingsPager
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.theme.LocalEnableFloatingBottomBar
import tools.alamobile.mod.ui.theme.LocalEnableFloatingBottomBarBlur
import tools.alamobile.mod.ui.theme.LocalEnableNavigationBadge
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.GestureDirectionLockState
import tools.alamobile.mod.ui.util.LocalGestureDirectionLock
import tools.alamobile.mod.ui.util.blockScrollAxis
import tools.alamobile.mod.ui.util.gestureDirectionLockObserver
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.util.rememberContentReady
import tools.alamobile.mod.ui.viewmodel.MainPagerConfig
import androidx.compose.foundation.gestures.Orientation
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 照搬 KernelSU `MainActivity.kt:226-411` MainScreen + MainScreenBackHandler。
 *
 * 三页 pager（概览/配置/设置）+ 底栏 + 双层 backdrop。
 * Ala Mobile 没有 badge / floating bottom bar 的实际 UI（CompositionLocal 默认 false），
 * 但保留结构分支以对齐 KernelSU。
 */

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current

    // 围场登录门控（2026-09-10，用户定案交互时序）：未登录 + 前置弹窗流程
    // （EULA/更新/激活）全部出完结果 → 弹「登录围场以使用模块」弹窗（优先级
    // 最低，弹窗期间**不锁页**，用户仍可自由浏览）。点「去登录」→ 关弹窗 +
    // 跳围场页 + **此时才上锁**（手势翻页禁用、底栏只留围场、返回键不回概览），
    // 登录完成解锁。点「退出」→ 关模块。
    //
    // 门控状态机：Idle（未触发）→ Dialog（弹窗展示中，可自由浏览）→
    // Locked（点了去登录，锁围场页）→ 登录成功回 Idle。登出（围场页退出登录）
    // 后 loggedIn=false 且前置条件都在 → 重新走 Dialog。
    val paddockViewModel: tools.alamobile.mod.ui.viewmodel.PaddockViewModel = viewModel()
    val paddockUiState by paddockViewModel.uiState.collectAsStateWithLifecycle()
    val gatePrecondition = LoginGateCoordinator.canGate(paddockUiState.loggedIn)

    var gateDialogShow by remember { mutableStateOf(false) }    // 弹窗 show（驱动退出动画）
    var gateDialogMounted by remember { mutableStateOf(false) } // 弹窗挂载（两变量契约）
    var gateLocked by remember { mutableStateOf(false) }        // 「去登录」后置位 = 锁页
    var pendingGateExit by remember { mutableStateOf(false) }   // 「退出」→ 动画完 finish
    val contextForGateExit = LocalContext.current
    // 登录完成（或登出循环重启）时清锁。
    LaunchedEffect(paddockUiState.loggedIn) {
        if (paddockUiState.loggedIn) gateLocked = false
    }
    // 前置条件满足且未登录且未锁页且弹窗未挂载 → 挂载弹窗。LaunchedEffect 只在
    // 键变化时触发一次，弹窗关掉后（mounted=false）前置条件不变就不会重弹——
    // 下次冷启动/登出（键变化）才重新武装。⚠️ 不能用「组合期 if (mounted && !show)
    // show = true」的写法：退出动画期间每次重组都会把 show 翻回 true，弹窗永远
    // 关不掉，onDismissFinished 不触发，两个按钮看起来都"没反应"（实测踩坑）。
    LaunchedEffect(gatePrecondition, gateLocked) {
        if (gatePrecondition && !gateLocked && !gateDialogMounted) {
            gateDialogMounted = true
            gateDialogShow = true
        }
    }

    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(pagerState)

    // 锁页 = 用户点了「去登录」之后（弹窗期间不锁，用户可自由浏览——用户定案）。
    val loginGateActive = gateLocked && !paddockUiState.loggedIn

    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    // 外层 backdrop：给底栏 BlurredBar 用，同时包裹整个 pager。
    // 与 KernelSU MainActivity.kt:282-285 对齐。
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        tools.alamobile.mod.util.Logger.log(
            android.util.Log.INFO, "MainScreen",
            "settledPage -> $settledPage (currentPage=${mainPagerState.pagerState.currentPage})"
        )
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }
    // 焦点闪滚诊断（临时）：pager 被非手势滚动（bringIntoView/IME resize）时打点。
    LaunchedEffect(mainPagerState.pagerState.targetPage) {
        tools.alamobile.mod.util.Logger.log(
            android.util.Log.INFO, "MainScreen",
            "targetPage -> ${mainPagerState.pagerState.targetPage}"
        )
    }

    // Pager 不响应 bringIntoView（2026-09-10 焦点闪滚修复）：TextField 获得焦点
    // 时 foundation 的 FocusableNode 无条件发 bringIntoView 请求，PagerBringIntoViewSpec
    // 的 settlingScrollDistance 在相邻页已被组合（beyondViewportPageCount 放开后）
    // 时会算出跨页滚动距离 → pager 短暂滑向邻页又被 snap 拉回（围场页点用户名
    // 输入框闪现到设置页再弹回，实机实证）。页内竖向让字段可见的滚动由 LazyColumn
    // 自己响应（在 bringIntoView 链上先于 pager），返回 0 只拦横向跨页，不影响输入法。
    // 本 BOM（foundation 1.11.4）的 HorizontalPager 无 bringIntoViewSpec 参数，
    // 但 Pager 内部经 LocalBringIntoViewSpec CompositionLocal 取 spec——在 pager
    // 外层覆盖它等价于传参。
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val noBringIntoViewSpec = remember {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = 0f
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides noBringIntoViewSpec,
    ) {
    MainScreenBackHandler(mainPagerState)

    // 围场登录弹窗（优先级最低）：前置弹窗（EULA/更新/激活）全部出完 + 未登录
    // 时展示（gateDialogMounted）。弹窗期间**不锁页**——用户可自由浏览，与前置
    // 弹窗不打架的关键。点「去登录」→ 退出动画播完（onDismissFinished）→
    // gateLocked=true → 跳围场页 + 上锁（手势翻页禁用、底栏只留围场、返回键不回
    // 概览），登录完成自动解锁。goLoginAsked 是「去登录/退出」分流标记：
    // 组合树卸载后状态即弃，无需复位。
    if (gateDialogMounted) {
        var goLoginAsked by remember { mutableStateOf(false) }
        LoginGateDialog(
            show = gateDialogShow,
            onGoLogin = {
                goLoginAsked = true
                gateDialogShow = false
            },
            onExit = {
                // 「退出」= 退出整个模块 App：退出动画播完后 finish（UpdateDialog
                // 「退出模块」同模式，finish 必须等动画结束否则系统拦不住返回键）。
                goLoginAsked = false
                gateDialogShow = false
                pendingGateExit = true
            },
            onDismissFinished = {
                if (goLoginAsked) {
                    gateLocked = true
                    mainPagerState.animateToPage(MainPagerConfig.PADDOCK_PAGE_INDEX)
                }
                gateDialogMounted = false
                if (pendingGateExit) {
                    (contextForGateExit as? android.app.Activity)?.finish()
                }
            },
        )
    }

    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)

    // 页面级手势方向锁：横滑切页 vs 页内竖滚在一次触摸内互斥（见
    // GestureDirectionLock.kt 的 AwaitGesturePickup 机制说明）。
    val directionLock = remember { GestureDirectionLockState() }

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState,
        LocalGestureDirectionLock provides directionLock,
        PaddockLoggedInState provides paddockUiState.loggedIn,
        LoginGateLockedState provides loginGateActive,
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(
                modifier = Modifier
                    .gestureDirectionLockObserver(directionLock)
                    // 方向锁为竖向（正在竖滚页面）时吃掉 pager 的横向增量，
                    // AwaitGesturePickup 复活接管也翻不了页。
                    .blockScrollAxis(directionLock, Orientation.Horizontal)
                    .then(if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier)
            ) {
                HorizontalPager(
                    modifier = Modifier
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) MainPagerConfig.LAST_PAGE_INDEX else 0,
                    overscrollEffect = null,
                    // 登录门控激活：禁用手势翻页，锁死在围场页（底栏点击也拦，见下）。
                    userScrollEnabled = !loginGateActive,
                ) { page ->
                    val isCurrentPage = page == settledPage
                    if (isCurrentPage || contentReady) {
                        when (page) {
                            0 -> OverviewPager(navController, bottomInnerPadding, isCurrentPage)
                            1 -> ConfigurePager(navController, bottomInnerPadding, isCurrentPage)
                            2 -> tools.alamobile.mod.ui.screen.paddock.PaddockPager(isCurrentPage, bottomInnerPadding)
                            3 -> SettingsPager(bottomInnerPadding)
                        }
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            Scaffold { _ ->
                Row {
                    SideRail()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .consumeWindowInsets(startInsets)
                    ) {
                        pagerContent(navBarBottomPadding)
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BlurredBar(blurBackdrop) {
                        NavigationBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        ) {
                            BottomBarDestination.entries.forEachIndexed { index, destination ->
                                NavigationBarItem(
                                    modifier = Modifier.weight(1f),
                                    icon = destination.icon,
                                    label = destination.label,
                                    selected = mainPagerState.selectedPage == index,
                                    // 登录门控激活：只允许点围场页（其他页拦截）。
                                    onClick = {
                                        if (!loginGateActive || index == MainPagerConfig.PADDOCK_PAGE_INDEX) {
                                            mainPagerState.animateToPage(index)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Scaffold(
                bottomBar = bottomBar,
            ) { innerPadding ->
                pagerContent(innerPadding.calculateBottomPadding())
            }
        }
        // CompositionLocalProvider（LocalBringIntoViewSpec 覆盖）作用域闭合
    }
    }
}

private enum class BottomBarDestination(
    val label: String,
    val icon: ImageVector,
) {
    Overview("概览", Icons.Rounded.Home),
    Configure("配置", Icons.Rounded.Build),
    Paddock("围场", tools.alamobile.mod.ui.ChequeredFlagIcon),
    Settings("设置", Icons.Rounded.Settings);
}

@Composable
private fun SideRail() {
    val mainState = LocalMainPagerState.current
    top.yukonga.miuix.kmp.basic.NavigationRail(
        state = top.yukonga.miuix.kmp.basic.rememberNavigationRailState(),
        color = MiuixTheme.colorScheme.surface,
    ) {
        BottomBarDestination.entries.forEachIndexed { index, destination ->
            // 登录门控锁页：只允许点围场页（SideRail 布局同样拦截）。
            // CompositionLocal 在组合期读（onClick lambda 内非组合上下文不可 .current）。
            val gateLocked = LoginGateLockedState.current
            top.yukonga.miuix.kmp.basic.NavigationRailItem(
                selected = mainState.selectedPage == index,
                onClick = {
                    if (!gateLocked || index == MainPagerConfig.PADDOCK_PAGE_INDEX) {
                        mainState.animateToPage(index)
                    }
                },
                icon = destination.icon,
                label = destination.label,
            )
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
) {
    val navControllerCompat = LocalNavigator.current
    // 登录门控锁页（LoginGateLockedState 共享）时禁用「返回键回概览」——
    // 锁死在围场页直到登录完成。读的是 Compose 状态（CompositionLocal +
    // object 的 mutableStateOf），组合期求值即随状态变化重组。
    val gateLocked = LoginGateLockedState.current
    val isPagerBackHandlerEnabled =
        navControllerCompat.current() is Route.Main && navControllerCompat.backStackSize() == 1 &&
            mainState.selectedPage != 0 && !gateLocked

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

/**
 * 登录门控的登录态 CompositionLocal：SideRail 离 MainScreen 作用域较远，经此
 * 共享 PaddockViewModel 的 loggedIn（MainScreen 顶层 provide）。
 */
val PaddockLoggedInState = staticCompositionLocalOf { false }

/**
 * 登录门控「已点去登录 = 页锁生效」的 CompositionLocal（MainScreen 顶层 provide），
 * SideRail / MainScreenBackHandler 读它拦截切页与返回键。
 */
val LoginGateLockedState = staticCompositionLocalOf { false }

/**
 * 围场登录门控弹窗：标题「登录围场以使用模块」无正文，左灰「退出」右蓝「去登录」。
 * 优先级最低（低于更新/EULA/激活弹窗）——挂载由 MainScreen 的 loginGateActive
 * 守门，那些前置弹窗在概览页时本弹窗未挂载，天然不打架。
 */
@Composable
private fun LoginGateDialog(
    show: Boolean,
    onGoLogin: () -> Unit,
    onExit: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "登录围场以使用模块",
        onDismissRequest = onExit,
        onDismissFinished = onDismissFinished,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "退出",
                    onClick = onExit,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "去登录",
                    onClick = onGoLogin,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}

