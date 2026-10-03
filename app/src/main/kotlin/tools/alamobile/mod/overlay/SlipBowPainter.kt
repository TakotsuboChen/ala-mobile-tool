package tools.alamobile.mod.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * 滑移率视觉反馈的绘制算法——**与 [TcAbsIndicatorView] 完全同构的椭圆弓形**，
 * 只有"亮度怎么来"这一点不同。
 *
 * ## 与 TC/ABS 指示灯的同与异
 *
 * | | TC/ABS 指示灯 | 本控件 |
 * |---|---|---|
 * | 形状 | 椭圆弓形（直边贴屏边） | 椭圆弓形（直边贴屏边） |
 * | 位置 | 屏幕上缘居中 | 屏幕下缘居中 |
 * | 亮度 | **二值闪烁**（native 25Hz 方波） | **连续函数**（电平 → alpha） |
 * | 渐变 | 直边中点 75% → 弧边 0% | 直边中点 = 电平×上限 → 弧边 0% |
 *
 * 两者构成一对：亮斑都**贴屏边**，都向屏幕内侧与左右两端衰减到完全透明。
 * 这正是用户要的"从内到外越来越透明、不是一整块长方形"。
 *
 * ## 单位圆 trick（与指示灯逐字一致）
 *
 * `canvas.scale(1, h / (w/2), w/2, h)` 之后，圆心 `(w/2, h)`、半径 `w/2` 的
 * **单位圆**的**上半部分**恰好是规格弓形（直边在 y=h 即屏边、弧顶在 y=0 即
 * 屏幕内侧）；`RadialGradient` 用同圆心同半径建立，经同一 scale 变换成为椭圆
 * 渐变，**弧边处（r = w/2）alpha 精确落在 0**。整个过程零 Path 运算。
 *
 * ⚠️ pivot 是 `(w/2, h)` 而**不是**指示灯的 `(w/2, 0)`：指示灯贴屏幕上缘、弓形
 * 向下凸；本条贴屏幕下缘、弓形向上凸。两处都是"直边落在屏幕边界上、弧边朝屏幕
 * 内侧"。若照抄指示灯的 pivot = 0，弓形会朝屏幕外侧凸出去被裁掉一半，亮度斑也
 * 跑到屏幕内侧——与指示灯不再是一对。
 */
internal object SlipBowPainter {

    /**
     * 中心 alpha 的满值。实际不透明度由 [paintAlphaFor] 通过 `Paint.alpha`
     * 整体缩放——**不重建 shader**。
     *
     * 这一点与 [TcAbsIndicatorView] 不同（它把 alpha 编进 shader 的色标里）：
     * 指示灯只有亮/灭两态，shader 建两次就够；本控件是连续量，若把 alpha 编进
     * 色标就得 60Hz 重建 `RadialGradient`（每帧一次对象分配 + native 渐变重建）。
     * 改成"shader 固定满值 + `Paint.alpha` 缩放"后，shader 只随尺寸变化重建
     * 一次，热路径零分配。
     *
     * `Paint.alpha` 对 shader 同样生效（绘制时作为整体 alpha 乘进最终结果），
     * 所以"中心 100% → 弧边 0%"的**相对**梯度形状完全保留，只是被整体压暗。
     */
    private const val CENTER_ALPHA = 255
    private const val EDGE_ALPHA = 0

    /** 滑移率电平（0..1）× 用户最大不透明度（0..1）→ `Paint.alpha`。 */
    fun paintAlphaFor(level: Float, maxOpacity: Float): Int {
        val a = level.coerceIn(0f, 1f) * maxOpacity.coerceIn(0f, 1f)
        return (a * 255f).toInt().coerceIn(0, 255)
    }

    /**
     * 建立弓形渐变。**按 view 宽度缓存**（尺寸变化罕见：旋转/分辨率变更走
     * overlay 重建），调用方负责在 `onSizeChanged` 时清缓存。
     */
    fun buildShader(cache: RadialGradient?, widthPx: Int, heightPx: Int, color: Int): RadialGradient {
        cache?.let { return it }
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val rgb = color and 0x00FFFFFF
        return RadialGradient(
            w / 2f, h, w / 2f,
            intArrayOf(rgb or (CENTER_ALPHA shl 24), rgb or (EDGE_ALPHA shl 24)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 画弓形。调用方需已设好 [paint] 的 alpha 与 shader（shader 必须是用**同一
     * 宽高**经 [buildShader] 建的那个——几何要与本函数的变换一致）。
     */
    fun draw(canvas: Canvas, paint: Paint, w: Float, h: Float) {
        val save = canvas.save()
        canvas.scale(1f, h / (w * 0.5f), w / 2f, h)
        canvas.drawCircle(w / 2f, h, w / 2f, paint)
        canvas.restoreToCount(save)
    }
}
