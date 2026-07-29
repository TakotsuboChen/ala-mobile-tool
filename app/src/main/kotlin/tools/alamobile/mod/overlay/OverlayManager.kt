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
import kotlin.math.max
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
    private var brakeView: PedalOverlayView? = null
    private var gearView: GearShiftView? = null
    private var toggleButton: View? = null
    private var pedalEditView: OverlayEditView? = null
    private var brakeEditView: OverlayEditView? = null
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
        // 只用 pedalView 判空触发 addGamingOverlays —— 手动换挡关时 gearView
        // 永远 null 是正常态，不应触发重复 add。pedalMode=OFF 时 pedalView
        // 也永远 null，同样不应反复触发。
        if (pedalView == null && brakeView == null && settings.pedalMode != ModConfig.PedalMode.OFF) {
            addGamingOverlays()
        }
        overlaysVisible = !overlaysVisible
        val newVisibility = if (overlaysVisible) View.VISIBLE else View.GONE
        pedalView?.visibility = newVisibility
        brakeView?.visibility = newVisibility
        gearView?.visibility = newVisibility
        if (!overlaysVisible) {
            editMode = false
            updateEditModeVisibility()
        }
    }

    private fun toggleEditMode() {
        if (pedalView == null && brakeView == null && settings.pedalMode != ModConfig.PedalMode.OFF) {
            addGamingOverlays()
        }
        // Make sure the underlying overlays are visible so the edit layer is
        // meaningful, but do not change the usage-visible state.
        pedalView?.visibility = View.VISIBLE
        brakeView?.visibility = View.VISIBLE
        gearView?.visibility = View.VISIBLE
        editMode = !editMode
        updateEditModeVisibility()
    }

    private fun updateEditModeVisibility() {
        pedalEditView?.let { view ->
            view.visibility = if (editMode) View.VISIBLE else View.GONE
            view.bringToFront()
        }
        brakeEditView?.let { view ->
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
        val brakePosition = settings.brakePosition

        // 手动换挡关时不创建换挡控件；gearView 保持 null。
        if (settings.enableManualShift) {
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
            addGearEditLayer(gearParams)
        }

        // 按 pedalMode 创建踏板 view：OFF 不创建，SINGLE 创建一个双分区 view，
        // DUAL 创建两个独立 view（油门 + 刹车），各自独立位置可调。
        when (settings.pedalMode) {
            ModConfig.PedalMode.OFF -> {
                // 不创建踏板 view；pedalView/brakeView 保持 null。
            }
            ModConfig.PedalMode.SINGLE -> {
                pedalView = PedalOverlayView(
                    appContext, settings,
                    PedalOverlayView.PedalRole.SINGLE, pedalPosition
                ).apply {
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
                addPedalEditLayer(pedalParams)
            }
            ModConfig.PedalMode.DUAL -> {
                pedalView = PedalOverlayView(
                    appContext, settings,
                    PedalOverlayView.PedalRole.THROTTLE, pedalPosition
                ).apply {
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
                addPedalEditLayer(pedalParams)

                brakeView = PedalOverlayView(
                    appContext, settings,
                    PedalOverlayView.PedalRole.BRAKE, brakePosition
                ).apply {
                    tag = "brake_overlay"
                    visibility = View.GONE
                }
                val brakeParams = FrameLayout.LayoutParams(
                    brakePosition.widthPx(appContext, screenWidth),
                    brakePosition.heightPx(appContext, screenHeight)
                ).apply {
                    leftMargin = brakePosition.leftPx(screenWidth)
                    topMargin = brakePosition.topPx(screenHeight)
                }
                root?.addView(brakeView, brakeParams)
                addBrakeEditLayer(brakeParams)
            }
        }
    }

    private fun addPedalEditLayer(pedalParams: FrameLayout.LayoutParams) {
        val pedal = pedalView ?: return
        val minPx = (48 * density).toInt()

        pedalEditView = OverlayEditView(
            appContext,
            pedal,
            minPx,
            minPx,
            settings.pedalPosition
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
                pedalParams.width,
                pedalParams.height
            ).apply {
                leftMargin = pedalParams.leftMargin
                topMargin = pedalParams.topMargin
            }
        )
    }

    private fun addBrakeEditLayer(brakeParams: FrameLayout.LayoutParams) {
        val brake = brakeView ?: return
        val minPx = (48 * density).toInt()

        brakeEditView = OverlayEditView(
            appContext,
            brake,
            minPx,
            minPx,
            settings.brakePosition
        ) { left, top, width, height ->
            saveOverlayPosition(ModConfig.KEY_BRAKE_POSITION, left, top, width, height)
        }
        brakeEditView?.apply {
            tag = "brake_overlay_edit"
            visibility = View.GONE
        }
        root?.addView(
            brakeEditView,
            FrameLayout.LayoutParams(
                brakeParams.width,
                brakeParams.height
            ).apply {
                leftMargin = brakeParams.leftMargin
                topMargin = brakeParams.topMargin
            }
        )
    }

    private fun addGearEditLayer(gearParams: FrameLayout.LayoutParams) {
        val gear = gearView ?: return
        val minPx = (48 * density).toInt()

        gearEditView = OverlayEditView(
            appContext,
            gear,
            minPx,
            minPx,
            settings.gearPosition
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
                gearParams.width,
                gearParams.height
            ).apply {
                leftMargin = gearParams.leftMargin
                topMargin = gearParams.topMargin
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
        root?.findViewWithTag<View>("brake_overlay")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("gear_shift_overlay")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("pedal_overlay_edit")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("brake_overlay_edit")?.let { root.removeView(it) }
        root?.findViewWithTag<View>("gear_shift_overlay_edit")?.let { root.removeView(it) }
        pedalView = null
        brakeView = null
        gearView = null
        toggleButton = null
        pedalEditView = null
        brakeEditView = null
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
