package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Point

/**
 * Describes the on-screen position and size of a resizable overlay view.
 *
 * Values are stored as fractions of the screen width/height so the layout
 * survives rotation and different devices.
 */
data class OverlayPosition(
    /** Fraction of screen width for the left edge (0..1). */
    val x: Float,
    /** Fraction of screen height for the top edge (0..1). */
    val y: Float,
    /** Fraction of screen width for the view width. */
    val width: Float,
    /** Fraction of screen height for the view height. */
    val height: Float
) {
    companion object {
        // 用户坐标系：屏幕左下角为原点，y 向上，百分比。
        // 四角顺序：(left,bottom),(right,bottom),(left,top),(right,top)
        //   单踏板/油门 (80,55),(95,55),(80,5),(95,5)   → left=.80 right=.95 bottom=.05 top=.55
        //   双踏板刹车   (5,55),(20,55),(5,5),(20,5)    → left=.05 right=.20 bottom=.05 top=.55
        //   换挡（与刹车同坐标）
        // 内部存储保持 Android 原生语义（左上原点 y 向下），转换：
        //   top_android = 1 - top_user  = 1 - .55 = .45
        //   height      = top_user - bottom_user = .55 - .05 = .50
        //   width       = right_user - left_user
        val DEFAULT_PEDAL = OverlayPosition(0.80f, 0.45f, 0.15f, 0.50f)
        val DEFAULT_GEAR = OverlayPosition(0.05f, 0.45f, 0.15f, 0.50f)
        val DEFAULT_BRAKE = OverlayPosition(0.05f, 0.45f, 0.15f, 0.50f)

        private const val MIN_DIMENSION_DP = 48f

        private fun dpToPx(context: Context, dp: Float): Float {
            return dp * context.resources.displayMetrics.density
        }

        fun fromPixels(
            screen: Point,
            leftPx: Int,
            topPx: Int,
            widthPx: Int,
            heightPx: Int
        ): OverlayPosition {
            return OverlayPosition(
                x = leftPx.toFloat() / screen.x,
                y = topPx.toFloat() / screen.y,
                width = widthPx.toFloat() / screen.x,
                height = heightPx.toFloat() / screen.y
            )
        }
    }

    private fun minPx(context: Context): Int =
        (MIN_DIMENSION_DP * context.resources.displayMetrics.density).toInt()

    fun leftPx(screenWidth: Int): Int = (x * screenWidth).toInt()

    fun topPx(screenHeight: Int): Int = (y * screenHeight).toInt()

    fun widthPx(context: Context, screenWidth: Int): Int {
        return ((width * screenWidth).toInt()).coerceAtLeast(minPx(context))
    }

    fun heightPx(context: Context, screenHeight: Int): Int {
        return ((height * screenHeight).toInt()).coerceAtLeast(minPx(context))
    }
}
