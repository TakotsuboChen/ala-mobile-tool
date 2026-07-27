package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Touch layer used to reposition/resize a target overlay view.
 *
 * Gestures:
 * - Drag inside the view body: move the view.
 * - Drag a corner circle: resize freely (width/height independent).
 * - Long-press without moving: reset to the default position and size.
 */
class OverlayEditView(
    context: Context,
    private val target: View,
    private val minWidth: Int,
    private val minHeight: Int,
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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var startWidth = 0
    private var startHeight = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var longPressHandled = false

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (mode == Mode.NONE) {
            longPressHandled = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            resetPosition()
        }
    }

    private val parent: android.view.ViewGroup?
        get() = target.parent as? android.view.ViewGroup

    init {
        setBackgroundColor(Color.argb(40, 0, 0, 0))
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = Rect().apply { target.getHitRect(this) }
        canvas.drawRect(rect, borderPaint)
        val cornerSize = 24f
        canvas.drawCircle(rect.left.toFloat(), rect.top.toFloat(), cornerSize, cornerPaint)
        canvas.drawCircle(rect.right.toFloat(), rect.top.toFloat(), cornerSize, cornerPaint)
        canvas.drawCircle(rect.left.toFloat(), rect.bottom.toFloat(), cornerSize, cornerPaint)
        canvas.drawCircle(rect.right.toFloat(), rect.bottom.toFloat(), cornerSize, cornerPaint)

        canvas.drawText(
            "拖拽移动 · 拖角落缩放 · 长按重置",
            width / 2f,
            rect.top - hintPaint.textSize,
            hintPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = detectMode(event.x, event.y)
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                startTouchX = event.x
                startTouchY = event.y
                startWidth = target.width
                startHeight = target.height
                val loc = intArrayOf(0, 0)
                target.getLocationOnScreen(loc)
                val parentLoc = intArrayOf(0, 0)
                (parent as? View)?.getLocationOnScreen(parentLoc)
                startLeft = loc[0] - parentLoc[0]
                startTop = loc[1] - parentLoc[1]
                longPressHandled = false

                longPressHandler.postDelayed(longPressRunnable, 500)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) {
                    if (kotlin.math.abs(event.x - startTouchX) > touchSlop ||
                        kotlin.math.abs(event.y - startTouchY) > touchSlop) {
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

                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                val wasClick = mode == Mode.NONE && !longPressHandled
                if (wasClick) {
                    parent?.let { p ->
                        for (i in 0 until p.childCount) {
                            val child = p.getChildAt(i)
                            if (child is OverlayEditView && child != this) {
                                child.visibility = if (child.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            }
                        }
                    }
                }
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectMode(x: Float, y: Float): Mode {
        val rect = Rect().apply { target.getHitRect(this) }
        val cornerRadius = 48f

        // Check corners first
        if (distance(x, y, rect.right.toFloat(), rect.bottom.toFloat()) < cornerRadius) return Mode.RESIZE_BOTTOM_RIGHT
        if (distance(x, y, rect.left.toFloat(), rect.bottom.toFloat()) < cornerRadius) return Mode.RESIZE_BOTTOM_LEFT
        if (distance(x, y, rect.right.toFloat(), rect.top.toFloat()) < cornerRadius) return Mode.RESIZE_TOP_RIGHT
        if (distance(x, y, rect.left.toFloat(), rect.top.toFloat()) < cornerRadius) return Mode.RESIZE_TOP_LEFT

        return Mode.NONE
    }

    private fun handleMove(event: MotionEvent) {
        val dx = event.x - lastX
        val dy = event.y - lastY
        val loc = intArrayOf(0, 0)
        target.getLocationOnScreen(loc)
        val parentLoc = intArrayOf(0, 0)
        (parent as? View)?.getLocationOnScreen(parentLoc)
        val left = loc[0] - parentLoc[0] + dx.toInt()
        val top = loc[1] - parentLoc[1] + dy.toInt()
        updateTarget(left, top, target.width, target.height)
    }

    private fun handleResizeBottomRight(event: MotionEvent) {
        val newWidth = max(minWidth, (event.x - startLeft).toInt())
        val newHeight = max(minHeight, (event.y - startTop).toInt())
        updateTarget(startLeft, startTop, newWidth, newHeight)
    }

    private fun handleResizeBottomLeft(event: MotionEvent) {
        val right = startLeft + startWidth
        val newWidth = max(minWidth, (right - event.x).toInt())
        val newHeight = max(minHeight, (event.y - startTop).toInt())
        val newLeft = right - newWidth
        updateTarget(newLeft, startTop, newWidth, newHeight)
    }

    private fun handleResizeTopRight(event: MotionEvent) {
        val bottom = startTop + startHeight
        val newWidth = max(minWidth, (event.x - startLeft).toInt())
        val newHeight = max(minHeight, (bottom - event.y).toInt())
        val newTop = bottom - newHeight
        updateTarget(startLeft, newTop, newWidth, newHeight)
    }

    private fun handleResizeTopLeft(event: MotionEvent) {
        val right = startLeft + startWidth
        val bottom = startTop + startHeight
        val newWidth = max(minWidth, (right - event.x).toInt())
        val newHeight = max(minHeight, (bottom - event.y).toInt())
        val newLeft = right - newWidth
        val newTop = bottom - newHeight
        updateTarget(newLeft, newTop, newWidth, newHeight)
    }

    private fun updateTarget(left: Int, top: Int, width: Int, height: Int) {
        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = width
        params.height = height
        params.leftMargin = max(0, left)
        params.topMargin = max(0, top)
        target.layoutParams = params
        onChanged?.invoke(params.leftMargin, params.topMargin, width, height)
        invalidate()
    }

    private fun resetPosition() {
        val params = target.layoutParams as? FrameLayout.LayoutParams ?: return
        val default = OverlayPosition.DEFAULT_PEDAL
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        params.leftMargin = default.leftPx(screenWidth)
        params.topMargin = default.topPx(screenHeight)
        params.width = default.widthPx(context, screenWidth)
        params.height = default.heightPx(context, screenHeight)
        target.layoutParams = params
        onChanged?.invoke(params.leftMargin, params.topMargin, params.width, params.height)
        invalidate()
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return kotlin.math.hypot(x1 - x2, y1 - y2)
    }

    private enum class Mode {
        NONE, MOVE, RESIZE_TOP_LEFT, RESIZE_TOP_RIGHT, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT
    }
}
