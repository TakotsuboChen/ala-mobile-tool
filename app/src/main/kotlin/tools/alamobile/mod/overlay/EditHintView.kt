package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 编辑模式的提示层：屏幕正中分三行显示操作说明。
 *
 * 做成独立的 MATCH_PARENT 透明 view，而不是画在 [OverlayEditView] 里：
 * 编辑框的布局边界就是被编辑控件的边界，画在框内要靠父容器
 * `clipChildren=false` 才能把文字画到框外——依赖父容器属性，脆弱（实测
 * 越界绘制没按预期生效）。独立全屏层的"屏幕中心"就是它自己的几何中心，
 * 无需坐标换算，也不受任何裁剪影响。
 *
 * 不消费触摸（不覆写 onTouchEvent，默认返回 false），触摸穿透到下层。
 */
class EditHintView(context: Context) : View(context) {

    companion object {
        // 正文字号（px），沿用旧编辑框内的提示字号。
        private const val TEXT_SIZE = 36f

        // 行高（px），三行以此间距排布在屏幕中心上下。
        private const val LINE_HEIGHT = 56f
    }

    // 描边 Paint（先画深色描边） + 填充 Paint（再画白色字形）。
    // ⚠️ 不用 Paint.setShadowLayer 提高可读性——阴影层只在软件渲染画布生效，
    // 硬件加速画布上会被静默忽略（历史实测确认为死路），描边双绘是稳妥做法。
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        textSize = TEXT_SIZE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SIZE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }

    private val lines = listOf(
        "拖拽移动位置",
        "拖动四角调整大小",
        "长按 ${OverlayEditView.LONG_PRESS_RESET_MS / 1000} 秒恢复默认状态"
    )

    init {
        // 提示文字不需要触摸，也不参与焦点。
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = lines.size
        val centerX = width / 2f
        val centerY = height / 2f
        // 三行整体以屏幕中心为中心：第 i 行偏移 (i - (n-1)/2) * LINE_HEIGHT，
        // 再 + textSize/2 把基线移到格子中心。
        for (i in 0 until n) {
            val baseline = centerY + (i - (n - 1) / 2f) * LINE_HEIGHT + fillPaint.textSize / 2f
            canvas.drawText(lines[i], centerX, baseline, strokePaint)
            canvas.drawText(lines[i], centerX, baseline, fillPaint)
        }
    }
}