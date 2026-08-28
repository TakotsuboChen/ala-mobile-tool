package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
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

    // 层内绘制全部用不透明色；控件透明度由 layerPaint 在合成时统一
    // 应用（同 PedalOverlayView——半透明以整层 alpha 承担，逐像素
    // 半透明会让"边框遮盖填充溢出"失效）。
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 200, 255)
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 200, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = settings.overlayBorderWidth * resources.displayMetrics.density
    }
    // 边框环（drawDoubleRoundRect FILL 模式，API 29+）。
    private val borderFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // 整层合成透明度（overlayAlpha → paint alpha）。
    private val layerPaint = Paint().apply {
        alpha = alphaOf(settings.overlayAlpha)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
    }

    // 边框环的内外边界（preallocate 避免每帧分配，DRRect 用）。
    private val outerRect = RectF()
    private val innerRect = RectF()

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
        // 半透明与缝隙处理同 PedalOverlayView：层内全不透明 + 填充比边框
        // 内缘多溢出 1.5px 伸进边框区（fillInset = sw - 1.5f，内缩量变小
        // 才是伸进边框，方向别写反）垫边框渐变带 + 不透明边框遮盖溢出，
        // 透明度由 layer alpha 统一承担。
        val fillInset = if (hasBorder) (sw - 1.5f).coerceAtLeast(0f) else 0f
        val fc = (corner - fillInset).coerceAtLeast(0f)

        val sc = canvas.saveLayer(0f, 0f, w, h, layerPaint)

        // 色带裁剪后画完整控件圆角矩形（溢出藏在边框下）。
        fun drawBand(top: Float, bottom: Float, paint: Paint) {
            canvas.save()
            canvas.clipRect(0f, top, w, bottom)
            canvas.drawRoundRect(fillInset, fillInset, w - fillInset, h - fillInset, fc, fc, paint)
            canvas.restore()
        }

        val halfHeight = h / 2f
        drawBand(0f, halfHeight, upPaint)
        drawBand(halfHeight, h, downPaint)

        // 边框最后画在填充之上（层内不透明，环实体区完全遮盖填充溢出），
        // 同 PedalOverlayView：API 29+ 用 drawDoubleRoundRect 环带，
        // API 26-28 回退 STROKE。
        if (hasBorder) {
            if (Build.VERSION.SDK_INT >= 29) {
                val bc = (corner - sw).coerceAtLeast(0f)
                outerRect.set(0f, 0f, w, h)
                innerRect.set(sw, sw, w - sw, h - sw)
                canvas.drawDoubleRoundRect(outerRect, corner, corner, innerRect, bc, bc, borderFillPaint)
            } else {
                val inset = sw / 2f
                val bc = (corner - inset).coerceAtLeast(0f)
                if (corner > 0f) {
                    canvas.drawRoundRect(inset, inset, w - inset, h - inset, bc, bc, borderPaint)
                } else {
                    canvas.drawRect(inset, inset, w - inset, h - inset, borderPaint)
                }
            }
        }

        canvas.restoreToCount(sc)

        // 文字在层外画，保持不透明白（不随控件透明度变淡）。
        canvas.drawText("+", w / 2f, halfHeight / 2f + textPaint.textSize / 3f, textPaint)
        canvas.drawText("-", w / 2f, halfHeight + halfHeight / 2f + textPaint.textSize / 3f, textPaint)
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
