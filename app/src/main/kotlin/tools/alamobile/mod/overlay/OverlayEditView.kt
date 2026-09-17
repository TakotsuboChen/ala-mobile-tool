package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 编辑模式的单控件编辑层：在被编辑控件周围画虚线框与四角圆点，并处理
 * 拖拽移动 / 四角缩放 / 长按恢复默认。
 *
 * **本 view 铺满整屏**（MATCH_PARENT），被编辑控件的矩形是"本地坐标里的
 * 一个 RectF"，不是 view 自身的布局边界。这么做的原因见下方注释——旧实现
 * 让 view 的布局边界等于被编辑控件边界，四个角圆点的圆心正好落在 0/width、
 * 0/height 上，圆面越出自身布局边界后被裁成 1/4 个圆；依赖父容器
 * `clipChildren=false` 修不好（父链每层都要放行，改一处只会让其中几个角
 * 变样）。铺满整屏后所有绘制都落在自身边界内，**任何层级的裁剪都无从
 * 下手**，四个圆必然是完整的。
 */
class OverlayEditView(
    context: Context,
    private val target: View,
    private val minWidth: Int,
    private val minHeight: Int,
    // 长按重置到此出厂默认（OverlayPosition.DEFAULT_*）——重置才有意义，
    // 不再用运行时已保存的 position（否则"重置"只是回到当前已保存值）。
    private val defaultPosition: OverlayPosition,
    private val onChanged: ((left: Int, top: Int, width: Int, height: Int) -> Unit)?,
    // 空白判定：(x, y)（本地坐标）是否不属于任何编辑层矩形。必须由
    // OverlayManager 动态聚合全部兄弟层注入——触摸只会派给 z 序最高的
    // 编辑层，高层若只按自己的矩形判定，会把落在兄弟矩形内的点当空白
    // 吃掉，兄弟的拖拽/缩放永远收不到事件。
    private val isBlankAreaFn: (x: Float, y: Float) -> Boolean,
    // 长按空白处 3 秒触发（恢复当前显示的单/双踏板默认位置）。回调是
    // 全局语义，不局限于本层编辑的控件——空白触摸只会落在 z 序最高的
    // 编辑层上，恢复哪些控件由 OverlayManager 统一决定。
    private val onBlankLongPress: (() -> Unit)?
) : View(context) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 200, 0)
    }

    companion object {
        // 四角圆点半径（px）。圆心落在被编辑控件的角上。本 view 铺满整屏，
        // 圆点完全落在自身边界之内，故显示为完整圆形。
        const val CORNER_RADIUS = 36f

        // 角落命中半径（px）。比可视半径大一圈，方便手指点中。聚合空白判定
        // （OverlayManager.isBlankArea）也用它做矩形外扩——空白必须与"本层
        // 接管拖拽"的判定用同一个 margin，否则圈边一像素之外按住 3s 会触发
        // 全局重置而用户以为按的是控件。
        const val CORNER_HIT_RADIUS = 72f

        // 长按恢复默认的判定时长。
        const val LONG_PRESS_RESET_MS = 2000L
    }

    // onDraw 高频调用，预分配复用的对象，避免逐帧分配（DrawAllocation）。
    private val rectF = RectF()

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // 空白长按的取消位移阈值（px）：3 × touchSlop。比控件内长按（拖拽取消，
    // 用 1×slop）宽得多——空白长按没有拖拽歧义，按住不动只有生理抖动，
    // 阈值放宽到 3× 才能不把抖动误判成"挪开了"（触发不了长按的主因）。
    private val longPressCancelSlopPx = touchSlop * 3f

    private var mode = Mode.NONE
    private var startLeft = 0
    private var startTop = 0
    private var startWidth = 0
    private var startHeight = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var longPressHandled = false
    // 本次触摸流是否按在空白处（不落任何编辑层矩形内，含四角命中圈）。
    // 空白长按走全局重置路径（恢复当前显示的单/双踏板），与控件内长按
    // （重置本层控件）区分。
    private var touchStartedBlank = false

    // 被编辑控件在本 view 本地坐标系里的矩形。
    private var editLeftInternal = 0
    private var editTopInternal = 0
    private var editWidthInternal = 0
    private var editHeightInternal = 0

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (mode == Mode.NONE) {
            longPressHandled = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (touchStartedBlank) {
                // 长按空白处：恢复当前显示的单/双踏板默认位置。全局语义——
                // 触摸只会派给 z 序最高的编辑层，重置哪些控件由
                // OverlayManager 统一决定，不局限于本层编辑的控件。
                onBlankLongPress?.invoke()
            } else {
                // 长按控件内（非四角）：重置本层编辑的控件到出厂默认。
                resetPosition()
            }
        }
    }

    init {
        // 本 view 铺满整屏，**绝不能有底色**——旧实现给自己加 15% 黑底只是
        // 罩住被编辑控件，铺满后会变成整屏压暗，与独立的变暗层叠加。
        // isClickable=false：触摸完全由 onTouchEvent 显式接管（DOWN 落在
        // 目标矩形外一律返回 false，事件穿透到下层），不依赖系统点击语义。
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 只在尚未定位时回读一次布局。drag 过程中被编辑控件自己的 left/top
        // 是**异步**重排的，每帧回读会拿到旧值把正在拖的矩形拽回去（一帧
        // 抖动）；外部位移（长按重置、配置重建）走 updateTarget 直接写
        // editRect，无需靠回读发现。
        if (editWidth == 0) syncStateFromTarget()

        rectF.set(
            editLeft.toFloat(), editTop.toFloat(),
            (editLeft + editWidth).toFloat(), (editTop + editHeight).toFloat()
        )
        canvas.drawRect(rectF, borderPaint)

        // 四角圆点：圆心落在矩形四角。全屏 view 的边界远大于矩形，圆点完整。
        val r = CORNER_RADIUS
        canvas.drawCircle(rectF.left, rectF.top, r, cornerPaint)
        canvas.drawCircle(rectF.right, rectF.top, r, cornerPaint)
        canvas.drawCircle(rectF.left, rectF.bottom, r, cornerPaint)
        canvas.drawCircle(rectF.right, rectF.bottom, r, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // DOWN 分流（三路）：
        // ① 落在本层矩形（含四角命中圈）内 → 拖拽/缩放/长按重置本层控件。
        // ② 落在空白处（不属于任何编辑层矩形、也不在工具按钮上）→ **消费**
        //    事件（返回 true），只挂 3s 长按重置计时器，MOVE 不做任何事。
        //    消费是"编辑模式下游戏画面全屏不可操作"的实现点：本层铺满整屏
        //    且 z 序最上，空白触摸在这里被吃掉就到不了下层（Unity 的
        //    SurfaceView 输入、游戏菜单按钮全部失灵）。
        // ③ 落在兄弟编辑层矩形或工具按钮上 → 返回 false 让事件继续下派
        //    （兄弟编辑层处理自己的拖拽；工具按钮靠长按退出编辑模式——
        //    这条穿透路径被吃掉用户就被锁死在编辑模式里了）。
        //    ViewGroup 按 z 序逐个子 view 派发，本层返回 false 自动轮到下层。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            syncStateFromTarget()
            val margin = CORNER_HIT_RADIUS
            val inOwnRect = !(event.x < editLeft - margin || event.y < editTop - margin ||
                event.x > editLeft + editWidth + margin ||
                event.y > editTop + editHeight + margin)
            if (!inOwnRect) {
                if (isBlankAreaFn(event.x, event.y)) {
                    touchStartedBlank = true
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    longPressHandled = false
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_RESET_MS)
                    return true
                }
                // 兄弟矩形/工具按钮：穿透。
                return false
            }
            touchStartedBlank = false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = detectMode(event.x - editLeft, event.y - editTop)
                startTouchX = event.rawX
                startTouchY = event.rawY
                startWidth = editWidth
                startHeight = editHeight
                startLeft = editLeft
                startTop = editTop
                longPressHandled = false

                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_RESET_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 空白触摸流的 MOVE 不升级成拖拽——mode 保持 NONE，只等长按
                // 计时器（或抬起结束）。取消长按的位移判据**不能只用 touchSlop
                // 裸比较**：touchSlop ≈ 8dp（高密度屏 ~24px），而手指按住不动时
                // rawX/rawY 仍有 1~3px 的量化抖动 + 指尖滚动微移，逐帧累积一超
                // 阈值就把计时器摘了——实测"经常触发不了"的主因。改用**欧氏
                // 距离 + 3 倍 slop**：① 距离（hypot）而非单轴，斜向微抖两轴
                // 各只有 0.7×阈值也累积不到单轴阈值之外（原两轴独立 |dx|>slop
                // 判定对斜向更敏感）；② 阈值放大到 3×slop 才是主效——真实
                // "挪开重按"的位移远大于 3×slop（~24dp），而静止手指的漂移
                // 通常 < 1×slop，3× 既放行全部生理抖动又保留取消误触的能力。
                if (touchStartedBlank) {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (dx * dx + dy * dy > longPressCancelSlopPx * longPressCancelSlopPx) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    return true
                }
                if (mode == Mode.NONE && !longPressHandled) {
                    if (abs(event.rawX - startTouchX) > touchSlop ||
                        abs(event.rawY - startTouchY) > touchSlop
                    ) {
                        mode = Mode.MOVE
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }

                when (mode) {
                    Mode.MOVE -> handleMove(event)
                    Mode.RESIZE_BOTTOM_RIGHT -> handleResizeBottomRight(event)
                    Mode.RESIZE_BOTTOM_LEFT -> handleResizeBottomLeft(event)
                    Mode.RESIZE_TOP_RIGHT -> handleResizeTopRight(event)
                    Mode.RESIZE_TOP_LEFT -> handleResizeTopLeft(event)
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                mode = Mode.NONE
                touchStartedBlank = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectMode(x: Float, y: Float): Mode {
        val cornerRadius = CORNER_HIT_RADIUS
        val w = editWidth.toFloat()
        val h = editHeight.toFloat()

        if (hypot(x - w, y - h) < cornerRadius) return Mode.RESIZE_BOTTOM_RIGHT
        if (hypot(x, y - h) < cornerRadius) return Mode.RESIZE_BOTTOM_LEFT
        if (hypot(x - w, y) < cornerRadius) return Mode.RESIZE_TOP_RIGHT
        if (hypot(x, y) < cornerRadius) return Mode.RESIZE_TOP_LEFT

        return Mode.NONE
    }

    private fun handleMove(event: MotionEvent) {
        val deltaX = (event.rawX - startTouchX).toInt()
        val deltaY = (event.rawY - startTouchY).toInt()
        updateTarget(max(0, startLeft + deltaX), max(0, startTop + deltaY), startWidth, startHeight)
    }

    private fun handleResizeBottomRight(event: MotionEvent) {
        val newWidth = max(minWidth, startWidth + (event.rawX - startTouchX).toInt())
        val newHeight = max(minHeight, startHeight + (event.rawY - startTouchY).toInt())
        updateTarget(startLeft, startTop, newWidth, newHeight)
    }

    private fun handleResizeBottomLeft(event: MotionEvent) {
        val right = startLeft + startWidth
        val newWidth = max(minWidth, startWidth - (event.rawX - startTouchX).toInt())
        val newHeight = max(minHeight, startHeight + (event.rawY - startTouchY).toInt())
        updateTarget(right - newWidth, startTop, newWidth, newHeight)
    }

    private fun handleResizeTopRight(event: MotionEvent) {
        val bottom = startTop + startHeight
        val newWidth = max(minWidth, startWidth + (event.rawX - startTouchX).toInt())
        val newHeight = max(minHeight, startHeight - (event.rawY - startTouchY).toInt())
        updateTarget(startLeft, bottom - newHeight, newWidth, newHeight)
    }

    private fun handleResizeTopLeft(event: MotionEvent) {
        val right = startLeft + startWidth
        val bottom = startTop + startHeight
        val newWidth = max(minWidth, startWidth - (event.rawX - startTouchX).toInt())
        val newHeight = max(minHeight, startHeight - (event.rawY - startTouchY).toInt())
        updateTarget(right - newWidth, bottom - newHeight, newWidth, newHeight)
    }

    private fun updateTarget(left: Int, top: Int, width: Int, height: Int) {
        editLeftInternal = max(0, left)
        editTopInternal = max(0, top)
        editWidthInternal = max(minWidth, width)
        editHeightInternal = max(minHeight, height)

        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = editWidthInternal
        params.height = editHeightInternal
        params.leftMargin = editLeftInternal
        params.topMargin = editTopInternal
        target.layoutParams = params
        onChanged?.invoke(editLeftInternal, editTopInternal, editWidthInternal, editHeightInternal)

        // 本 view 铺满整屏、布局不变，只需重绘矩形。
        invalidate()
    }

    private fun resetPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        resetToDefault(screenWidth, screenHeight)
    }

    /**
     * 全局重置入口（OverlayManager 长按空白处 3s 调）：按给定屏幕尺寸把
     * 本层编辑的控件恢复出厂默认。与 [resetPosition] 同一落点，只是屏幕
     * 尺寸由调用方传入。
     */
    fun resetToDefault(screenWidth: Int, screenHeight: Int) {
        updateTarget(
            defaultPosition.leftPx(screenWidth),
            defaultPosition.topPx(screenHeight),
            defaultPosition.widthPx(context, screenWidth),
            defaultPosition.heightPx(context, screenHeight)
        )
        invalidate()
    }

    // 聚合空白判定用（OverlayManager.isBlankArea 读）：本层编辑矩形的本地
    // 坐标。编辑层与兄弟层同父同原点，坐标跨层直接可比。
    val editLeft: Int get() = editLeftInternal
    val editTop: Int get() = editTopInternal
    val editWidth: Int get() = editWidthInternal
    val editHeight: Int get() = editHeightInternal

    /**
     * 把被编辑控件在父容器中的矩形换算到本 view 的本地坐标系。
     * 两者是同一个父容器的兄弟 view，`target.left - this.left` 直接给出
     * 相对偏移，父容器有 padding 也自动抵消。
     */
    private fun syncStateFromTarget() {
        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        editWidthInternal = max(minWidth, params.width)
        editHeightInternal = max(minHeight, params.height)
        // 被编辑控件已完成布局时用实际坐标（含父容器 padding 的精确贡献）；
        // 尚未布局（width==0）时回退到 margin，避免短暂画到左上角。
        val laidOut = target.width > 0
        editLeftInternal = max(0, if (laidOut) target.left - left else params.leftMargin)
        editTopInternal = max(0, if (laidOut) target.top - top else params.topMargin)
    }

    private enum class Mode {
        NONE, MOVE, RESIZE_TOP_LEFT, RESIZE_TOP_RIGHT, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT
    }
}
