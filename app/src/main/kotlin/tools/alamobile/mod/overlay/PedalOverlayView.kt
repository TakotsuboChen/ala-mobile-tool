package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge

/**
 * Dual-zone vertical pedal overlay.
 *
 * The touch area is split vertically:
 * - Top half: throttle. Finger near the top => full throttle; at the
 *   transition line => zero throttle.
 * - Bottom half: brake. Finger near the bottom => full brake; at the
 *   transition line => zero brake.
 *
 * TODO(human): implement updateValues(y) to map the finger Y position to
 * throttle and brake values, including a configurable deadzone and
 * transition point.
 */
class PedalOverlayView(context: Context) : View(context) {

    private val throttlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 255, 0)
    }
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 0, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var throttle = 0f
    private var brake = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f
        val throttleHeight = centerY * throttle
        val brakeHeight = centerY * brake

        canvas.drawRect(0f, centerY - throttleHeight, width.toFloat(), centerY, throttlePaint)
        canvas.drawRect(0f, centerY, width.toFloat(), centerY + brakeHeight, brakePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateValues(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                throttle = 0f
                brake = 0f
                updateNativeValues()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Maps a finger Y coordinate to throttle/brake values.
     */
    private fun updateValues(y: Float) {
        // TODO(human): replace this placeholder with your mapping.
        val centerY = height / 2f
        when {
            y < centerY -> {
                throttle = 1f - (y / centerY).coerceIn(0f, 1f)
                brake = 0f
            }
            else -> {
                throttle = 0f
                brake = ((y - centerY) / centerY).coerceIn(0f, 1f)
            }
        }
        updateNativeValues()
        invalidate()
    }

    private fun updateNativeValues() {
        NativeBridge.setThrottle(throttle)
        NativeBridge.setBrake(brake)
    }
}
