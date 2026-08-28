package tools.alamobile.mod.ui.util

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * 页面级手势方向锁：一次触摸会话内，先跨过 touch slop 的轴独占到底，
 * 另一轴在松手前不得接管（pager 横滑 vs 页面竖滚的方向互抢问题）。
 *
 * 为什么不能靠检测器层解决：Compose 的拖拽检测器被消费事件取消后并不死亡，
 * 而是进入 AwaitGesturePickup 等待状态——同一手势内只要出现一个完全未被
 * 消费的事件就原地复活接管（Draggable.kt processAwaitGesturePickup 的
 * hasUnconsumedDrag 检查）。而活动拖拽只消费沿自己轴有位移的事件（
 * hasDragged 过滤），纯另一轴位移的事件会原样漏出——于是"横滑回位后改
 * 竖滑"成为可能。锁轴因此做在增量层：NestedScrollConnection 把被锁轴的
 * UserInput 滚动增量全部吃掉，检测器随便接管，位移恒为零。
 *
 * 锁的生命周期：DOWN 时重置，跨 slop 时按主导轴（|dx| vs |dy|）锁定，
 * 抬手后保留到下一次 DOWN——抬手后的惯性 fling 仍受锁约束，而程序化滚动
 *（底栏切页动画等，source != UserInput）不受影响。
 */
class GestureDirectionLockState {
    var lockedAxis: Orientation? by mutableStateOf(null)
        private set

    internal fun reset() {
        lockedAxis = null
    }

    internal fun lock(axis: Orientation) {
        if (lockedAxis == null) lockedAxis = axis
    }
}

/** MainScreen 提供；页面组件（如 ConfigurePagerMiuix）经此读取同一把锁。 */
val LocalGestureDirectionLock: ProvidableCompositionLocal<GestureDirectionLockState> =
    staticCompositionLocalOf { GestureDirectionLockState() }

/**
 * 轴锁观察器：挂在 HorizontalPager 的外层 Box 上，被动记录每次触摸的
 * 主导轴。只观察不消费——消费会杀死子级检测器（slop 自由竞争），轴的
 * 认领仍由 pager/LazyColumn 自己完成，本层只负责"锁住败者"。
 */
fun Modifier.gestureDirectionLockObserver(state: GestureDirectionLockState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            state.reset()
            var total = Offset.Zero
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent()
                val change: PointerInputChange = event.changes.firstOrNull { it.pressed } ?: break
                total += change.positionChangeIgnoreConsumed()
                if (state.lockedAxis == null) {
                    val dx = abs(total.x)
                    val dy = abs(total.y)
                    if (dx > slop || dy > slop) {
                        state.lock(if (dx >= dy) Orientation.Horizontal else Orientation.Vertical)
                    }
                }
            }
            // 所有指针抬起：锁保留（覆盖抬手后的 fling），下次 DOWN 重置。
        }
    }

/**
 * 轴封锁 NestedScrollConnection：当方向锁锁在**另一轴**时，把**本轴分量**
 * 的滚动增量与惯性全部消费（页面在本轴位移恒为零）。
 *
 * 注意必须按轴拆分量、不能整体 return available：嵌套滚动派发是子→父，
 * 本连接会看到子树里**所有** scrollable 的增量（pager 外层的连接同样会
 * 收到页内 LazyColumn 的竖向增量）——整体消费会把"另一轴的合法滚动"
 * 一并吃掉（此前的全页竖滚死亡即是此因）。
 */
private class GestureAxisBlocker(
    private val state: GestureDirectionLockState,
    private val axis: Orientation,
) : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val locked = state.lockedAxis ?: return Offset.Zero
        if (locked == axis) return Offset.Zero
        return if (axis == Orientation.Horizontal) Offset(available.x, 0f) else Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val locked = state.lockedAxis ?: return Velocity.Zero
        if (locked == axis) return Velocity.Zero
        return if (axis == Orientation.Horizontal) Velocity(available.x, 0f) else Velocity(0f, available.y)
    }
}

/**
 * 给本层子树的 scrollable 加轴封锁：方向锁锁在另一轴期间，本轴滚动增量
 * 全被吃掉（AwaitGesturePickup 复活接管也滚不动）。
 *
 * 例：HorizontalPager 外层传 [Orientation.Horizontal]——锁为 Vertical
 *（正在竖滚页面）时 pager 横向增量被吃掉，翻不了页；LazyColumn 外层传
 * [Orientation.Vertical]——锁为 Horizontal（正在横滑）时竖向增量被吃掉。
 * 本轴自己持锁时（锁 == [axis]）不封锁，正常滚动。
 */
fun Modifier.blockScrollAxis(
    state: GestureDirectionLockState,
    axis: Orientation,
): Modifier = nestedScroll(GestureAxisBlocker(state, axis))