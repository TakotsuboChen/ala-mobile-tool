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

    companion object {
        // 配置变更回调入口：ConfigReceiver 写完 JSON 后调此方法，
        // post 到主线程触发 OverlayManager 重建 overlay。共存版双 ClassLoader
        // 下，第二个 ClassLoader 不构造 OverlayManager（isNativeInstalled 守卫
        // 跳过），其 instance 为 null，notifyConfigChanged 是 no-op——只有
        // 第一个 ClassLoader 的 instance 非空，重建生效，不会重复。
        @Volatile
        private var instance: OverlayManager? = null

        fun notifyConfigChanged() {
            instance?.let { mgr ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    mgr.rebuildFromConfigChange()
                }
            }
        }
    }

    private val appContext = context.applicationContext
    // root 是 var：Activity 可能被销毁重建（旋转/内存回收），旧 decorView 失效。
    // rebuildFromConfigChange 在广播到达时触发（不在 Activity 生命周期同步点），
    // 必须重新获取 root，否则 addView/removeView 作用在旧 view 上无效。
    private var root: ViewGroup? = null
    private var pedalView: PedalOverlayView? = null
    private var brakeView: PedalOverlayView? = null
    private var gearView: GearShiftView? = null
    private var toggleButton: View? = null
    private var pedalEditView: OverlayEditView? = null
    private var brakeEditView: OverlayEditView? = null
    private var gearEditView: OverlayEditView? = null
    private val density = appContext.resources.displayMetrics.density

    // 可重读的配置：每次 show/toggle 都重新读 JSON，避免 by lazy 缓存
    // 导致配置页改的 pedalMode/curve 流不到运行时（M10 真机不生效根因）。
    // PedalOverlayView 构造时拷贝 settings 快照，所以光重读不够——必须重建 view。
    private var settings = ModConfig.readFromTargetProcess(appContext)

    private var overlaysVisible = false
    private var editMode = false

    init {
        refreshRoot()
        instance = this
    }

    /**
     * 重新获取当前 Activity 的 content view 作为 root 容器。
     * Activity 可能被销毁重建（旋转/内存回收），旧 decorView 失效，
     * addView/removeView 作用在旧 view 上无效。rebuildFromConfigChange
     * 在广播到达时触发（不在 Activity 生命周期同步点），必须先刷新 root。
     */
    private fun refreshRoot() {
        val activity = findCurrentActivity()
        root = activity?.window?.decorView?.findViewById(android.R.id.content)
    }

    /**
     * 配置变更后重建 overlay（由 ConfigReceiver 通过 notifyConfigChanged 触发）。
     *
     * PedalOverlayView 构造时拷贝 ModConfig.Settings 快照（data class 值语义），
     * 光重读 JSON 不够——必须重建 view 才能让新配置流进去。此方法重读配置、
     * 移除游戏控件、重建，并保持当前可见性和编辑模式状态不变（配置变了但
     * 用户没操作，可见性应保持）。
     */
    private fun rebuildFromConfigChange() {
        // Activity 可能已重建，旧 root 失效——先刷新。
        refreshRoot()
        if (root == null) return
        settings = ModConfig.readFromTargetProcess(appContext)
        removeGamingOverlays()
        addGamingOverlays()
        // addGamingOverlays 创建时 visibility=GONE，按当前 overlaysVisible 重设。
        val newVisibility = if (overlaysVisible) View.VISIBLE else View.GONE
        pedalView?.visibility = newVisibility
        brakeView?.visibility = newVisibility
        gearView?.visibility = newVisibility
        // 重建后 editView 是新实例，若在编辑模式要重新设 VISIBLE。
        if (editMode) updateEditModeVisibility()
    }

    fun showOverlays() {
        root ?: return

        // 每次显示前重读配置——配置页（模块进程）改的 pedalMode/curve 通过
        // 共享 JSON 文件传递，游戏进程必须主动读才能拿到新值。原 by lazy 只读一次，
        // 导致运行时永远用旧配置（M10 真机不生效根因）。
        settings = ModConfig.readFromTargetProcess(appContext)

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
        // 每次 toggle 都重读配置并重建控件：配置页改 pedalMode（关/单/双）、
        // curve（线性/拟真）、enableManualShift 后，用户点工具按钮重新展开，
        // 控件必须反映最新值。原实现用 pedalView==null 判空跳过重建，导致
        // 首次创建后就永远用旧配置——切关/双踏板仍显示单踏板，切拟真仍线性。
        settings = ModConfig.readFromTargetProcess(appContext)
        removeGamingOverlays()
        addGamingOverlays()

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
        settings = ModConfig.readFromTargetProcess(appContext)
        removeGamingOverlays()
        addGamingOverlays()

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
        val singlePosition = settings.singlePedalPosition

        // 手动换挡关时不创建换挡控件；gearView 保持 null。
        // DUAL 模式下也不创建——刹车和换挡默认坐标相同（左下角），
        // 同时开会重叠导致触摸冲突。用户明确要求两者不能同时开，
        // 当前换挡开关 UI 禁用，这里加运行时守卫防 JSON 手动编辑或
        // 未来 UI bug 导致两者同时 true。
        if (settings.enableManualShift && settings.pedalMode != ModConfig.PedalMode.DUAL) {
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
                    PedalOverlayView.PedalRole.SINGLE, singlePosition
                ).apply {
                    tag = "pedal_overlay"
                    visibility = View.GONE
                }
                val pedalParams = FrameLayout.LayoutParams(
                    singlePosition.widthPx(appContext, screenWidth),
                    singlePosition.heightPx(appContext, screenHeight)
                ).apply {
                    leftMargin = singlePosition.leftPx(screenWidth)
                    topMargin = singlePosition.topPx(screenHeight)
                }
                root?.addView(pedalView, pedalParams)
                addPedalEditLayer(pedalParams, singlePosition, ModConfig.KEY_SINGLE_PEDAL_POSITION)
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
                addPedalEditLayer(pedalParams, pedalPosition, ModConfig.KEY_PEDAL_POSITION)

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

    private fun addPedalEditLayer(
        pedalParams: FrameLayout.LayoutParams,
        runtimePosition: OverlayPosition,
        positionKey: String
    ) {
        val pedal = pedalView ?: return
        val minPx = (48 * density).toInt()

        pedalEditView = OverlayEditView(
            appContext,
            pedal,
            minPx,
            minPx,
            // 长按重置到出厂默认（OverlayPosition.DEFAULT_*），不再用运行时
            // 已保存的 position——否则"重置"只是回到当前已保存值，用户感知
            // 无变化。出厂默认是固定值，重置才有意义。
            OverlayPosition.DEFAULT_PEDAL,
            runtimePosition
 // 运行时 position 作为初始布局（与 target view 对齐）
        ) { left, top, width, height ->
            saveOverlayPosition(positionKey, left, top, width, height)
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
            OverlayPosition.DEFAULT_BRAKE,
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
            OverlayPosition.DEFAULT_GEAR,
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

    private fun removeGamingOverlays() {
        // 只移除游戏控件，保留 toggle 按钮——toggle 操作时按钮本身要留着
        // 供用户再次点击，只需重建踏板/换挡 view 反映最新配置。
        // 用局部 val 快照 root，避免 var 的 smart cast 限制。
        val parent = root ?: return
        parent.findViewWithTag<View>("pedal_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay_edit")?.let { parent.removeView(it) }
        pedalView = null
        brakeView = null
        gearView = null
        pedalEditView = null
        brakeEditView = null
        gearEditView = null
    }

    private fun removeExisting() {
        val parent = root ?: return
        parent.findViewWithTag<View>("ala_tool_toggle")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay_edit")?.let { parent.removeView(it) }
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
