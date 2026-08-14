package tools.alamobile.mod.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.util.rememberContentReady
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import tools.alamobile.mod.ui.viewmodel.MainPagerConfig
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * 照搬 KernelSU `MainActivity.kt:226-411` MainScreen + MainScreenBackHandler。
 *
 * 三页 pager（概览/配置/设置）+ 底栏 + 双层 backdrop + rememberContentReady 门控。
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
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(pagerState)

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
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        // 不用 rememberContentReady：它依赖 LocalNavAnimatedContentScope.current.transition.isRunning，
        // 但我们的三个 page 是 HorizontalPager 内切换，不经过 NavDisplay 的 AnimatedContent 过渡。
        // transition 永远不 running → contentReady 永远 false → beyondViewportPageCount 永远 0
        // → 每次切 tab 都要从零首次组合相邻页的整个子树（Scaffold+LazyColumn+Card+preference），
        // 这就是 30% janky 的根因。
        // KernelSU 用它是为了在 Nav3 页面切换过渡动画期间保护首帧；我们没有 Nav3 过渡，直接全预组。
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = MainPagerConfig.LAST_PAGE_INDEX,
                    overscrollEffect = null,
                    userScrollEnabled = true,
                ) { page ->
                    when (page) {
                        0 -> OverviewPager(navController, bottomInnerPadding, page == settledPage)
                        1 -> ConfigurePager(navController, bottomInnerPadding, page == settledPage)
                        2 -> SettingsPager(navController, bottomInnerPadding)
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
                                    onClick = { mainPagerState.animateToPage(index) },
                                )
                            }
                        }
                    }
                }
            }

            Scaffold(
                bottomBar = bottomBar,
                popupHost = { MiuixPopupUtils.MiuixPopupHost() }
            ) { innerPadding ->
                pagerContent(innerPadding.calculateBottomPadding())
            }
        }
    }
}

private enum class BottomBarDestination(
    val label: String,
    val icon: ImageVector,
) {
    Overview("概览", Icons.Rounded.Home),
    Configure("配置", Icons.Rounded.Build),
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
            top.yukonga.miuix.kmp.basic.NavigationRailItem(
                selected = mainState.selectedPage == index,
                onClick = { mainState.animateToPage(index) },
                icon = destination.icon,
                label = destination.label,
            )
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: tools.alamobile.mod.ui.navigation3.Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

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
