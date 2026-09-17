package tools.alamobile.mod.overlay

import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.util.Logger
import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 创建一个铺满整屏的 [OverlayEditView] 并挂到 [parent]。
 *
 * ⚠️ **必须铺满整屏（MATCH_PARENT），且绝不能给编辑层加底色。** 被编辑
 * 控件的矩形是 OverlayEditView 内部的一个 RectF，不是它的布局边界——这样
 * 四角圆点才不会越出自身边界被裁成 1/4。旧的"编辑层尺寸=控件尺寸 + 父容器
 * clipChildren=false"路线已被实机证伪（父链每层都要放行，改一处只会让其中
 * 几个角变样），不要再试。
 */
fun createEditLayer(
    context: Context,
    parent: ViewGroup,
    target: View,
    tag: String,
    defaultPosition: OverlayPosition,
    positionKey: String,
    startVisible: Boolean,
    // 空白判定回调（OverlayManager 聚合全部兄弟层后注入，见 OverlayEditView）。
    isBlankAreaFn: (x: Float, y: Float) -> Boolean,
    // 长按空白处 3s 触发（恢复当前显示的单/双踏板默认位置）。
    onBlankLongPress: (() -> Unit)?
): OverlayEditView {
    val minSizePx = (48 * context.resources.displayMetrics.density).toInt()
    val layer = OverlayEditView(
        context, target, minSizePx, minSizePx, defaultPosition,
        { left, top, width, height ->
            saveOverlayPosition(context, positionKey, left, top, width, height)
        },
        isBlankAreaFn, onBlankLongPress
    )
    layer.tag = tag
    // startVisible=true（在编辑模式中因配置变更而重建）：直接以 VISIBLE +
    // alpha=1 呈现，避免每改一次边框参数就重播一遍 0.3s 淡入（拖滑动条时会
    // 一直闪）。startVisible=false（进场）：GONE + alpha=0，交给 syncEditMode
    // 播 0→1 淡入——若初值为 1，animate().alpha(1f) 是空操作，编辑框会瞬间
    // 出现而不是淡入。
    layer.visibility = if (startVisible) View.VISIBLE else View.GONE
    layer.alpha = if (startVisible) 1f else 0f
    // MATCH_PARENT 铺满整屏：被编辑控件的矩形坐标是编辑层内部状态，不靠布局表达。
    parent.addView(
        layer,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    return layer
}

private fun saveOverlayPosition(context: Context, key: String, left: Int, top: Int, width: Int, height: Int) {
    val dm = context.resources.displayMetrics
    val position = OverlayPosition.fromPixels(
        Point(dm.widthPixels, dm.heightPixels),
        left, top, width, height
    )
    try {
        ModConfig.saveOverlayPosition(context, key, position)
    } catch (e: Throwable) {
        Logger.e("AlaMobileTool", "Failed to save overlay position", e)
    }
}
