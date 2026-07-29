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
        val DEFAULT_PEDAL = OverlayPosition(0.75f, 0.35f, 0.18f, 0.55f)
        val DEFAULT_GEAR = OverlayPosition(0.04f, 0.60f, 0.22f, 0.30f)
        val DEFAULT_BRAKE = OverlayPosition(0.55f, 0.35f, 0.18f, 0.55f)

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
