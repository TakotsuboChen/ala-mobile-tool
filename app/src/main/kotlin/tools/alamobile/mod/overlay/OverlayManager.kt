package tools.alamobile.mod.overlay

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig

/**
 * Adds pedal and gear-shift overlay views on top of the Unity activity.
 *
 * NOTE: this is called from the game process, not the module's own process.
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val root: ViewGroup?
    private var pedalView: PedalOverlayView? = null
    private var gearView: GearShiftView? = null
    private var toggleButton: View? = null
    private val density = appContext.resources.displayMetrics.density

    private val settings by lazy { ModConfig.readFromTargetProcess(appContext) }

    init {
        val activity = findCurrentActivity()
        root = activity?.window?.decorView?.findViewById(android.R.id.content)
    }

    fun showOverlays() {
        root ?: return

        // Remove existing views to avoid duplicates.
        removeExisting()

        // Toggle button is always visible; overlays are hidden until toggled on.
        addToggleButton()

        NativeBridge.setThrottle(0f)
        NativeBridge.setBrake(0f)
    }

    private fun addToggleButton() {
        val btn = android.widget.Button(appContext).apply {
            text = "工具"
            tag = "ala_tool_toggle"
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        val params = FrameLayout.LayoutParams(
            (70 * density).toInt(),
            (70 * density).toInt()
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (8 * density).toInt()
            topMargin = (40 * density).toInt()
        }
        btn.setOnClickListener {
            toggleOverlays()
        }
        root?.addView(btn, params)
        toggleButton = btn
    }

    private fun toggleOverlays() {
        if (pedalView == null || gearView == null) {
            addGamingOverlays()
        }
        val currentlyVisible = pedalView?.visibility == View.VISIBLE
        val newVisibility = if (currentlyVisible) View.GONE else View.VISIBLE
        pedalView?.visibility = newVisibility
        gearView?.visibility = newVisibility
    }

    private fun addGamingOverlays() {
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        val thirdHeight = (screenHeight / 3f).toInt()

        gearView = GearShiftView(appContext).apply {
            tag = "gear_shift_overlay"
            visibility = View.GONE
        }
        val gearParams = FrameLayout.LayoutParams(
            (100 * density).toInt(),
            thirdHeight
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = (16 * density).toInt()
            bottomMargin = (16 * density).toInt()
        }
        root?.addView(gearView, gearParams)

        pedalView = PedalOverlayView(appContext, settings).apply {
            tag = "pedal_overlay"
            visibility = View.GONE
        }
        val pedalParams = FrameLayout.LayoutParams(
            (110 * density).toInt(),
            thirdHeight
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            rightMargin = (24 * density).toInt()
        }
        root?.addView(pedalView, pedalParams)
    }

    private fun removeExisting() {
        root?.findViewWithTag<View>("ala_tool_toggle")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("pedal_overlay")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("gear_shift_overlay")?.let { root.removeView(it) }
        pedalView = null
        gearView = null
        toggleButton = null
    }

    private fun findCurrentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as Map<*, *>
            activities.values.firstOrNull()?.let { activityRecord ->
                val activityField = activityRecord.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                activityField.get(activityRecord) as? Activity
            }
        } catch (_: Throwable) {
            null
        }
    }
}
