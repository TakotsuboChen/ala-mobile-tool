package tools.alamobile.mod.ui.component.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import tools.alamobile.mod.ui.component.PagerNavigationSpringSpec
import tools.alamobile.mod.ui.util.shouldShowSplitPane
import tools.alamobile.mod.ui.LocalUiMode
import tools.alamobile.mod.ui.UiMode
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import kotlin.math.abs

/**
 * 照搬 KernelSU `MainPagerState.kt`：包装 PagerState，提供 selectedPage / isNavigating / animateToPage，
 * 使用 [PagerNavigationSpringSpec] 做 spring 动画。去掉了 KernelSU 的 badge 逻辑（Ala Mobile 不需要角标）。
 */
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

private suspend fun PagerState.springAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    scroll(MutatePriority.UserInput) {
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distance = target - currentPage - currentPageOffsetFraction
        val scrollPixels = distance * pageSize
        if (abs(scrollPixels) <= 0.5f) return@scroll
        var consumedScroll = 0f
        var skipScroll = false
        Animatable(0f).animateTo(
            targetValue = scrollPixels,
            animationSpec = PagerNavigationSpringSpec,
        ) {
            if (skipScroll) return@animateTo

            val delta = value - consumedScroll
            if (abs(delta) > 0.5f) {
                val consumed = scrollBy(delta)
                consumedScroll += consumed
                if (abs(delta - consumed) > 0.1f) {
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

    // ⚠️ 无条件精确归位（2026-09-10 焦点闪跳定案修复）：KernelSU 原版在
    // 「|remaining| <= 0.5f 且 currentPage == target」时跳过 scrollToPage——
    // pager 会停在离页边界最多 1px 的位置。登录门控锁页后 userScrollEnabled=false，
    // 没有任何手势能再触发 snap 归位，这个亚像素残差**常驻**；此后 TextField 聚焦/
    // IME 弹出的 bringIntoView 请求经 PagerBringIntoViewSpec 判定「不在页边界」
    // → settlingScrollDistance 算出整页吸附距离 → pager 无手势时凭空跳到邻页
    // （实机实证：点用户名框跳设置页且滑不回来，点底栏 animateToPage 归位后才恢复）。
    // 每次程序化导航后强制 scrollToPage 保证 firstVisiblePageOffset 恒为 0，
    // 焦点滚动请求恒为 0 距离，跳页物理性消失。
    scrollToPage(target)
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): MainPagerState {
    return remember(pagerState, coroutineScope) {
        MainPagerState(pagerState, coroutineScope)
    }
}

@Immutable
data class NavigationBadgeState(
    val superuserCount: Int = 0,
    val moduleEnabledCount: Int = 0,
    val moduleUpdatableCount: Int = 0,
)

@Composable
fun useNavigationRail(enableFloatingBottomBar: Boolean): Boolean {
    return shouldShowSplitPane() && !(LocalUiMode.current == UiMode.Miuix && enableFloatingBottomBar)
}
