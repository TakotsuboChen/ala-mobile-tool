package tools.alamobile.mod

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import tools.alamobile.mod.ui.AboutScreen
import tools.alamobile.mod.ui.MainScreen
import tools.alamobile.mod.ui.PermissionGateScreen
import tools.alamobile.mod.ui.UiMode
import tools.alamobile.mod.util.AllFilesPermission
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import tools.alamobile.mod.ui.navigation3.rememberNavigator
import tools.alamobile.mod.ui.theme.LocalColorMode
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.theme.LocalEnableFloatingBottomBar
import tools.alamobile.mod.ui.theme.LocalEnableFloatingBottomBarBlur
import tools.alamobile.mod.ui.theme.LocalEnableNavigationBadge
import tools.alamobile.mod.ui.viewmodel.MainActivityViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 照搬 KernelSU `MainActivity.kt` 的 setContent 块（114-212）：
 * - viewModel + collectAsStateWithLifecycle
 * - enableEdgeToEdge（DisposableEffect + SystemBarStyle.auto）
 * - rememberNavigator(Route.Main)
 * - Density 按 pageScale 缩放
 * - CompositionLocalProvider（LocalNavigator / LocalDensity / LocalColorMode / blur 开关 / LocalUiMode）
 * - MiuixTheme（dark/light 跟随 colorMode）
 * - NavDisplay + entryProvider（Route.Main → MainScreen，Route.About → AboutScreen）
 *
 * 与 KernelSU 差异：
 * - 没有 IntentDispatcher（Ala Mobile 无 deep link / 外部 intent 分发）
 * - 没有 KernelSUTheme wrapper（Ala Mobile 只用 miuix，不切换 Material）
 * - UiMode 始终 Miuix，但保留 dispatch 结构以对齐
 * - EULA 检查移到 MainScreen 内部（由 MainScreen 决定是否渲染 ConfigMainScreen）
 */
class ConfigActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()

            // 「所有文件访问」必要权限门（2026-09-12）：未授权时满屏不可跳过，
            // 用户去设置页开完开关**返回模块**自动进主界面。resumeKey 在每次
            // ON_RESUME 自增，作为下面 remember 的 key 触发重查（LifecycleResumeEffect
            // 在 Compose 里对同一 composable 只在首次进入时跑，无法感知外部设置页归来，
            // 故用 Activity 生命周期回调驱动 key）。
            var allFilesGranted by remember { mutableStateOf(AllFilesPermission.isGranted()) }
            var resumeTick by remember { mutableIntStateOf(0) }
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
                }
                val owner = this@ConfigActivity
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(resumeTick) {
                allFilesGranted = AllFilesPermission.isGranted()
            }

            val darkMode = when (uiState.colorMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            // 照搬 KernelSU MainActivity.kt:122-135
            // ⚠️ 必须在下面的权限门 return 之前——否则门控页不启用边到边，
            // TopAppBar 的 statusBar inset 会与窗口行为叠加出错位间距。
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose { }
            }

            // 权限门视觉统一（2026-09-12）：不再只包 MiuixTheme（那只能借配色，
            // 借不到毛玻璃/pageScale/导航栏形态），而是和主界面走同一套
            // CompositionLocal + enableEdgeToEdge，见 [PermissionGateScreen]。
            if (!allFilesGranted) {
                val gateSystemDensity = LocalDensity.current
                val gateDensity = remember(gateSystemDensity, uiState.pageScale) {
                    Density(gateSystemDensity.density * uiState.pageScale, gateSystemDensity.fontScale)
                }
                CompositionLocalProvider(
                    LocalDensity provides gateDensity,
                    LocalColorMode provides uiState.colorMode,
                    LocalEnableBlur provides uiState.enableBlur,
                    LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                    LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                    LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                ) {
                    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
                        PermissionGateScreen()
                    }
                }
                return@setContent
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            // navigationevent dispatcher owner：不手动创建，用 ComponentActivity 自带的
            // NavigationEventDispatcherOwner（activity 1.13.0 已实现，已绑定 OnBackPressedDispatcher）。
            // LocalNavigationEventDispatcherOwner 在 Android 端是 ViewTreeLocal，从 ContextWrapper
            // fallback 找到 Activity 的 owner，系统返回键经 Activity dispatcher → 弹窗 NavigationBackHandler。
            // 之前手动 rememberNavigationEventDispatcherOwner(parent=null) 创建了未绑定 OnBackPressedDispatcher
            // 的独立 dispatcher，导致弹窗收不到系统返回事件、直接 finish 退桌面。

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides uiState.colorMode,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                tools.alamobile.mod.ui.LocalUiMode provides uiState.uiMode,
            ) {
                MiuixTheme(
                    colors = if (darkMode) darkColorScheme() else lightColorScheme()
                ) {
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = { navigator.pop() },
                            entryProvider = entryProvider {
                                entry<Route.Main> { mainScreenEntry() }
                                entry<Route.Overview> { mainScreenEntry() }
                                entry<Route.Configure> { mainScreenEntry() }
                                entry<Route.Settings> { mainScreenEntry() }
                                entry<Route.About> { AboutScreen() }
                                entry<Route.Paddock> { tools.alamobile.mod.ui.screen.paddock.LeaderboardScreen() }
                                entry<Route.Avatar> { tools.alamobile.mod.ui.screen.paddock.AvatarScreen() }
                            }
                        )
                    }

                    // Ala Mobile 只用 miuix，UiMode 分支保留结构对齐但走同一路径。
                    when (uiState.uiMode) {
                        UiMode.Miuix -> Scaffold { navDisplay() }
                        UiMode.Material -> Scaffold { navDisplay() }
                    }
                }
            }
        }
    }
}
