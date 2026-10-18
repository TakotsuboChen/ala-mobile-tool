package tools.alamobile.mod.ui.navigation3

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * 照搬 KernelSU `Routes.kt`，裁剪到 Ala Mobile 需要的目标：
 * - `Main` 是入口（承载 pager + 底栏）
 * - 三个 page 作为 NavKey 供 entryProvider 映射（实际渲染由 pager 驱动，这里用于二级页面 push）
 * - `About` 是独立的二级页面
 */
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Overview : Route

    @Parcelize
    @Serializable
    data object Configure : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object Paddock : Route

    @Parcelize
    @Serializable
    data object Avatar : Route

    /**
     * 车手主页（2026-10-18）：`userId` = 服务端 users.id。
     * 从围场主页信息卡 / 排行榜任意一行点进来，展示该车手的全量圈速记录。
     */
    @Parcelize
    @Serializable
    data class Driver(val userId: String) : Route

    @Parcelize
    @Serializable
    data object About : Route

    // ── 配置页 Hub 的四个二级页（配置首页拆成四张入口卡片）──
    @Parcelize
    @Serializable
    data object ConfigureNativeFeatures : Route

    @Parcelize
    @Serializable
    data object ConfigureOverlay : Route

    @Parcelize
    @Serializable
    data object ConfigureCurves : Route

    @Parcelize
    @Serializable
    data object ConfigureMisc : Route
}
