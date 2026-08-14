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
    data object About : Route
}
