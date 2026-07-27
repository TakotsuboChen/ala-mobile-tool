package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import tools.alamobile.mod.NativeBridge

/**
 * Adds pedal and gear-shift overlay views on top of the Unity activity.
 *
 * NOTE: this is called from the game process, not the module's own process.
 */
class OverlayManager(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayViews = mutableListOf<View>()

    fun showOverlays() {
        if (overlayViews.isNotEmpty()) return

        val pedal = PedalOverlayView(windowManager.context)
        addOverlay(pedal, Gravity.START or Gravity.CENTER_VERTICAL, 300, 600)

        val gearShift = GearShiftView(windowManager.context)
        addOverlay(gearShift, Gravity.END or Gravity.CENTER_VERTICAL, 200, 400)

        NativeBridge.setThrottle(0f)
        NativeBridge.setBrake(0f)
    }

    private fun addOverlay(view: View, gravity: Int, width: Int, height: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

        windowManager.addView(view, params)
        overlayViews.add(view)
    }

    fun hideOverlays() {
        for (view in overlayViews) {
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // View was already removed.
            }
        }
        overlayViews.clear()
    }
}
