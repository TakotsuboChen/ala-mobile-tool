package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.util.Logger

/**
 * 滑移率视觉反馈——**贴屏幕下缘的椭圆弓形光斑**，亮度随滑移率连续变化。
 *
 * ## 为什么是弓形而不是横条（设计定案，别再改回矩形）
 *
 * 初版在这里直接 `drawRect` 画了一条纯色长条，用户一眼指认「生硬的长方形」。
 * 参照物是 [TcAbsIndicatorView]——那盏灯的形状语言是**椭圆弓形 + 以直边中点
 * 为中心的径向渐变（中心浓、弧边精确到 0）**，观感是一块柔和光斑而不是一块
 * 色块。本控件现在与它**同构**（几何与渐变逐字复用，见 [SlipBowPainter]），
 * 只有亮度来源不同：
 *
 * | | TC/ABS 指示灯 | 本控件 |
 * |---|---|---|
 * | 形状 | 椭圆弓形，贴屏幕上缘 | 椭圆弓形，贴屏幕下缘 |
 * | 亮度 | **二值闪烁**（native 合成 25Hz 方波） | **连续函数**（滑移率电平 → alpha） |
 *
 * 与「一盏会闪的灯」的差别正是用户要的：「不是闪烁形式，是根据滑移率映射的
 * 函数改变不透明度」。
 *
 * ## 尺寸
 *
 * 宽 = 全屏宽，高 = 指示灯高度的两倍（屏高 1/30 × 2 = 屏高 1/15）。指示灯在
 * 顶部、本条在底部，两者不重叠。弓形在**全宽 2400px 上展开**时，竖向上从顶边
 * 中心 100% 渐变到底边中心 0（见 [SlipBowPainter] 的单位圆 trick），横向也在
 * 两端衰减到 0——所以最终观感是屏幕底部一片**中间亮、四周化开**的琥珀光晕。
 *
 * ## 亮度链
 *
 * `滑移率电平(0..1) × 用户最大不透明度(0..1) → Paint.alpha`。电平来自 native
 * （`native/src/slip_feedback.c`：**两条独立归一化通道取大**，单位都是"该轮此刻
 * 的轮胎峰值 = 1"——
 *
 * - 纵向 `z = max_i |σ_i|/maxSlip_i`：z ≤ 1.0 → 0，1.0~4.0 幂次上弯到 0.25，
 *   4.0~10.0 线性到 1（10 = σ 被 RoadForce 夹到 ±1 的饱和点）；
 * - 横向 `a = max_i |α_i|/maxAngle_i`：a ≤ 1.5 → 0，a ≥ 4.0 后**硬封顶 0.25**。
 *
 * ⚠️ 锚点值来自 **2026-10-02 逐帧直方图重标定**（实测巡航 z≈0.02~0.17、
 * 普通过弯 0.88~2.40、极限过弯 3.9~7.7、锁死/空转 10.2~10.75）：旧锚点
 * （0.40/1.0/3.5）把普通过弯整段推到 0.2~0.7 电平上 = 「低速拐弯太振」的
 * 量化根因。详见 native/src/slip_feedback.c 的常量区。
 *
 * 所以「大多数区间 = 完全不显示」是 native 行为，本 view 不做阈值门控。
 *
 * ⚠️ 横向封顶 1/4 不是审美选择，是对「低速打方向满振」的结构性修复：
 * `IRDSWheel.SlipAngle` 在低速时分母被钳到 1 m/s，α 退化成 `atan(LV.x)`，
 * 而 `LV.x` 含**打舵本身贡献的合法横向速度**，实测可达峰值的 7 倍。完整推导
 * 见 `native/src/slip_feedback.h`。
 *
 * ⚠️ **不再有"最小可见电平"地板值**。初版有个 `MIN_VISIBLE_LEVEL = 0.08f`，
 * 是为了让"电平 0.02 时 alpha 5/255 看不见"这件事不那么困惑——但那个地板值的
 * 副作用是**把用户调低最大不透明度的意图抹掉**（用户设 20%，起振瞬间仍被抬到
 * 8% 以上，且整条曲线被压缩）。现在用户要的「最大不透明度」滑动条正是它的
 * 正确替代物：要更显眼就调高，要更含蓄就调低，从电平到最终 alpha 是纯乘法，
 * 无隐藏下限。
 */
