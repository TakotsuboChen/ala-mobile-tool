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
    private val onChanged: ((left: Int, top: Int, width: Int, height: Int) -> Unit)?
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

        // 角落命中半径（px）。比可视半径大一圈，方便手指点中。
        private const val CORNER_HIT_RADIUS = 72f

        // 长按恢复默认的判定时长。EditHintView 的提示文字引用此常量，
        // 避免改了一处忘了另一处。
        const val LONG_PRESS_RESET_MS = 3000L
    }

    // onDraw 高频调用，预分配复用的对象，避免逐帧分配（DrawAllocation）。
    private val rectF = RectF()

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private var mode = Mode.NONE
    private var startLeft = 0
    private var startTop = 0
    private var startWidth = 0
    private var startHeight = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var longPressHandled = false

    // 被编辑控件在本 view 本地坐标系里的矩形。
    private var editLeft = 0
    private var editTop = 0
    private var editWidth = 0
    private var editHeight = 0

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (mode == Mode.NONE) {
            longPressHandled = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            resetPosition()
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
        // DOWN 落在被编辑控件（含四角命中圈）之外时**必须返回 false 让事件
        // 穿透**：本层铺满整屏，若一律接管，工具按钮、其他控件与游戏画面
        // 全都失灵。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            syncStateFromTarget()
            val margin = CORNER_HIT_RADIUS
            if (event.x < editLeft - margin || event.y < editTop - margin ||
                event.x > editLeft + editWidth + margin ||
                event.y > editTop + editHeight + margin
            ) {
                return false
            }
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
        editLeft = max(0, left)
        editTop = max(0, top)
        editWidth = max(minWidth, width)
        editHeight = max(minHeight, height)

        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = editWidth
        params.height = editHeight
        params.leftMargin = editLeft
        params.topMargin = editTop
        target.layoutParams = params
        onChanged?.invoke(editLeft, editTop, editWidth, editHeight)

        // 本 view 铺满整屏、布局不变，只需重绘矩形。
        invalidate()
    }

    private fun resetPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        updateTarget(
            defaultPosition.leftPx(screenWidth),
            defaultPosition.topPx(screenHeight),
            defaultPosition.widthPx(context, screenWidth),
            defaultPosition.heightPx(context, screenHeight)
        )
        invalidate()
    }

    /**
     * 把被编辑控件在父容器中的矩形换算到本 view 的本地坐标系。
     * 两者是同一个父容器的兄弟 view，`target.left - this.left` 直接给出
     * 相对偏移，父容器有 padding 也自动抵消。
     */
    private fun syncStateFromTarget() {
        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        editWidth = max(minWidth, params.width)
        editHeight = max(minHeight, params.height)
        // 被编辑控件已完成布局时用实际坐标（含父容器 padding 的精确贡献）；
        // 尚未布局（width==0）时回退到 margin，避免短暂画到左上角。
        val laidOut = target.width > 0
        editLeft = max(0, if (laidOut) target.left - left else params.leftMargin)
        editTop = max(0, if (laidOut) target.top - top else params.topMargin)
    }

    private enum class Mode {
        NONE, MOVE, RESIZE_TOP_LEFT, RESIZE_TOP_RIGHT, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT
    }
}
