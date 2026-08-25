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
 *   throttle, at bottom = zero. No transition, no deadzone. Direction
 *   reversible via [ModConfig.Settings.pedalInvert] (inverted: finger
 *   bottom = full, fills top-down).
 * - BRAKE: dedicated full-travel brake view. Finger at bottom = full
 *   brake, at top = zero. No transition, no deadzone. Direction
 *   reversible via [ModConfig.Settings.pedalInvert] (default fills
 *   bottom-up like throttle; inverted fills top-down).
 *
 * The response curve (linear / custom control point) is applied
 * on top of the raw travel; SINGLE applies throttleCurve on the throttle
 * half and brakeCurve on the brake half, DUAL applies the matching curve
 * to each dedicated view.
 */
class PedalOverlayView(
    context: Context,
    private val settings: ModConfig.Settings = ModConfig.Settings(
        pedalMode = ModConfig.PedalMode.SINGLE,
        enableAutoDrs = true,
        disableAutoGear = false,
        enableManualShift = false,
        enableUnlock = false,
        pedalDeadzone = 0.05f,
        pedalTransition = 0.5f,
        brakeTransition = 0.1f,
        pedalInvert = ModConfig.PedalInvert.OFF,
        throttleCurve = ModConfig.PedalCurve.LINEAR,
        brakeCurve = ModConfig.PedalCurve.LINEAR,
        throttleCurvePoints = emptyList(),
        brakeCurvePoints = emptyList()
    ),
    private val role: PedalRole = PedalRole.SINGLE,
    private val position: OverlayPosition = settings.pedalPosition
) : View(context) {

    enum class PedalRole { SINGLE, THROTTLE, BRAKE }

    companion object {
        private const val TAG = "AlaMobileTool"

        // 双踏板模式跨 view 仲裁：油门和刹车是两个独立 view，各自 onTouchEvent
        // 独立调 NativeBridge.setThrottle/setBrake。两指同时按下时，native 层
        // throttle/brake 字段虽不互覆盖，但游戏逻辑不允许两者同时非零（否则
        // "只有先按着的生效"——油门和刹车互相抵消）。这里持共享 raw 值，每次
        // 任意 view 更新都调 arbitrate() 按刹车过渡点规则仲裁：
        //   brake ≥ brakeTransition → 刹车优先，屏蔽油门（mappedThrottle=0）
        //   brake <  brakeTransition 且 throttle>0 → 油门优先，屏蔽刹车（mappedBrake=0）
        // 屏蔽只作用于 mapped/native；raw 仍跟手绘制（视觉反馈手指位移）。
        @Volatile private var sharedRawThrottle = 0f
        @Volatile private var sharedRawBrake = 0f
        @Volatile private var sharedBrakeTransition = 0.1f
        // 仲裁后的 mapped 值，双踏板两 view 共享——谁 update 谁送 native，
        // 但值由 arbitrate() 决定，避免"后按者覆盖"导致先按者失效。
        @Volatile private var arbitratedThrottle = 0f
        @Volatile private var arbitratedBrake = 0f
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
                // 默认（pedalInvert 不含 throttle）：raw=1-t（手指顶部=满油门）。
                // 绿色锚在底部，随 raw 增大从底部向上生长——手指往顶部拉
                // 绿色从底往上涨到手指位置，"从下往上拉"。
                // 反转（pedalInvert 含 throttle）：raw=t（手指底部=满油门）。
                // 绿色锚在顶部，随 raw 增大从顶部向下生长——手指往底部拉
                // 绿色从顶往下涨到手指位置，"从上往下拉"。
                if (settings.pedalInvert.invertThrottle) {
                    val h = height * rawThrottle
                    canvas.drawRect(0f, 0f, width.toFloat(), h, throttlePaint)
                } else {
                    val h = height * rawThrottle
                    canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), throttlePaint)
                }
            }
            PedalRole.BRAKE -> {
                // 默认（pedalInvert 不含 brake）：raw=1-t（手指顶部=满刹车）。
                // 红色锚在底部，随 raw 增大从底部向上生长——手指往顶部拉
                // 红色从底往上涨到手指位置，"从下往上拉"。
                // 反转（pedalInvert 含 brake）：raw=t（手指底部=满刹车）。
                // 红色锚在顶部，随 raw 增大从顶部向下生长——手指往底部拉
                // 红色从顶往下涨到手指位置，"从上往下拉"。
                // 两种方向 raw 都送 native mapped，游戏内输入同步反转。
                if (settings.pedalInvert.invertBrake) {
                    val bottom = height * rawBrake
                    canvas.drawRect(0f, 0f, width.toFloat(), bottom, brakePaint)
                } else {
                    val top = height * (1f - rawBrake)
                    canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), brakePaint)
                }
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
                // 双踏板：本 view 抬起要同步清共享 raw，否则另一指还在屏上时
                // 仲裁仍用旧 raw 误判。mapped 由另一 view 下次 MOVE 重新仲裁。
                if (role == PedalRole.THROTTLE) {
                    sharedRawThrottle = 0f
                    arbitratedThrottle = 0f
                } else if (role == PedalRole.BRAKE) {
                    sharedRawBrake = 0f
                    arbitratedBrake = 0f
                }
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
            mappedThrottle = applyCurve(afterDead, settings.throttleCurve, settings.throttleCurvePoints)
            mappedBrake = 0f
        } else {
            val raw = if (transition >= 1f) 0f else (t - transition) / (1f - transition)
            val afterDead = applyDeadzone(raw, deadzone)
            rawThrottle = 0f
            rawBrake = raw
            mappedThrottle = 0f
            mappedBrake = applyCurve(afterDead, settings.brakeCurve, settings.brakeCurvePoints)
        }
    }

    private fun updateDedicatedThrottle(t: Float) {
        // 默认（pedalInvert 不含 throttle）：Top = full, bottom = zero; full travel, no deadzone/transition.
        //   raw 跟手（1-t）：手指顶部=1 满油门，底部=0。
        // 反转（pedalInvert 含 throttle）：Bottom = full, top = zero.
        //   raw 跟手（t）：手指底部=1 满油门，顶部=0。
        // mapped 送 native：曲线变换后，再经双踏板仲裁（刹车优先/油门优先）。
        val raw = if (settings.pedalInvert.invertThrottle) t else 1f - t
        rawThrottle = raw
        rawBrake = 0f
        val curveMapped = applyCurve(raw, settings.throttleCurve, settings.throttleCurvePoints)
        // DUAL 仲裁：本 view 是油门，共享 rawThrottle 已更新，调 arbitrate
        // 决定 mappedThrottle（可能被刹车屏蔽）和 mappedBrake。
        arbitrateDual(curveMapped, isThrottleView = true)
    }

    private fun updateDedicatedBrake(t: Float) {
        // BRAKE view：默认顶部=满刹车、底部=零（与 THROTTLE 对称）。
        //   raw=1-t 跟手：手指顶部 raw=1 红色画满，往下滑 raw 减小，
        //   视觉"红色从底往上涨到手指位置"，和油门填充方向一致。
        // 反转（pedalInvert 含 brake）：底部=满刹车、顶部=零。
        //   raw=t 跟手：手指底部 raw=1 红色画满，往上拉 raw 减小，
        //   视觉"红色从顶往下涨到手指位置"，即用户要的"从上往下拉"。
        // raw 送绘制与仲裁判定，mapped 送 native，三者方向同步。
        val raw = if (settings.pedalInvert.invertBrake) t else 1f - t
        rawThrottle = 0f
        rawBrake = raw
        val curveMapped = applyCurve(raw, settings.brakeCurve, settings.brakeCurvePoints)
        arbitrateDual(curveMapped, isThrottleView = false)
    }

    /**
     * 双踏板跨 view 仲裁。两指同时按下时油门和刹车 view 各自独立更新，
     * 这里用 companion 共享状态仲裁：brake ≥ brakeTransition → 刹车优先屏蔽
     * 油门；brake < brakeTransition 且 throttle>0 → 油门优先屏蔽刹车。
     * 屏蔽只作用于 mapped/native，raw 仍跟手绘制（视觉反馈手指位移）。
     *
     * 调用方传本 view 曲线变换后的 mapped 值 [curveMapped]；方法内合并共享 raw
     * 状态计算仲裁结果写入 arbitratedThrottle/Brake，再由 updateNativeValues
     * 送 native。SINGLE 模式不走此路径（单 view 内 updateSingle 已自洽）。
     */
    private fun arbitrateDual(curveMapped: Float, isThrottleView: Boolean) {
        sharedBrakeTransition = settings.brakeTransition
        if (isThrottleView) {
            sharedRawThrottle = rawThrottle
            arbitratedThrottle = curveMapped
        } else {
            sharedRawBrake = rawBrake
            arbitratedBrake = curveMapped
        }
        // 仲裁规则：
        //   刹车值（raw）≥ 过渡点 → 刹车优先，油门 mapped 置 0
        //   刹车值 < 过渡点 且 油门 raw > 0 → 油门优先，刹车 mapped 置 0
        // 注意用 raw 判定（跟手、即时），用 mapped 屏蔽（送 native）。
        if (sharedRawBrake >= sharedBrakeTransition && sharedRawBrake > 0f) {
            arbitratedThrottle = 0f
            // mappedBrake 保留刹车 view 算出的值
        } else if (sharedRawThrottle > 0f) {
            arbitratedBrake = 0f
            // mappedThrottle 保留油门 view 算出的值
        }
        mappedThrottle = arbitratedThrottle
        mappedBrake = arbitratedBrake
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        if (deadzone <= 0f) return value
        if (value <= deadzone) return 0f
        return (value - deadzone) / (1f - deadzone)
    }

    private fun applyCurve(value: Float, curve: ModConfig.PedalCurve, points: List<ModConfig.CurvePoint>): Float {
        // 仅作用于 mapped（送 native），不影响 raw（绘制用）。
        // LINEAR stays identity.
        // CUSTOM：保单调三次样条（Fritsch–Carlson），恒过 (0,0)、各控制点、(1,1)，
        // 连续光滑、段内单调（无过冲/抖动）。空列表 = 只有两端点 = 线性。
        return when (curve) {
            ModConfig.PedalCurve.LINEAR -> value.coerceIn(0f, 1f)
            ModConfig.PedalCurve.CUSTOM -> ModConfig.monotoneCubic(points, value)
        }
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
