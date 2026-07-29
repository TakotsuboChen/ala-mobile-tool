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
 * Vertical pedal overlay view. Supports three topologies via [PedalRole]:
 *
 * - SINGLE: one view split into throttle (top) + brake (bottom) around
 *   [ModConfig.Settings.pedalTransition]; deadzone applies around the
 *   transition line. Finger at the top = full throttle, at the bottom =
 *   full brake.
 * - THROTTLE: dedicated full-travel throttle view. Finger at top = full
 *   throttle, at bottom = zero. No transition, no deadzone.
 * - BRAKE: dedicated full-travel brake view. Finger at bottom = full
 *   brake, at top = zero. No transition, no deadzone.
 *
 * The response curve (linear / exponential ease-out ≈ 30%→60%) is applied
 * on top of the raw travel; SINGLE applies throttleCurve on the throttle
 * half and brakeCurve on the brake half, DUAL applies the matching curve
 * to each dedicated view.
 */
class PedalOverlayView(
    context: Context,
    private val settings: ModConfig.Settings = ModConfig.Settings(
        pedalMode = ModConfig.PedalMode.SINGLE,
        enableAutoDrs = true,
        showOverlay = true,
        disableAutoGear = false,
        enableManualShift = false,
        enableUnlock = false,
        pedalDeadzone = 0.05f,
        pedalTransition = 0.5f,
        throttleCurve = ModConfig.PedalCurve.LINEAR,
        brakeCurve = ModConfig.PedalCurve.LINEAR
    ),
    private val role: PedalRole = PedalRole.SINGLE,
    private val position: OverlayPosition = settings.pedalPosition
) : View(context) {

    enum class PedalRole { SINGLE, THROTTLE, BRAKE }

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

        when (role) {
            PedalRole.SINGLE -> {
                val centerY = height * settings.pedalTransition
                val throttleHeight = centerY * throttle
                val brakeHeight = (height - centerY) * brake
                canvas.drawRect(0f, centerY - throttleHeight, width.toFloat(), centerY, throttlePaint)
                canvas.drawRect(0f, centerY, width.toFloat(), centerY + brakeHeight, brakePaint)
            }
            PedalRole.THROTTLE -> {
                val h = height * throttle
                canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), throttlePaint)
            }
            PedalRole.BRAKE -> {
                val h = height * brake
                canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), brakePaint)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // 用 rawY - 配置的 view top 重建相对坐标。
                // 不能用 event.getY()（相对 view 左上角）：共存版被 pairip
                // 壳反复 relayout，view 实际位置漂移，相对坐标跟着跳，归一化后
                // throttle/brake 值抖动。rawY 是屏幕绝对坐标，不受 view 位置影响；
                // 配置值 (topPx/heightPx) 是用户配置的、不依赖运行时 layout，也稳定。
                // 原版上 view 布局稳定，配置值 == 实际值，行为不变；
                // 共存版上用配置值绕开漂移，行为与原版一致。
                val screenHeight = resources.displayMetrics.heightPixels
                val viewTop = position.topPx(screenHeight)
                val viewHeight = position.heightPx(context, screenHeight).toFloat()
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

        when (role) {
            PedalRole.SINGLE -> updateSingle(t)
            PedalRole.THROTTLE -> updateDedicatedThrottle(t)
            PedalRole.BRAKE -> updateDedicatedBrake(t)
        }

        updateNativeValues()
        invalidate()
    }

    private fun updateSingle(t: Float) {
        // Split around transition; each half normalized 0..1, deadzone applied
        // near the transition line so resting/misplaced fingers don't bleed input.
        val transition = settings.pedalTransition.coerceIn(0.1f, 0.9f)
        val deadzone = settings.pedalDeadzone.coerceIn(0f, 0.5f)

        if (t <= transition) {
            val raw = if (transition <= 0f) 0f else 1f - (t / transition)
            throttle = applyCurve(applyDeadzone(raw, deadzone), settings.throttleCurve)
            brake = 0f
        } else {
            val raw = if (transition >= 1f) 0f else (t - transition) / (1f - transition)
            throttle = 0f
            brake = applyCurve(applyDeadzone(raw, deadzone), settings.brakeCurve)
        }
    }

    private fun updateDedicatedThrottle(t: Float) {
        // Top = full, bottom = zero; full travel, no deadzone/transition.
        throttle = applyCurve(1f - t, settings.throttleCurve)
        brake = 0f
    }

    private fun updateDedicatedBrake(t: Float) {
        // Bottom = full, top = zero; full travel, no deadzone/transition.
        throttle = 0f
        brake = applyCurve(t, settings.brakeCurve)
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        if (deadzone <= 0f) return value
        if (value <= deadzone) return 0f
        return (value - deadzone) / (1f - deadzone)
    }

    private fun applyCurve(value: Float, curve: ModConfig.PedalCurve): Float {
        // exponent < 1 => ease-out (fast rise, soft tail), realistic feel:
        // ~30% travel yields ~60% output. LINEAR stays identity.
        val exponent = when (curve) {
            ModConfig.PedalCurve.LINEAR -> 1f
            ModConfig.PedalCurve.EXPONENTIAL -> 0.42f
        }
        return value.coerceIn(0f, 1f).pow(exponent).coerceIn(0f, 1f)
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
