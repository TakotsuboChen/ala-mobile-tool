package tools.alamobile.mod

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import tools.alamobile.mod.ui.AboutScreen
import tools.alamobile.mod.ui.MainScreen
import tools.alamobile.mod.ui.UiMode
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
            val darkMode = when (uiState.colorMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            // 照搬 KernelSU MainActivity.kt:122-135
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
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            // navigationevent dispatcher owner（miuix 需要）
            val dispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides uiState.colorMode,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                tools.alamobile.mod.ui.LocalUiMode provides uiState.uiMode,
                LocalNavigationEventDispatcherOwner provides dispatcherOwner,
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
