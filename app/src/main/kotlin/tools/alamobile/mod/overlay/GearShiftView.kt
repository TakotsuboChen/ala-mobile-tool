package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge

/**
 * On-screen upshift/downshift buttons.
 */
class GearShiftView(context: Context) : View(context) {

    companion object {
        private const val TAG = "AlaMobileTool"
    }

    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 200, 255)
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 200, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val halfHeight = height / 2f
        canvas.drawRect(0f, 0f, width.toFloat(), halfHeight, upPaint)
        canvas.drawRect(0f, halfHeight, width.toFloat(), height.toFloat(), downPaint)

        canvas.drawText("+", width / 2f, halfHeight / 2f + textPaint.textSize / 3f, textPaint)
        canvas.drawText("-", width / 2f, halfHeight + halfHeight / 2f + textPaint.textSize / 3f, textPaint)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val isUp = event.y < height / 2f

                // Increment the shared shift counter.
                // Odd values = shift up, even values = shift down.
                // Native detects the *change* in value to fire a one-shot command.
                PedalOverlayView.sharedShiftCmd++
                val cmd = PedalOverlayView.sharedShiftCmd
                if (isUp && cmd % 2 != 1) {
                    PedalOverlayView.sharedShiftCmd++
                } else if (!isUp && cmd % 2 != 0) {
                    PedalOverlayView.sharedShiftCmd++
                }

                Log.d(TAG, "Shift ${if (isUp) "up" else "down"}, cmd=${PedalOverlayView.sharedShiftCmd}")

                // Flush to IPC file (works for ALL builds)
                PedalOverlayView.flushToIpc()

                // Also try direct JNI (fast path for original build)
                if (NativeBridge.isAvailable) {
                    try {
                        if (isUp) {
                            NativeBridge.shiftUp()
                        } else {
                            NativeBridge.shiftDown()
                        }
                    } catch (_: Throwable) {
                        // JNI may fail in coexistence builds; file IPC is the fallback
                    }
                }

                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
