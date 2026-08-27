package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.util.Logger
import kotlin.math.abs
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
        brakeTransition = 0.2f,
        throttleTransition = 0.2f,
        pedalPriority = ModConfig.PedalPriority.BRAKE_VALUE,
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
        // 任意 view 更新都调 arbitrate() 按 [PedalPriority] 策略仲裁：
        //   FIRST_PRESSED：最早持续按住的踏板优先，抬起后另一踏板接管
        //   LAST_TOUCHED：最新触摸的踏板优先
        //   ALWAYS_THROTTLE：油门有值时始终屏蔽刹车
        //   ALWAYS_BRAKE：刹车有值时始终屏蔽油门
        //   THROTTLE_VALUE：throttle ≥ throttleTransition → 油门优先屏蔽刹车
        //   BRAKE_VALUE：brake ≥ brakeTransition → 刹车优先屏蔽油门
        // 屏蔽只作用于 mapped/native；raw 仍跟手绘制（视觉反馈手指位移）。
        @Volatile private var sharedRawThrottle = 0f
        @Volatile private var sharedRawBrake = 0f
        @Volatile private var sharedBrakeTransition = 0.2f
        @Volatile private var sharedThrottleTransition = 0.2f
        @Volatile private var sharedPedalPriority = ModConfig.PedalPriority.BRAKE_VALUE
        // FIRST_PRESSED / LAST_TOUCHED 策略的时序状态（跨 view 共享）。
        @Volatile private var sharedFirstPressed: PedalRole? = null
        @Volatile private var sharedLastTouched: PedalRole? = null
        // 仲裁后的 mapped 值，双踏板两 view 共享——谁 update 谁送 native，
        // 但值由 arbitrate() 决定，避免"后按者覆盖"导致先按者失效。
        @Volatile private var arbitratedThrottle = 0f
        @Volatile private var arbitratedBrake = 0f
    }

    private val throttlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 0, 255, 0)
    }
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 255, 0, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alphaOf(settings.overlayAlpha), 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = settings.overlayBorderWidth * resources.displayMetrics.density
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

    // raw = 手指实际位移归一值（0..1），用于 onDraw 绘制，保证视觉跟手。
    private var rawThrottle = 0f
    private var rawBrake = 0f
    // mapped = 曲线变换后送 native 的值（0..1），可能 raw≠mapped。
    private var mappedThrottle = 0f
    private var mappedBrake = 0f

    // Active pointer 跟踪：记录当前控制踏板的手指 pointerId，
    // 防止其他手指的触摸事件干扰踏板值。详见 onTouchEvent。
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // ── 触摸诊断日志（多指漂移排查，logEnabled 门控，写入导出日志）──
    // MOVE 节流状态（实例级，DUAL 两 view 各自节流）：最近一次 MOVE 日志的
    // 归一 t 与时间戳。滑动按行程采样（>2%），静止时 500ms 心跳——心跳能
    // 揭示"手指没动值在变"的竞态。
    private var diagLastT = -1f
    private var diagLastLogMs = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = cornerRadiusPx()
        val hasBorder = settings.overlayBorderWidth > 0f
        val sw = if (hasBorder) borderPaint.strokeWidth else 0f
        // 填充内缩量 = 边框宽度 - 1.5px 抗锯齿溢出。填充比边框内边缘多溢出
        // 1.5px 到边框下面，覆盖 clipPath 硬裁剪与 drawRoundRect STROKE 在圆角
        // 处的抗锯齿过渡差异——消除"不均匀缝隙"。1.5px 在视觉上不可见。
        val fillInset = (sw - 1.5f).coerceAtLeast(0f)

        // 裁剪：有圆角或有边框时都需要裁剪填充区域。
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

        // 用 raw 值绘制填充——手指摸到哪，填充到哪，视觉跟手。
        // mapped 值只送 native，不影响视觉。
        when (role) {
            PedalRole.SINGLE -> {
                val centerY = h * settings.pedalTransition
                val throttleHeight = centerY * rawThrottle
                val brakeHeight = (h - centerY) * rawBrake
                canvas.drawRect(0f, centerY - throttleHeight, w, centerY, throttlePaint)
                canvas.drawRect(0f, centerY, w, centerY + brakeHeight, brakePaint)
            }
            PedalRole.THROTTLE -> {
                // 默认（pedalInvert 不含 throttle）：raw=1-t（手指顶部=满油门）。
                // 绿色锚在底部，随 raw 增大从底部向上生长——手指往顶部拉
                // 绿色从底往上涨到手指位置，"从下往上拉"。
                // 反转（pedalInvert 含 throttle）：raw=t（手指底部=满油门）。
                // 绿色锚在顶部，随 raw 增大从顶部向下生长——手指往底部拉
                // 绿色从顶往下涨到手指位置，"从上往下拉"。
                if (settings.pedalInvert.invertThrottle) {
                    val fillH = h * rawThrottle
                    canvas.drawRect(0f, 0f, w, fillH, throttlePaint)
                } else {
                    val fillH = h * rawThrottle
                    canvas.drawRect(0f, h - fillH, w, h, throttlePaint)
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
                    val bottom = h * rawBrake
                    canvas.drawRect(0f, 0f, w, bottom, brakePaint)
                } else {
                    val top = h * (1f - rawBrake)
                    canvas.drawRect(0f, top, w, h, brakePaint)
                }
            }
        }

        // 边框在 restore 之后画：stroke 沿圆角路径描边，若在 clip 内画，
        // 外半圈会被裁掉，边框看起来只有一半粗细。
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                // DOWN 打布局对比：view 实际屏幕位置 vs 配置换算位置。共存版
                // pairip 壳可能反复 relayout 移动 view——若两者不一致，
                // rawY−配置topPx 的换算基准失真，踏板值随 relayout 漂移。
                // 该日志直接证实/证伪布局漂移假设。
                logEvent(event, "DOWN", layoutDiag())
                updateValuesFromPointer(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateValuesFromPointer(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 新手指按下，不改变 active pointer——踏板始终由第一指控制。
                // 消费事件避免落入 super（返回 false 可能导致系统误判）。
                logEvent(event, "POINTER_DOWN",
                    "newId=${event.getPointerId(event.actionIndex)} → keep active")
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 如果抬起的是 active pointer（控制踏板的手指）→ 重置踏板；
                // 非 active pointer 抬起 → 忽略，不影响踏板值。
                val pointerId = event.getPointerId(event.actionIndex)
                val isActive = pointerId == activePointerId
                logEvent(event, "POINTER_UP",
                    "upId=$pointerId ${if (isActive) "→ RESET" else "→ ignore"}")
                if (isActive) {
                    resetPedalState()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                logEvent(event,
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) "CANCEL" else "UP",
                    "→ RESET")
                resetPedalState()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // 触摸诊断日志 helper（logEnabled 门控，只省字符串构造，logcat 由
    // Logger 内部决定）：低频事件（DOWN/POINTER_*/UP/CANCEL）全打。
    private fun logEvent(event: MotionEvent, action: String, extra: String) {
        if (!Logger.isEnabled()) return
        Logger.i("pedal[$role] $action activeId=$activePointerId ptrCount=${event.pointerCount} $extra")
    }

    // 布局诊断：view 实际屏幕位置/尺寸 vs 配置值。两者不一致 = 布局漂移
    //（共存版 pairip 壳 relayout），是踏板值漂移的候选根因。
    private fun layoutDiag(): String {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenHeight = resources.displayMetrics.heightPixels
        return "onScreen=(${loc[0]},${loc[1]}) size=${width}x$height " +
            "cfgTop=${position.topPx(screenHeight)} cfgH=${position.heightPx(context, screenHeight)}"
    }

    // 用 active pointer 的屏幕绝对坐标重建相对坐标。
    // 不能用 event.getY()（相对 view 左上角）：共存版被 pairip
    // 壳反复 relayout，view 实际位置漂移，相对坐标跟着跳，归一化后
    // throttle/brake 值抖动。rawY 是屏幕绝对坐标，不受 view 位置影响；
    // 配置值 (topPx/heightPx) 是用户配置的、不依赖运行时 layout，也稳定。
    // 原版上 view 布局稳定，配置值 == 实际值，行为不变；
    // 共存版上用配置值绕开漂移，行为与原版一致。
    //
    // 用 findPointerIndex(activePointerId) 而非 getRawY(0)：多指按下/抬起时
    // pointer index 会重新排列，index 0 可能不是控制踏板的手指，导致踏板值
    // 跳到其他手指的位置（如另一指点空白处时踏板飘到 100% 油门）。
    private fun updateValuesFromPointer(event: MotionEvent) {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex == -1) {
            // 理论不可达（active pointer 抬起会走 POINTER_UP/UP 分支并先清
            // activePointerId）。真出现说明事件流被系统重排——多指漂移的
            // 重要线索，无条件打。
            Logger.i("pedal[$role] MOVE activeId=$activePointerId NOT_FOUND ptrCount=${event.pointerCount}")
            return
        }
        val screenHeight = resources.displayMetrics.heightPixels
        val viewTop = position.topPx(screenHeight)
        val viewHeight = position.heightPx(context, screenHeight).toFloat()
        val relativeY = event.getRawY(pointerIndex) - viewTop
        updateValues(relativeY, viewHeight)
        diagMaybeLogMove(event, pointerIndex, relativeY, viewHeight)
    }

    // MOVE 高频（60Hz+）节流：t 变化超 2% 或距上条 ≥500ms 才打。
    // 记录 rawY → relY → t → raw/mapped 值的完整换算链，配合 DOWN 的
    // 布局对比日志可定位漂移发生在哪一环。
    private fun diagMaybeLogMove(event: MotionEvent, pointerIndex: Int, relativeY: Float, viewHeight: Float) {
        if (!Logger.isEnabled()) return
        val t = if (viewHeight > 0f) relativeY / viewHeight else 0f
        val now = SystemClock.uptimeMillis()
        if (abs(t - diagLastT) <= 0.02f && now - diagLastLogMs < 500L) return
        diagLastT = t
        diagLastLogMs = now
        val rawY = event.getRawY(pointerIndex)
        Logger.i(
            "pedal[$role] MOVE idx=$pointerIndex rawY=$rawY relY=$relativeY " +
                "t=${"%.3f".format(t)} thr=$rawThrottle brk=$rawBrake " +
                "mappedT=$mappedThrottle mappedB=$mappedBrake"
        )
    }

    // 重置踏板值到零，并同步清理双踏板共享状态。
    private fun resetPedalState() {
        rawThrottle = 0f
        rawBrake = 0f
        mappedThrottle = 0f
        mappedBrake = 0f
        // 双踏板：本 view 抬起要同步清共享 raw，否则另一指还在屏上时
        // 仲裁仍用旧 raw 误判。mapped 由另一 view 下次 MOVE 重新仲裁。
        if (role == PedalRole.THROTTLE) {
            sharedRawThrottle = 0f
            arbitratedThrottle = 0f
            // FIRST_PRESSED：油门抬起时清记录，让刹车 view 下次 MOVE 接管。
            if (sharedFirstPressed == PedalRole.THROTTLE) sharedFirstPressed = null
            // LAST_TOUCHED：油门抬起时切到刹车（若仍按着），否则清空。
            if (sharedLastTouched == PedalRole.THROTTLE) {
                sharedLastTouched = if (sharedRawBrake > 0f) PedalRole.BRAKE else null
            }
        } else if (role == PedalRole.BRAKE) {
            sharedRawBrake = 0f
            arbitratedBrake = 0f
            if (sharedFirstPressed == PedalRole.BRAKE) sharedFirstPressed = null
            if (sharedLastTouched == PedalRole.BRAKE) {
                sharedLastTouched = if (sharedRawThrottle > 0f) PedalRole.THROTTLE else null
            }
        }
        updateNativeValues()
        invalidate()
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
     * 这里用 companion 共享状态按 [ModConfig.PedalPriority] 策略仲裁。
     * 屏蔽只作用于 mapped/native，raw 仍跟手绘制（视觉反馈手指位移）。
     *
     * 调用方传本 view 曲线变换后的 mapped 值 [curveMapped]；方法内合并共享 raw
     * 状态计算仲裁结果写入 arbitratedThrottle/Brake，再由 updateNativeValues
     * 送 native。SINGLE 模式不走此路径（单 view 内 updateSingle 已自洽）。
     */
    private fun arbitrateDual(curveMapped: Float, isThrottleView: Boolean) {
        val priority = settings.pedalPriority
        sharedBrakeTransition = settings.brakeTransition
        sharedThrottleTransition = settings.throttleTransition
        sharedPedalPriority = priority

        // 保存旧 raw 值，供 LAST_TOUCHED 判断"按下瞬间"（0→>0）。
        val prevThrottle = sharedRawThrottle
        val prevBrake = sharedRawBrake

        if (isThrottleView) {
            sharedRawThrottle = rawThrottle
            arbitratedThrottle = curveMapped
        } else {
            sharedRawBrake = rawBrake
            arbitratedBrake = curveMapped
        }

        val role = if (isThrottleView) PedalRole.THROTTLE else PedalRole.BRAKE
        val raw = if (isThrottleView) sharedRawThrottle else sharedRawBrake

        when (priority) {
            ModConfig.PedalPriority.FIRST_PRESSED -> {
                // 最早按住的踏板优先：raw > 0 且当前无记录 → 设为当前 view；
                // raw 归零且记录是当前 view → 清除，让另一 view 下次 MOVE 接管。
                if (raw > 0f && sharedFirstPressed == null) {
                    sharedFirstPressed = role
                } else if (raw <= 0f && sharedFirstPressed == role) {
                    sharedFirstPressed = null
                }
                when (sharedFirstPressed) {
                    PedalRole.THROTTLE -> arbitratedBrake = 0f
                    PedalRole.BRAKE -> arbitratedThrottle = 0f
                    else -> { /* SINGLE 不可能出现在双踏板仲裁；null = 都刚抬起，不屏蔽 */ }
                }
            }

            ModConfig.PedalPriority.LAST_TOUCHED -> {
                // 最新触摸的踏板优先：只在 raw 从 0→>0（按下瞬间）时更新，
                // 不在已按住的 MOVE 中更新——否则先按的 view 手指微动会夺回优先。
                val prevRaw = if (isThrottleView) prevThrottle else prevBrake
                if (raw > 0f && prevRaw <= 0f) {
                    sharedLastTouched = role
                } else if (raw <= 0f && sharedLastTouched == role) {
                    sharedLastTouched = if (isThrottleView) {
                        if (sharedRawBrake > 0f) PedalRole.BRAKE else null
                    } else {
                        if (sharedRawThrottle > 0f) PedalRole.THROTTLE else null
                    }
                }
                when (sharedLastTouched) {
                    PedalRole.THROTTLE -> arbitratedBrake = 0f
                    PedalRole.BRAKE -> arbitratedThrottle = 0f
                    else -> { /* SINGLE 不可能出现在双踏板仲裁；null = 都没按，不屏蔽 */ }
                }
            }

            ModConfig.PedalPriority.ALWAYS_THROTTLE -> {
                // 始终油门优先：油门有值时屏蔽刹车，单独按刹车不受影响。
                if (sharedRawThrottle > 0f) arbitratedBrake = 0f
            }

            ModConfig.PedalPriority.ALWAYS_BRAKE -> {
                // 始终刹车优先：刹车有值时屏蔽油门，单独按油门不受影响。
                if (sharedRawBrake > 0f) arbitratedThrottle = 0f
            }

            ModConfig.PedalPriority.THROTTLE_VALUE -> {
                // 油门值（raw）≥ 过渡点 → 油门优先，刹车 mapped 置 0
                // 油门值 < 过渡点 且 刹车 raw > 0 → 刹车优先，油门 mapped 置 0
                if (sharedRawThrottle >= sharedThrottleTransition && sharedRawThrottle > 0f) {
                    arbitratedBrake = 0f
                } else if (sharedRawBrake > 0f) {
                    arbitratedThrottle = 0f
                }
            }

            ModConfig.PedalPriority.BRAKE_VALUE -> {
                // 刹车值（raw）≥ 过渡点 → 刹车优先，油门 mapped 置 0
                // 刹车值 < 过渡点 且 油门 raw > 0 → 油门优先，刹车 mapped 置 0
                // 注意用 raw 判定（跟手、即时），用 mapped 屏蔽（送 native）。
                if (sharedRawBrake >= sharedBrakeTransition && sharedRawBrake > 0f) {
                    arbitratedThrottle = 0f
                } else if (sharedRawThrottle > 0f) {
                    arbitratedBrake = 0f
                }
            }
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
