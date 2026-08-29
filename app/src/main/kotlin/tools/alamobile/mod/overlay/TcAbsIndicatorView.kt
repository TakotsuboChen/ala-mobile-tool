package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import tools.alamobile.mod.NativeBridge

/**
 * TC/ABS 介入指示灯——单个无边框椭圆弓形，直边在上、直边中点贴屏幕上边缘中点。
 *
 * - TC 介入 = 绿色，ABS 介入 = 红色；同时介入时两色渐变叠加自然呈黄色
 *   （加色混合，无需仲裁）。
 * - 渐变：以直边中点为中心的 RadialGradient，中心 alpha 75%，向弧边渐变
 *   到 0%（用户规格：50% 起版，实测后改 75%）。
 * - 闪烁由 **native 侧合成**：物理线程每物理帧（50Hz）判定介入并叠加
 *   25Hz 相位时钟（ABS = 任一轮 |slipRatio|>0.15 && 游戏自有的
 *   pulseBrakes 方波；TC = 削减 f<accel && 模块帧相位计数器——TC 削减律
 *   无内建方波，直读会常亮）。本 view 主线程 Handler 16ms 轮询读电平
 *   （[NativeBridge.queryTcAbsIndicator]）直驱亮灭，不在 Java 侧做任何
 *   相位/频率处理。
 *
 * 绘制：椭圆弓形 = 上边直、下边半椭圆弧。canvas.scale 把圆压成椭圆——
 * 圆心在直边中点的圆与 RadialGradient 经同一变换，弧边=椭圆边、渐变
 * 50%/75%→0% 精确对齐，无 Path 运算。
 * 尺寸：宽 = 当前屏宽 1/3，高 = 当前屏高 1/30（构造时传入，layoutParams 定）。
 */
class TcAbsIndicatorView(
    context: Context,
    /** 灯宽度（当前屏宽 1/3），px。 */
    private val lightWidthPx: Int,
    /** 灯高度（当前屏高 1/30），px。 */
    private val lightHeightPx: Int,
) : View(context) {

    companion object {
        // 主线程轮询周期：16ms ≈ 60Hz > 物理 50Hz 采样率，不漏电平翻转。
        private const val POLL_INTERVAL_MS = 16L

        // 中心 alpha 75%，弧边 0%（用户规格，50% 起版实测后改 75%）。
        private const val CENTER_ALPHA = 191 // 75% × 255 ≈ 191
        private const val EDGE_ALPHA = 0

        // TC=绿，ABS=红（与 PedalOverlayView 油门=绿/刹车=红 同色系）。
        private val TC_COLOR = Color.rgb(0, 255, 0)
        private val ABS_COLOR = Color.rgb(255, 0, 0)
    }

    // 轮询缓冲（复用，无每帧分配）。
    private val tcBuf = IntArray(1)
    private val absBuf = IntArray(1)

    // 绘制状态：决定 onDraw 画哪个颜色的灯。
    private var tcOn = false
    private var absOn = false

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            pollNative()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** 轮询 native 信号，仅在电平变化时 invalidate（25Hz 闪烁由轮询自然采样）。 */
    private fun pollNative() {
        if (!NativeBridge.isAvailable) return
        try {
            NativeBridge.queryTcAbsIndicator(tcBuf, absBuf)
        } catch (_: Throwable) {
            return
        }
        val newTcOn = tcBuf[0] != 0
        val newAbsOn = absBuf[0] != 0
        if (newTcOn != tcOn || newAbsOn != absOn) {
            tcOn = newTcOn
            absOn = newAbsOn
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!tcOn && !absOn) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || lightWidthPx <= 0 || lightHeightPx <= 0) return

        // 椭圆弓形：直边 = view 顶边，弧 = 下半椭圆（向屏幕内凸出）。
        // 单位圆 trick：y 方向 scale 后，圆心 (w/2, 0)（直边中点）半径 w/2
        // 的圆的下半部分恰是规格弓形；RadialGradient 同圆心同半径，
        // 经同一 scale 变换为椭圆渐变，弧边（r=w/2）处 alpha 精确到 0%。
        val save = canvas.save()
        canvas.scale(1f, h / (lightWidthPx * 0.5f), w / 2f, 0f)
        if (tcOn) drawBow(canvas, w, TC_COLOR)
        if (absOn) drawBow(canvas, w, ABS_COLOR)
        canvas.restoreToCount(save)
    }

    /** 画一个颜色的椭圆弓形（调用方已设置 y 压缩变换）。 */
    private fun drawBow(canvas: Canvas, w: Float, color: Int) {
        paint.shader = shaderFor(color)
        canvas.drawCircle(w / 2f, 0f, w / 2f, paint)
    }

    // 渐变 shader 按颜色缓存（尺寸变化罕见：旋转走 overlay 重建）。
    private var tcShader: RadialGradient? = null
    private var absShader: RadialGradient? = null

    private fun shaderFor(color: Int): RadialGradient {
        val cached = if (color == TC_COLOR) tcShader else absShader
        if (cached != null) return cached
        val w = width.toFloat()
        val rgb = color and 0x00FFFFFF
        val g = RadialGradient(
            w / 2f, 0f, w / 2f,
            intArrayOf(rgb or (CENTER_ALPHA shl 24), rgb or (EDGE_ALPHA shl 24)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        if (color == TC_COLOR) tcShader = g else absShader = g
        return g
    }

    // 灯尺寸自定（构造传入），不响应父容器测量约束——WRAP_CONTENT 布局参数
    // 下 FrameLayout 会传 UNSPECIFIED，这里直接自报构造尺寸。
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(lightWidthPx, lightHeightPx)
    }

    // shader 按创建时 width 建，尺寸变化时清缓存重建。
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tcShader = null
        absShader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    private fun start() {
        if (running || Looper.myLooper() != Looper.getMainLooper()) return
        running = true
        handler.post(pollRunnable)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }
}
