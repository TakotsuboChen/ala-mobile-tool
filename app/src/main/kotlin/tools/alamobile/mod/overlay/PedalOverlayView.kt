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

    // raw = 手指实际位移归一值（0..1），用于 onDraw 绘制，保证视觉跟手。
    private var rawThrottle = 0f
    private var rawBrake = 0f
    // mapped = 曲线变换后送 native 的值（0..1），可能 raw≠mapped。
    private var mappedThrottle = 0f
    private var mappedBrake = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 用 raw 值绘制填充——手指摸到哪，填充到哪，视觉跟手。
        // mapped 值只送 native，不影响视觉。
        when (role) {
            PedalRole.SINGLE -> {
                val centerY = height * settings.pedalTransition
                val throttleHeight = centerY * rawThrottle
                val brakeHeight = (height - centerY) * rawBrake
                canvas.drawRect(0f, centerY - throttleHeight, width.toFloat(), centerY, throttlePaint)
                canvas.drawRect(0f, centerY, width.toFloat(), centerY + brakeHeight, brakePaint)
            }
            PedalRole.THROTTLE -> {
                val h = height * rawThrottle
                canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), throttlePaint)
            }
            PedalRole.BRAKE -> {
                // 红色从手指位置往下填到 view 底部：手指顶部=红色填满整条，
                // 手指往下滑红色顶部边缘跟着下移、红色区域缩短。
                // 与 THROTTLE（从底向上填到手指）镜像对称。
                val top = height * (1f - rawBrake)
                canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), brakePaint)
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
                rawThrottle = 0f
                rawBrake = 0f
                mappedThrottle = 0f
                mappedBrake = 0f
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
        // raw = 手指位移（跟手，用于绘制）；mapped = 曲线变换后送 native。
        val transition = settings.pedalTransition.coerceIn(0.1f, 0.9f)
        val deadzone = settings.pedalDeadzone.coerceIn(0f, 0.5f)

        if (t <= transition) {
            val raw = if (transition <= 0f) 0f else 1f - (t / transition)
            val afterDead = applyDeadzone(raw, deadzone)
            rawThrottle = raw
            rawBrake = 0f
            mappedThrottle = applyCurve(afterDead, settings.throttleCurve)
            mappedBrake = 0f
        } else {
            val raw = if (transition >= 1f) 0f else (t - transition) / (1f - transition)
            val afterDead = applyDeadzone(raw, deadzone)
            rawThrottle = 0f
            rawBrake = raw
            mappedThrottle = 0f
            mappedBrake = applyCurve(afterDead, settings.brakeCurve)
        }
    }

    private fun updateDedicatedThrottle(t: Float) {
        // Top = full, bottom = zero; full travel, no deadzone/transition.
        // raw 跟手（1-t）：手指顶部=1 满油门，底部=0。
        // mapped 送 native：曲线变换后的值。
        val raw = 1f - t
        rawThrottle = raw
        rawBrake = 0f
        mappedThrottle = applyCurve(raw, settings.throttleCurve)
        mappedBrake = 0f
    }

    private fun updateDedicatedBrake(t: Float) {
        // BRAKE view：顶部=满刹车，底部=零（与 THROTTLE 对称：顶部=满，底部=零）。
        // raw=1-t 跟手：手指顶部 raw=1 红色画满，往下滑 raw 减小红色从顶往下退，
        // 视觉"红色从下往上涨到手指位置"，和油门填充方向一致。
        // mapped=applyCurve(1-t) 送 native，非线性映射。
        val raw = 1f - t
        rawThrottle = 0f
        rawBrake = raw
        mappedThrottle = 0f
        mappedBrake = applyCurve(raw, settings.brakeCurve)
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        if (deadzone <= 0f) return value
        if (value <= deadzone) return 0f
        return (value - deadzone) / (1f - deadzone)
    }

    private fun applyCurve(value: Float, curve: ModConfig.PedalCurve): Float {
        // exponent < 1 => ease-out (fast rise, soft tail), realistic feel:
        // ~30% travel yields ~60% output. LINEAR stays identity.
        // 仅作用于 mapped（送 native），不影响 raw（绘制用）。
        val exponent = when (curve) {
            ModConfig.PedalCurve.LINEAR -> 1f
            ModConfig.PedalCurve.EXPONENTIAL -> 0.42f
        }
        return value.coerceIn(0f, 1f).pow(exponent).coerceIn(0f, 1f)
    }

    private fun updateNativeValues() {
        // 送 mapped 值给 native（曲线变换后），raw 留给绘制。
        if (NativeBridge.isAvailable) {
            try {
                NativeBridge.setThrottle(mappedThrottle)
                NativeBridge.setBrake(mappedBrake)
            } catch (e: Throwable) {
                Log.w(TAG, "JNI setThrottle/setBrake failed", e)
            }
        }
    }
}