class SlipFeedbackView(
    context: Context,
    /** 条宽（全屏宽），px。 */
    private val barWidthPx: Int,
    /** 条高（指示灯高度的两倍 = 屏高 1/15），px。 */
    private val barHeightPx: Int,
    /**
     * 是否绘制视觉条。false = 本 view 只当**轮询载体**（振感单独开时用）——
     * 仍然负责 60Hz 读电平，但不画东西，避免开两个 timer 各拉一路信号。
     */
    private val drawVisual: Boolean = true,
    /** 用户设定的最大不透明度（0..1，配置页 20%~100%）。 */
    private val maxOpacity: Float = 1f,
    /** 视觉风格（弓形光晕 / 实心矩形），配置页可选。 */
    private val style: ModConfig.SlipVisualStyle = ModConfig.SlipVisualStyle.GLOW,
    /** 是否启用路肩采样（用户开关）。false 时**完全不碰 native**，开销为零。 */
    private val kerbEnabled: Boolean = false,
    /**
     * 每次轮询到新电平后的回调（振感路接在这里，与视觉共用同一次采样）。
     *
     * ⚠️ **一次轮询回调两条通道**：抓地力电平与路肩（电平 + 目标颗粒率）。
     * 两个振感必须合成**一条**波形下发（见 [HapticMixer] 类注释「一」），
     * 所以只能有一个轮询源——分成两个 timer 会让两条波形互相打断。
     */
    private val onLevel: ((Float, Float, Float, Long) -> Unit)? = null,
) : View(context) {

    companion object {
        // 主线程轮询周期：16ms ≈ 60Hz，与物理 50Hz 采样匹配（同 TcAbsIndicatorView）。
        private const val POLL_INTERVAL_MS = 16L

        // 琥珀橙：抓地力临界 ≠ 锁死，与 ABS 红灯（纯红）在语义与色相上区分开。
        // 用户定案。
        private val BAR_COLOR = Color.rgb(255, 152, 0)

        private const val TAG_SLIP = "AlaMobileTool"
    }

    // 轮询缓冲（复用，无每帧分配）。
    private val levelBuf = FloatArray(1)
    private val kerbLevelBuf = FloatArray(1)
    private val kerbRateBuf = FloatArray(1)

    private var level = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 弓形渐变按宽度缓存（见 SlipBowPainter.buildShader 的零分配说明）。
    private var bowShader: RadialGradient? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            pollNative()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun pollNative() {
        if (!NativeBridge.isAvailable) return
        // ⚠️ 读**当前值**（querySlipFeedback），不用 drain 队列。
        // 队列语义会把"入环那一刻的样本"推迟到下一次轮询才被读到（最多 +16ms），
        // 而电平是**状态量**不是事件流 —— 没有历史需要回放，只要最新的那一个。
        var v = 0f
        try {
            NativeBridge.querySlipFeedback(levelBuf)
            v = levelBuf[0]
        } catch (_: Throwable) {
            return
        }
        if (v != level) {
            level = v
            if (drawVisual) invalidate()
        }
        // 路肩电平 + 颗粒率：同一个 60Hz 轮询里读，两条通道的相位天然对齐。
        var kerbLevel = 0f
        var kerbRate = 0f
        if (kerbEnabled) {
            try {
                NativeBridge.queryKerbHaptic(kerbLevelBuf, kerbRateBuf)
                kerbLevel = kerbLevelBuf[0]
                kerbRate = kerbRateBuf[0]
            } catch (_: Throwable) {
                // native 不可用/异常时按"无路肩"处理，不影响抓地力通道。
                kerbLevel = 0f
                kerbRate = 0f
            }
        }
        // ⚠️ 振感回调**每轮都调**（不受 `v != level` 去重门控）：振感的重发判据
        // 在 [HapticMixer] 内部（滞回 + 限流），这里去重会让"电平平台期"少掉
        // 重新计时的机会，反而让限流窗口算错。
        onLevel?.invoke(v, kerbLevel, kerbRate, SystemClock.uptimeMillis())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!drawVisual) return
        if (level <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val alpha = SlipBowPainter.paintAlphaFor(level, maxOpacity)
        if (alpha <= 0) return

        when (style) {
            ModConfig.SlipVisualStyle.GLOW -> {
                val shader = SlipBowPainter.buildShader(bowShader, width, height, BAR_COLOR)
                bowShader = shader
                paint.shader = shader
                paint.alpha = alpha
                SlipBowPainter.draw(canvas, paint, w, h)
            }
            // 实心矩形：不加渐变、不压成椭圆，就是一块纯色条。作为备用选项保留
            // （用户先要弓形，这里给的是"万一弓形在全宽比例下太淡"的退路）。
            ModConfig.SlipVisualStyle.BLOCK -> {
                paint.shader = null
                paint.color = BAR_COLOR
                paint.alpha = alpha
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
    }

    // 尺寸自定（构造传入），不响应父容器测量约束——同 TcAbsIndicatorView。
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(barWidthPx, barHeightPx)
    }

    // shader 按创建时 width 建，尺寸变化时清缓存重建（同 TcAbsIndicatorView）。
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bowShader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ⚠️ **不按可见性启停轮询**（与 TcAbsIndicatorView 的关键差异）。
    // 本 view 是两个功能的共用载体，而两者的生命周期诉求不同：
    // - 视觉条：用户折叠 Overlay 时应当跟着消失（applyOverlayVisibility 置 GONE）；
    // - 振感：是**驾驶手感反馈**，与"屏幕上是否显示控件"无关——用户收起
    //   Overlay 往往正是为了干净地开车，此时把振感一并掐掉是错的。
    // GONE 的 View 不参与绘制（视觉自然消失），但 onVisibilityAggregated 会
    // 停掉轮询进而掐死 onLevel 回调——所以这里刻意不给它加可见性门控。
    // 轮询只在 view 真正离开窗口（onDetachedFromWindow）时停。

    private fun start() {
        if (running || Looper.myLooper() != Looper.getMainLooper()) return
        running = true
        handler.post(pollRunnable)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        // 停轮询时把电平归零并回调一次：GONE 后视觉不再绘制，但振感是"现在
        // 是不是在打滑"的实时反馈，残留的旧电平会让回退路在 view 已摘除时
        // 还持有引用继续触发。显式归零最省心。
        if (level != 0f) {
            level = 0f
        }
        onLevel?.invoke(0f, 0f, 0f, SystemClock.uptimeMillis())
        Logger.i(TAG_SLIP, "SlipFeedbackView: poll stopped (detached)")
    }
}
