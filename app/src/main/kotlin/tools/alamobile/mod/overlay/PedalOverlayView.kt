package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig
import kotlin.math.pow

/**
 * Dual-zone vertical pedal overlay.
 *
 * The touch area is split vertically around [ModConfig.Settings.pedalTransition].
 * - Top half: throttle. Finger at the top => full throttle; near the transition
 *   line (inside the deadzone) => zero throttle.
 * - Bottom half: brake. Finger at the bottom => full brake; near the transition
 *   line (inside the deadzone) => zero brake.
 *
 * The mapping curve can be linear, quadratic, or exponential.
 */
class PedalOverlayView(
    context: Context,
    private val settings: ModConfig.Settings = ModConfig.Settings(
        enableControlReplacement = true,
        enableAutoDrs = true,
        showOverlay = true,
        disableAutoGear = false,
        enableUnlock = false,
        pedalDeadzone = 0.05f,
        pedalTransition = 0.5f,
        pedalCurve = ModConfig.PedalCurve.LINEAR
    )
) : View(context) {

    companion object {
        private const val TAG = "AlaMobileTool"
    }

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

        val centerY = height * settings.pedalTransition
        val throttleHeight = centerY * throttle
        val brakeHeight = (height - centerY) * brake

        canvas.drawRect(0f, centerY - throttleHeight, width.toFloat(), centerY, throttlePaint)
        canvas.drawRect(0f, centerY, width.toFloat(), centerY + brakeHeight, brakePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // 用 rawY - 配置的 view top 重建相对坐标。
                // 不能用 event.getY()（相对 view 左上角）：共存版被 pairip 壳
                // 反复 relayout，view 实际位置漂移，相对坐标跟着跳，归一化后
                // throttle/brake 值抖动。rawY 是屏幕绝对坐标，不受 view 位置影响；
                // 配置值 (topPx/heightPx) 是用户配置的、不依赖运行时 layout，也稳定。
                // 原版上 view 布局稳定，配置值 == 实际值，行为不变；
                // 共存版上用配置值绕开漂移，行为与原版一致。
                val screenHeight = resources.displayMetrics.heightPixels
                val viewTop = settings.pedalPosition.topPx(screenHeight)
                val viewHeight = settings.pedalPosition.heightPx(context, screenHeight).toFloat()
                val relativeY = event.rawY - viewTop
                updateValues(relativeY, viewHeight)
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

    private fun updateValues(y: Float, viewHeight: Float) {
        if (viewHeight <= 0f) return

        val t = (y / viewHeight).coerceIn(0f, 1f)
        val transition = settings.pedalTransition.coerceIn(0.1f, 0.9f)
        val deadzone = settings.pedalDeadzone.coerceIn(0f, 0.5f)

        if (t <= transition) {
            val raw = if (transition <= 0f) 0f else 1f - (t / transition)
            throttle = applyCurve(applyDeadzone(raw, deadzone))
            brake = 0f
        } else {
            val raw = if (transition >= 1f) 0f else (t - transition) / (1f - transition)
            throttle = 0f
            brake = applyCurve(applyDeadzone(raw, deadzone))
        }

        updateNativeValues()
        invalidate()
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        if (deadzone <= 0f) return value
        if (value <= deadzone) return 0f
        return (value - deadzone) / (1f - deadzone)
    }

    private fun applyCurve(value: Float): Float {
        val exponent = when (settings.pedalCurve) {
            ModConfig.PedalCurve.LINEAR -> 1f
            ModConfig.PedalCurve.QUADRATIC -> 2f
            ModConfig.PedalCurve.EXPONENTIAL -> 2.5f
        }
        val result = value.coerceIn(0f, 1f).pow(exponent)
        return result.coerceIn(0f, 1f)
    }

    private fun updateNativeValues() {
        // Direct JNI path. With the dual-ClassLoader guard in
        // AlaMobileModule, NativeBridge.isAvailable is reliably true in both
        // original and coexistence builds, so the legacy file-based IPC
        // fallback (which raced with seek+write and caused pedal stutter)
        // has been removed.
        if (NativeBridge.isAvailable) {
            try {
                NativeBridge.setThrottle(throttle)
                NativeBridge.setBrake(brake)
            } catch (e: Throwable) {
                Log.w(TAG, "JNI setThrottle/setBrake failed", e)
            }
        }
    }
}
