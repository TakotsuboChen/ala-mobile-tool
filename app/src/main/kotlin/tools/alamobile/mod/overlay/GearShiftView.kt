package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig

/**
 * On-screen upshift/downshift buttons.
 */
class GearShiftView(
    context: Context,
    private val settings: ModConfig.Settings
) : View(context) {

    companion object {
        private const val TAG = "AlaMobileTool"
    }

    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 0, 200, 255)
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 255, 200, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = settings.overlayBorderWidth * resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
    }

    // 圆角裁剪路径（preallocate 避免每帧分配）。
    private val clipPath = Path()

    // 输入是"透明度"比例（0=不透明，1=完全透明），返回 paint alpha 值。
    private fun alphaOf(transparency: Float): Int = ((1f - transparency.coerceIn(0f, 1f)) * 255f).toInt()

    private fun cornerRadiusPx(): Float {
        val ratio = settings.overlayCornerRadius.coerceIn(0f, 1f)
        if (ratio <= 0f) return 0f
        return ratio * (minOf(width, height) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val corner = cornerRadiusPx()
        val hasBorder = settings.overlayBorderWidth > 0f
        val sw = if (hasBorder) borderPaint.strokeWidth else 0f
        // 填充内缩量 = 边框宽度 - 1.5px 抗锯齿溢出（同 PedalOverlayView）。
        val fillInset = (sw - 1.5f).coerceAtLeast(0f)

        val needClip = corner > 0f || hasBorder
        if (needClip) {
            canvas.save()
            clipPath.reset()
            if (corner > 0f) {
                val fc = (corner - fillInset).coerceAtLeast(0f)
                clipPath.addRoundRect(fillInset, fillInset, w - fillInset, h - fillInset, fc, fc, Path.Direction.CW)
            } else {
                clipPath.addRect(fillInset, fillInset, w - fillInset, h - fillInset, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
        }

        val halfHeight = h / 2f
        canvas.drawRect(0f, 0f, w, halfHeight, upPaint)
        canvas.drawRect(0f, halfHeight, w, h, downPaint)

        canvas.drawText("+", w / 2f, halfHeight / 2f + textPaint.textSize / 3f, textPaint)
        canvas.drawText("-", w / 2f, halfHeight + halfHeight / 2f + textPaint.textSize / 3f, textPaint)

        if (needClip) canvas.restore()

        // drawRoundRect 系统原生渲染弧线（不经过 Path.op flatten），
        // 圆角与直线连接处天然平滑。
        if (hasBorder) {
            val inset = borderPaint.strokeWidth / 2f
            if (corner > 0f) {
                val bc = (corner - inset).coerceAtLeast(0f)
                canvas.drawRoundRect(inset, inset, w - inset, h - inset, bc, bc, borderPaint)
            } else {
                canvas.drawRect(inset, inset, w - inset, h - inset, borderPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val isUp = event.y < height / 2f

                Log.d(TAG, "Shift ${if (isUp) "up" else "down"}")

                // Direct JNI path. The legacy file-based IPC shift counter
                // has been removed; NativeBridge.isAvailable is reliably
                // true in both original and coexistence builds.
                if (NativeBridge.isAvailable) {
                    try {
                        if (isUp) {
                            NativeBridge.shiftUp()
                        } else {
                            NativeBridge.shiftDown()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "JNI shift failed", e)
                    }
                }

                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
