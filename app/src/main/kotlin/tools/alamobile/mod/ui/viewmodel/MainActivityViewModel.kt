package tools.alamobile.mod.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tools.alamobile.mod.ui.UiMode

/**
 * 照搬 KernelSU `MainActivityViewModel`：用 SavedStateHandle 管理 selectedMainPage，
 * 用 SharedPreferences 监听 UI 设置（blur / pageScale / uiMode）。
 *
 * 与 KernelSU 的差异：
 * - 配置数据源是 [tools.alamobile.mod.config.ModConfig]（JSON 文件 + Remote Preferences），
 *   不是 SharedPreferences。所以业务字段（踏板/DRS/解锁等）由 [ConfigViewModel] 单独管理，
 *   本 ViewModel 只管 UI 层状态（pageScale / enableBlur / uiMode）。
 * - 没有 KernelSU 的 ColorPalette / MaterialKolor 集成，colorMode 只支持 0/1/2。
 */
class MainActivityViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in observedKeys) {
            _uiState.value = readUiState()
        }
    }

    private val _uiState = MutableStateFlow(readUiState())
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()

    val selectedMainPage: StateFlow<Int> = savedStateHandle.getStateFlow(SELECTED_MAIN_PAGE_KEY, 0)

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun setSelectedMainPage(page: Int) {
        savedStateHandle[SELECTED_MAIN_PAGE_KEY] = MainPagerConfig.coercePage(page)
    }

    private fun readUiState(): MainActivityUiState {
        return MainActivityUiState(
            pageScale = prefs.getFloat("page_scale", 1f),
            enableBlur = prefs.getBoolean("enable_blur", false),
            enableFloatingBottomBar = prefs.getBoolean("enable_floating_bottom_bar", false),
            enableFloatingBottomBarBlur = prefs.getBoolean("enable_floating_bottom_bar_blur", false),
            enableNavigationBadge = prefs.getBoolean("enable_navigation_badge", false),
            uiMode = UiMode.fromValue(prefs.getString("ui_mode", UiMode.DEFAULT_VALUE)!!),
            colorMode = prefs.getInt("color_mode", 0),
        )
    }

    private companion object {
        const val SELECTED_MAIN_PAGE_KEY = "selected_main_page"

        val observedKeys = setOf(
            "page_scale",
            "enable_blur",
            "enable_floating_bottom_bar",
            "enable_floating_bottom_bar_blur",
            "enable_navigation_badge",
            "ui_mode",
            "color_mode",
        )
    }
}

data class MainActivityUiState(
    val pageScale: Float = 1f,
    val enableBlur: Boolean = false,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = false,
    val uiMode: UiMode = UiMode.Miuix,
    val colorMode: Int = 0,
)

object MainPagerConfig {
    const val PAGE_COUNT = 4
    const val LAST_PAGE_INDEX = PAGE_COUNT - 1

    fun coercePage(page: Int): Int = page.coerceIn(0, LAST_PAGE_INDEX)
}
