package tools.alamobile.mod.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.os.Handler
import android.os.Looper
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
    private var pedalEditView: OverlayEditView? = null
    private var gearEditView: OverlayEditView? = null
    private val density = appContext.resources.displayMetrics.density

    private val settings by lazy { ModConfig.readFromTargetProcess(appContext) }

    private var overlaysVisible = false
    private var editMode = false

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
        addGamingOverlays()

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
        btn.setOnLongClickListener {
            toggleEditMode()
            true
        }
        root?.addView(btn, params)
        toggleButton = btn
    }

    private fun toggleOverlays() {
        if (pedalView == null || gearView == null) {
            addGamingOverlays()
        }
        overlaysVisible = !overlaysVisible
        val newVisibility = if (overlaysVisible) View.VISIBLE else View.GONE
        pedalView?.visibility = newVisibility
        gearView?.visibility = newVisibility
        if (!overlaysVisible) {
            editMode = false
            updateEditModeVisibility()
        }
    }

    private fun toggleEditMode() {
        if (pedalView == null || gearView == null) {
            addGamingOverlays()
        }
        overlaysVisible = true
        pedalView?.visibility = View.VISIBLE
        gearView?.visibility = View.VISIBLE
        editMode = !editMode
        updateEditModeVisibility()
    }

    private fun updateEditModeVisibility() {
        pedalEditView?.let { view ->
            view.visibility = if (editMode) View.VISIBLE else View.GONE
            view.bringToFront()
        }
        gearEditView?.let { view ->
            view.visibility = if (editMode) View.VISIBLE else View.GONE
            view.bringToFront()
        }
    }

    private fun addGamingOverlays() {
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val screenHeight = appContext.resources.displayMetrics.heightPixels

        val gearPosition = settings.gearPosition
        val pedalPosition = settings.pedalPosition
        gearView = GearShiftView(appContext).apply {
            tag = "gear_shift_overlay"
            visibility = View.GONE
        }
        val gearParams = FrameLayout.LayoutParams(
            gearPosition.widthPx(appContext, screenWidth),
            gearPosition.heightPx(appContext, screenHeight)
        ).apply {
            leftMargin = gearPosition.leftPx(screenWidth)
            topMargin = gearPosition.topPx(screenHeight)
        }
        root?.addView(gearView, gearParams)

        pedalView = PedalOverlayView(appContext, settings).apply {
            tag = "pedal_overlay"
            visibility = View.GONE
        }
        val pedalParams = FrameLayout.LayoutParams(
            pedalPosition.widthPx(appContext, screenWidth),
            pedalPosition.heightPx(appContext, screenHeight)
        ).apply {
            leftMargin = pedalPosition.leftPx(screenWidth)
            topMargin = pedalPosition.topPx(screenHeight)
        }
        root?.addView(pedalView, pedalParams)

        addEditLayers()
    }

    private fun addEditLayers() {
        val pedal = pedalView ?: return
        val gear = gearView ?: return

        val minPx = (48 * density).toInt()

        pedalEditView = OverlayEditView(
            appContext,
            pedal,
            minPx,
            minPx
        ) { left, top, width, height ->
            saveOverlayPosition(ModConfig.KEY_PEDAL_POSITION, left, top, width, height)
        }
        pedalEditView?.apply {
            tag = "pedal_overlay_edit"
            visibility = View.GONE
        }
        root?.addView(
            pedalEditView,
            FrameLayout.LayoutParams(
                pedal.width,
                pedal.height
            ).apply {
                leftMargin = (pedal.layoutParams as FrameLayout.LayoutParams).leftMargin
                topMargin = (pedal.layoutParams as FrameLayout.LayoutParams).topMargin
            }
        )

        gearEditView = OverlayEditView(
            appContext,
            gear,
            minPx,
            minPx
        ) { left, top, width, height ->
            saveOverlayPosition(ModConfig.KEY_GEAR_POSITION, left, top, width, height)
        }
        gearEditView?.apply {
            tag = "gear_shift_overlay_edit"
            visibility = View.GONE
        }
        root?.addView(
            gearEditView,
            FrameLayout.LayoutParams(
                gear.width,
                gear.height
            ).apply {
                leftMargin = (gear.layoutParams as FrameLayout.LayoutParams).leftMargin
                topMargin = (gear.layoutParams as FrameLayout.LayoutParams).topMargin
            }
        )
    }

    private fun saveOverlayPosition(key: String, left: Int, top: Int, width: Int, height: Int) {
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        val position = OverlayPosition.fromPixels(
            Point(screenWidth, screenHeight),
            left, top, width, height
        )
        try {
            ModConfig.saveOverlayPosition(appContext, key, position)
        } catch (e: Throwable) {
            android.util.Log.e("AlaMobileTool", "Failed to save overlay position", e)
        }
    }

    private fun removeExisting() {
        root?.findViewWithTag<View>("ala_tool_toggle")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("pedal_overlay")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("gear_shift_overlay")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("pedal_overlay_edit")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("gear_shift_overlay_edit")?.let { root.removeView(it) }
        pedalView = null
        gearView = null
        toggleButton = null
        pedalEditView = null
        gearEditView = null
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
