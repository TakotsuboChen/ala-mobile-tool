package tools.alamobile.mod.overlay

import tools.alamobile.mod.util.Logger
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig

/**
 * Adds pedal and gear-shift overlay views on top of the Unity activity.
 *
 * NOTE: this is called from the game process, not the module's own process.
 */
class OverlayManager(context: Context) {

    companion object {
        // 进出编辑模式的过渡时长（ms）。编辑框淡入/整屏变暗同步使用。
        private const val FADE_DURATION_MS = 300L

        // 我们自己添加的 view 的 tag 集合。用于把变暗层插到"游戏内容之后、
        // 我们自己的控件之前"——见 placeDimAboveGameContent。
        private val OWN_TAGS = setOf(
            "ala_tool_toggle",
            "pedal_overlay", "brake_overlay", "gear_shift_overlay", "tc_abs_indicator",
            "pedal_overlay_edit", "brake_overlay_edit", "gear_shift_overlay_edit",
            "overlay_dim", "overlay_edit_hint"
        )

        // 配置变更回调入口：ConfigReceiver 写完 JSON 后调此方法，
        // post 到主线程触发 OverlayManager 重建 overlay。共存版双 ClassLoader
        // 下，第二个 ClassLoader 不构造 OverlayManager（isNativeInstalled 守卫
        // 跳过），其 instance 为 null，notifyConfigChanged 是 no-op——只有
        // 第一个 ClassLoader 的 instance 非空，重建生效，不会重复。
        @Volatile
        private var instance: OverlayManager? = null

        // 最近一次广播带来的最新配置 JSON。ConfigReceiver 收到广播后写入，
        // rebuild 优先用它解析（比 readFromTargetProcess 读 daemon 更及时——
        // daemon 可能是旧值，见 M41 根因）。@Volatile 保证跨线程可见。
        @Volatile
        private var latestConfigJson: String? = null

        fun notifyConfigChanged(json: String?) {
            if (json != null) latestConfigJson = json
            Logger.i("AlaMobileTool", "notifyConfigChanged: instance=${instance != null} json=${json != null}")
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
    private var toggleButton: ToolButtonView? = null
    private var indicatorView: TcAbsIndicatorView? = null
    private var pedalEditView: OverlayEditView? = null
    private var brakeEditView: OverlayEditView? = null
    private var gearEditView: OverlayEditView? = null
    // 编辑模式的整屏变暗层。z 序紧贴游戏内容之后、我们自己的控件之前：
    // 只压暗游戏画面，被编辑的踏板/换挡控件与编辑框保持原亮度。
    // **不消费触摸**——长按工具按钮退出编辑模式的手势必须能穿过它到达按钮。
    private var dimView: View? = null
    // 编辑模式的屏幕居中提示层（三行操作说明）。独立全屏透明层，不消费触摸。
    private var hintView: EditHintView? = null
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
        Logger.i("AlaMobileTool", "rebuildFromConfigChange: root=${root != null} overlaysVisible=$overlaysVisible")
        // Activity 可能已重建，旧 root 失效——先刷新。
        refreshRoot()
        if (root == null) return
        settings = resolveLatestSettings()
        removeGamingOverlays()
        addGamingOverlays()
        // addGamingOverlays 创建时 visibility=GONE，按当前状态重设。
        // **编辑模式下必须 VISIBLE**（applyOverlayVisibility 内已含该判据）：
        // 编辑模式不改 overlaysVisible，若只按 overlaysVisible 判定，编辑中改
        // 边框参数触发重建会把控件本体设回 GONE——表现为"只剩编辑框、控件
        // 消失，要退出重进才恢复"。
        applyOverlayVisibility()
        // 重建后 editView 是新实例，若在编辑模式要重新设 VISIBLE + 重播淡入。
        if (editMode) updateEditModeVisibility()
    }

    fun showOverlays() {
        root ?: return

        // 每次显示前重读配置——配置页（模块进程）改的 pedalMode/curve 通过
        // 共享 JSON 文件传递，游戏进程必须主动读才能拿到新值。原 by lazy 只读一次，
        // 导致运行时永远用旧配置（M10 真机不生效根因）。
        settings = resolveLatestSettings()

        // Remove existing views to avoid duplicates.
        removeExisting()

        // Toggle button is always visible; overlays are hidden until toggled on.
        addToggleButton()
        addGamingOverlays()

        // 应用工具按钮记忆位置（settings.toolButtonPosition 来自本地
        // externalFilesDir 的 KEY_TOOL_POSITION；未拖过时 = 默认位置）。
        // 注意这条**只在 showOverlays 调**——后续 toggleOverlays /
        // rebuildFromConfigChange 不再应用，因为那是用户主动操作过程中，
        // 拖动后的位置已在 view layoutParams 上，不应被回放值覆盖。
        toggleButton?.applySavedPosition()
    }

    /**
     * 解析最新配置。优先用广播带来的最新 JSON（[latestConfigJson]），
     * 它比 readFromTargetProcess 读 daemon 更及时——daemon 写入滞后于广播
     * （M41 根因），导致 rebuild/toggle 读到旧 pedalMode/curve。
     *
     * 但广播 JSON 不含 position 字段（ConfigActivity 不管 position，position
     * 由游戏进程拖拽时 saveOverlayPosition 写本地 externalFilesDir）。所以
     * 用广播 JSON 解析后，必须从本地 externalFilesDir 合并 position，否则
     * 重建后单踏板位置/大小会丢回默认（M41 位置丢失根因）。
     */
    private fun resolveLatestSettings(): ModConfig.Settings {
        val json = latestConfigJson
        if (json != null) {
            // 从本地 externalFilesDir 读 position 字段，合并进广播 JSON。
            val dir = appContext.getExternalFilesDir(null)
            val localJson = if (dir != null) {
                val file = java.io.File(dir, "ala_tool_config.json")
                if (file.exists()) {
                    try { file.readText() } catch (_: Throwable) { null }
                } else null
            } else null
            val merged = ModConfig.mergePositionFromLocalPublic(json, localJson)
            return ModConfig.fromJson(merged)
        }
        return ModConfig.readFromTargetProcess(appContext)
    }

    private fun addToggleButton() {
        // 96×96dp 圆角矩形 + 居中 App 图标的浮动工具按钮。
        // - 单击：切换 overlay 展开/折叠（onClick → toggleOverlays）
        // - 长按 500ms：进入其他 overlay 的编辑模式（onLongPress → toggleEditMode）
        // - 拖动：移动按钮自身，拖动结束持久化到本地 externalFilesDir
        //   （KEY_TOOL_POSITION，saveOverlayPosition 与踏板/换挡同机制）。
        //   默认启用记忆位置——未拖过时本地无该 key，settings 解析落回
        //   Defaults.TOOL_BUTTON_POSITION，行为同旧版默认位置。
        val btn = ToolButtonView(
            appContext,
            // 传入 settings.toolButtonPosition（记忆值或默认值）作为初始位置。
            // 长按重置自身不在此处理；applySavedPosition() 会用此值。
            settings.toolButtonPosition
        ).apply {
            tag = "ala_tool_toggle"
            onClick = { toggleOverlays() }
            onLongPress = { toggleEditMode() }
            // 拖动结束：与踏板/换挡同机制落盘（比例存，跨设备可回放）。
            onPositionChanged = { left, top, width, height ->
                saveOverlayPosition(ModConfig.KEY_TOOL_POSITION, left, top, width, height)
            }
        }
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        // 工具按钮大小 = 屏幕高度的 10%（动态像素，跨设备视觉比例一致）。
        // ToolButtonView.onMeasure 也强制此尺寸，但 layoutParams 也设一致
        // 避免 0 尺寸测量路径出问题。
        val buttonSize = (screenHeight * 0.10f).toInt()
        val params = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (8 * density).toInt()
            topMargin = (40 * density).toInt()
        }
        root?.addView(btn, params)
        // showOverlays 会再调一次 applySavedPosition 把位置校正到记忆值——
        // 这里设的 leftMargin/topMargin 是兜底（首次创建时 applySavedPosition
        // 还没执行）。
        toggleButton = btn
    }

    private fun toggleOverlays() {
        // 编辑模式中屏蔽单击：单击 = 展开/折叠控件，会与编辑模式打架
        // （折叠会连带退出编辑、重建控件、控件本体闪现消失）。编辑模式的
        // 唯一入口/出口都是长按工具图标。
        if (editMode) return

        // 每次 toggle 都重读配置并重建控件：配置页改 pedalMode（关/单/双）、
        // curve（线性/拟真）、enableManualShift 后，用户点工具按钮重新展开，
        // 控件必须反映最新值。原实现用 pedalView==null 判空跳过重建，导致
        // 首次创建后就永远用旧配置——切关/双踏板仍显示单踏板，切拟真仍线性。
        settings = resolveLatestSettings()
        removeGamingOverlays()
        addGamingOverlays()

        overlaysVisible = !overlaysVisible
        applyOverlayVisibility()
        if (!overlaysVisible) {
            editMode = false
            updateEditModeVisibility()
        }
    }

    /**
     * 按当前状态设置控件本体（踏板/换挡/指示灯）的可见性。
     *
     * 编辑模式中一律强制可见——要看得见控件才能拖它；退出编辑模式后回到
     * "进入编辑之前"的状态（由 [overlaysVisible] 表达）：用户从隐藏状态长按
     * 进编辑，退出后控件必须重新隐藏。旧实现退出路径完全不碰可见性，进编辑
     * 时设的 VISIBLE 就永久留下了（用户实测"退出后控件本体还留着"）。
     */
    private fun applyOverlayVisibility() {
        val v = if (editMode || overlaysVisible) View.VISIBLE else View.GONE
        pedalView?.visibility = v
        brakeView?.visibility = v
        gearView?.visibility = v
        indicatorView?.visibility = v
    }

    private fun toggleEditMode() {
        if (editMode) {
            // ── 退出编辑模式 ──
            // ⚠️ 这里**绝不能重建控件**。旧实现在进出两个方向都调
            // removeGamingOverlays() + addGamingOverlays()：退出时旧编辑层
            // 被 removeView 立刻摘掉，新建的层是 GONE——于是"淡出"动画作用在
            // 刚出生就已不可见的层上，用户看到的退出是**瞬间恢复**（而不是
            // 0.3s 渐亮）。进场方向恰好能淡入，因为新层随即被置 VISIBLE 再
            // 0→1 播放。退出方向保持现有图层，只翻转 editMode 让
            // syncEditMode 播 1→0。
            editMode = false
            applyOverlayVisibility()
            updateEditModeVisibility()
            return
        }

        // ── 进入编辑模式 ──
        // 进场重建一次：让编辑层反映最新配置（pedalMode/curve/换挡开关）。
        settings = ModConfig.readFromTargetProcess(appContext)
        removeGamingOverlays()
        addGamingOverlays()

        // 编辑模式中控件必须可见，但**不改变 overlaysVisible**——那是用户的
        // 展开/折叠意图，退出编辑后要按它（配合 applyOverlayVisibility）还原。
        editMode = true
        applyOverlayVisibility()
        updateEditModeVisibility()
    }

    private fun updateEditModeVisibility() {
        syncEditMode()
    }

    /**
     * 把编辑模式的视觉状态同步到实际 view，并带 0.5s 淡入/淡出。
     *
     * **不能简单地用 visibility 开关**：`visibility=GONE` 会让 view 立刻消失，
     * 没有任何过渡。做法是进场先置 VISIBLE 再靠 alpha 0→1 淡入；退场靠
     * alpha 1→0 淡出，**动画结束后**才置 GONE。
     *
     * ⚠️ 收尾置 GONE 用 Handler.postDelayed 而不是 `withEndAction`：本方法把
     * 变暗层 / 提示层 / 多个编辑框依次用一个 ViewPropertyAnimator 驱动，
     * `animate()` 返回的是**同一实例**（每个 view 各自一个，但同一 view 上
     * 重复链式调用会覆盖）——历史实测 `withEndAction` 的收尾在"退出瞬间恢复"
     * 的路径上没被执行（用户实测退出时无渐亮、瞬间恢复，且编辑层没有正确
     * 置 GONE）。改成显式延时按下 targetAlpha 收尾，逻辑直接可读。
     *
     * ⚠️ 退场后必须真的置 GONE，不能停在 "alpha=0 但 VISIBLE"：编辑层会
     * 吃掉触摸事件，导致退出编辑模式后游戏踏板点不动。GONE 的 view 不参与
     * 命中测试。
     *
     * 变暗层只压暗游戏画面（z 序紧贴游戏内容之上、游戏控件之下），被编辑的
     * 控件与编辑框保持明亮。**z 序不是简单插到 index 0**：Unity 的画面走
     * SurfaceView，会被合成器提到窗口 View 之上，index 0 的层实际上位于
     * SurfaceView 之下——变暗层必须紧贴游戏内容 View 之后。
     */
    private fun syncEditMode() {
        val parent = root ?: return

        val editViews = listOfNotNull(pedalEditView, brakeEditView, gearEditView)
        if (editMode) editViews.forEach { it.visibility = View.VISIBLE }

        // 双踏板控件的「油门」/「刹车」标识只在编辑模式显示。⚠️ 必须在
        // 进场重建之后调用——进场路径会重建 PedalOverlayView，新实例的
        // editLabels 为 false；本方法在 toggleEditMode/rebuild 末尾都会跑到，
        // 所以这里统一翻正是唯一可靠的写入点。
        pedalView?.setEditLabelsVisible(editMode)
        brakeView?.setEditLabelsVisible(editMode)

        editViews.forEach { animateFade(it, if (editMode) 1f else 0f, isEditLayer = true) }
        // 编辑层必须在控件与变暗层之上，否则边框/四角会被压暗或挡住。
        if (editMode) editViews.forEach { it.bringToFront() }

        if (editMode) {
            val dim = ensureDimView(parent)
            placeDimAboveGameContent(parent, dim)
            val hint = ensureHintView(parent)
            hint.bringToFront()
            // 进场从 0 播 0→1 淡入，与退场的 1→0 对称。alpha 初值已在
            // ensureDimView/ensureHintView 设为 0（新建层）或保持现值
            // （"编辑中改参数重建"复用旧层时 alpha 已是 1，动画为空操作
            // 不重播——见 ensureDimView 注释）。
            animateFade(dim, 1f, isEditLayer = false)
            animateFade(hint, 1f, isEditLayer = false)
        } else {
            dimView?.let { animateFade(it, 0f, isEditLayer = false) }
            hintView?.let { animateFade(it, 0f, isEditLayer = false) }
        }
    }

    /**
     * alpha 动画 + 收尾。进编辑模式：VISIBLE + 0→1；退编辑模式：1→0，动画
     * 结束后置 GONE（编辑层用；变暗层/提示层永远保持 VISIBLE，只动 alpha）。
     */
    private fun animateFade(view: View, target: Float, isEditLayer: Boolean) {
        view.animate().cancel()
        view.animate().alpha(target).setDuration(FADE_DURATION_MS).start()
        if (target == 0f && isEditLayer) {
            // 延时收尾必须与动画时长同步；期间又切回编辑模式时，editMode
            // 已为 true，不能误置 GONE。
            // ⚠️ 不能加 `view.alpha == 0f` 附加判据：ViewPropertyAnimator 的末帧
            // 走 Choreographer 调度，比同时到期的 postDelayed 消息晚 0~1 帧——
            // 消息触发时动画还差最后零点几没播完，alpha ≈ 0.03~0.05 恒不为 0，
            // 判据永不成立 → 永远不置 GONE → 编辑层以 alpha≈0 常驻 VISIBLE，
            // 铺满整屏 + z 序最上，吃掉踏板矩形内的触摸（表现为退出编辑模式后
            // 踏板"仍可拖拽"、游戏油门/刹车失灵）。editMode 守卫已覆盖"中途
            // 重进"的全部情形，直接置 GONE 即可。
            view.postDelayed({
                if (!editMode) view.visibility = View.GONE
            }, FADE_DURATION_MS)
        }
    }

    /**
     * 变暗层紧贴"游戏内容 View"之后（即它上面的第一个位置）。
     *
     * Unity 画面由 SurfaceView 承载，合成器会把 SurfaceView 提到同一窗口
     * 所有 View 之上，故窗口里比 SurfaceView 索引更高的普通 View 仍会盖在
     * 画面上——变暗层放在游戏内容之后即可压暗画面，又不会盖住我们自己后加
     * 的控件与编辑框。
     *
     * 判断方式：子 view 里索引最小、且带我们自己的 tag 的那个，其前面就是
     * 游戏内容。我们所有 view 都是 addGamingOverlays/addToggleButton 最后
     * 追加的，所以这个位置等于"游戏内容之后"。
     */
    private fun placeDimAboveGameContent(parent: ViewGroup, dim: View) {
        // dim 自己也在 parent 里，先移除再算，避免自我干扰。
        if (dim.parent === parent) parent.removeView(dim)
        var insertAt = parent.childCount
        for (i in 0 until parent.childCount) {
            val tag = parent.getChildAt(i).tag as? String
            if (tag != null && tag in OWN_TAGS) {
                insertAt = i
                break
            }
        }
        parent.addView(dim, insertAt, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun ensureDimView(parent: ViewGroup): View {
        dimView?.takeIf { it.parent === parent }?.let { return it }
        val dim = View(appContext).apply {
            tag = "overlay_dim"
            setBackgroundColor(Color.argb(191, 0, 0, 0)) // 75% 黑
            // 新建层一律从 alpha=0 起步，由 syncEditMode 的 animateFade 播 0→1
            // 淡入（与退场 1→0 对称）。"编辑中改参数不重播渐暗"不靠初值，靠
            // 复用：removeGamingOverlays 不再摘除 dim 层，编辑中途重建控件时
            // 本方法命中顶部 takeIf 直接返回旧层（alpha 已是 1，1→1 动画为
            // 空操作）。旧实现 `alpha = if (editMode) 1f else 0f` 在首次进场时
            // 也命中 editMode=true 分支（toggleEditMode 先置位再调到这），
            // 新层 alpha=1 + 1→1 空动画 = 进场瞬间变黑无过渡（用户报的
            // "进入编辑模式闪现变暗"）。
            alpha = 0f
            isClickable = false
            isFocusable = false
            // 不接受触摸：事件穿透到下层（长按工具按钮退出编辑模式的手势
            // 必须能穿过它）。
            isEnabled = false
        }
        dimView = dim
        parent.addView(dim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return dim
    }

    /**
     * 屏幕正中的三行操作提示。独立全屏透明层，"屏幕中心"就是它自己的几何
     * 中心——不依赖父容器裁剪，也不受编辑框位置影响。
     */
    private fun ensureHintView(parent: ViewGroup): View {
        hintView?.takeIf { it.parent === parent }?.let { return it }
        val hint = EditHintView(appContext).apply {
            tag = "overlay_edit_hint"
            // 同 ensureDimView：新建层从 alpha=0 淡入，复用旧层时 1→1 空操作
            // 不重播。旧实现 `if (editMode) 1f else 0f` 与 ensureDimView 同款
            // 问题——首次进场时 editMode 已为 true，初值 1 = 瞬间出现无过渡。
            alpha = 0f
        }
        hintView = hint
        parent.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return hint
    }

    private fun addGamingOverlays() {
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        Logger.i("AlaMobileTool", "addGamingOverlays: pedalMode=${settings.pedalMode} root=${root} rootHash=${System.identityHashCode(root)}")

        val gearPosition = settings.gearPosition
        val pedalPosition = settings.pedalPosition
        val brakePosition = settings.brakePosition
        val singlePosition = settings.singlePedalPosition

        // TC/ABS 介入指示灯：开关开启时创建，GONE 等待 toggle 展开。
        // 位置固定（直边中点贴屏幕上边缘中点），无拖拽/编辑层/持久化。
        // 尺寸：宽 = 屏宽 1/3，高 = 屏高 1/30（当前设备方向，addGamingOverlays
        // 在旋转/重建时重入，displayMetrics 即当前方向）。
        if (settings.enableTcAbsIndicator) {
            indicatorView = TcAbsIndicatorView(
                appContext,
                (screenWidth / 3f).roundToInt(),
                (screenHeight / 30f).roundToInt()
            ).apply {
                tag = "tc_abs_indicator"
                visibility = View.GONE
            }
            val indicatorParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            root?.addView(indicatorView, indicatorParams)
        } else {
            indicatorView = null
        }

        // 手动换挡关时不创建换挡控件；gearView 保持 null。
        // DUAL 模式下也不创建——刹车和换挡默认坐标相同（左下角），
        // 同时开会重叠导致触摸冲突。用户明确要求两者不能同时开，
        // 当前换挡开关 UI 禁用，这里加运行时守卫防 JSON 手动编辑或
        // 未来 UI bug 导致两者同时 true。
        if (settings.enableManualShift && settings.pedalMode != ModConfig.PedalMode.DUAL) {
            gearView = GearShiftView(appContext, settings).apply {
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
            addGearEditLayer()
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
                addPedalEditLayer(singlePosition, ModConfig.KEY_SINGLE_PEDAL_POSITION)
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
                addPedalEditLayer(pedalPosition, ModConfig.KEY_PEDAL_POSITION)

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
                addBrakeEditLayer()
            }
        }
    }

    private fun addPedalEditLayer(runtimePosition: OverlayPosition, positionKey: String) {
        val pedal = pedalView ?: return
        val parent = root ?: return
        pedalEditView = createEditLayer(
            appContext,
            parent,
            pedal,
            "pedal_overlay_edit",
            // 长按重置到出厂默认（OverlayPosition.DEFAULT_*），不再用运行时
            // 已保存的 position——否则"重置"只是回到当前已保存值，用户感知
            // 无变化。出厂默认是固定值，重置才有意义。
            OverlayPosition.DEFAULT_PEDAL,
            positionKey,
            startVisible = editMode,
            ::isBlankArea,
            ::resetPedalsToDefault
        )
    }

    private fun addBrakeEditLayer() {
        val brake = brakeView ?: return
        val parent = root ?: return
        brakeEditView = createEditLayer(
            appContext,
            parent,
            brake,
            "brake_overlay_edit",
            OverlayPosition.DEFAULT_BRAKE,
            ModConfig.KEY_BRAKE_POSITION,
            startVisible = editMode,
            ::isBlankArea,
            ::resetPedalsToDefault
        )
    }

    private fun addGearEditLayer() {
        val gear = gearView ?: return
        val parent = root ?: return
        gearEditView = createEditLayer(
            appContext,
            parent,
            gear,
            "gear_shift_overlay_edit",
            OverlayPosition.DEFAULT_GEAR,
            ModConfig.KEY_GEAR_POSITION,
            startVisible = editMode,
            ::isBlankArea,
            ::resetPedalsToDefault
        )
    }

    /**
     * 空白判定：本地坐标 (x, y)（相对某个编辑层）不属于**任何**编辑层矩形、
     * 也不在工具按钮上。注入给每个 OverlayEditView——触摸只会派给 z 序
     * 最高的编辑层，高层必须替兄弟层"让路"（否则把落在兄弟矩形内的点当
     * 空白吃掉，兄弟的拖拽永远收不到事件）。工具按钮排除在外，保留长按
     * 退出编辑模式的穿透路径。
     *
     * 各编辑层铺满整屏、同父容器，矩形在每层的本地坐标里原点相同，一个
     * 聚合函数可服务所有层。
     */
    private fun isBlankArea(x: Float, y: Float): Boolean {
        fun inside(left: Int, top: Int, width: Int, height: Int): Boolean =
            !(x < left - OverlayEditView.CORNER_HIT_RADIUS || y < top - OverlayEditView.CORNER_HIT_RADIUS ||
                x > left + width + OverlayEditView.CORNER_HIT_RADIUS ||
                y > top + height + OverlayEditView.CORNER_HIT_RADIUS)

        listOfNotNull(pedalEditView, brakeEditView, gearEditView).forEach { layer ->
            if (inside(layer.editLeft, layer.editTop, layer.editWidth, layer.editHeight)) return false
        }
        // 工具按钮矩形（屏幕坐标 → 编辑层本地坐标；编辑层与按钮同父容器、
        // 无滚动，本地坐标一致）。按钮自身消费触摸，这里排除防长按 3s 误触
        // （按住按钮 3s 是退出编辑模式，不是重置踏板）。
        toggleButton?.let { btn ->
            if (x >= btn.left && x < btn.left + btn.width &&
                y >= btn.top && y < btn.top + btn.height
            ) return false
        }
        return true
    }

    /**
     * 长按空白处 3s：把**当前显示**的单/双踏板恢复出厂默认位置（含大小）。
     * 换挡控件不在范围（按钮语义只提踏板，与提示文案一致）；每套踏板的
     * 重置经编辑层已有的 updateTarget → onChanged 链路落盘，与拖拽持久化
     * 同一条路径。
     */
    private fun resetPedalsToDefault() {
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        var any = false
        listOfNotNull(pedalEditView, brakeEditView).forEach { layer ->
            layer.resetToDefault(screenWidth, screenHeight)
            any = true
        }
        Logger.i("AlaMobileTool", "resetPedalsToDefault: layers=$any")
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
            Logger.e("AlaMobileTool", "Failed to save overlay position", e)
        }
    }

    private fun removeGamingOverlays() {
        // 只移除游戏控件，保留 toggle 按钮——toggle 操作时按钮本身要留着
        // 供用户再次点击，只需重建踏板/换挡 view 反映最新配置。
        // 用局部 val 快照 root，避免 var 的 smart cast 限制。
        val parent = root ?: return
        Logger.i("AlaMobileTool", "removeGamingOverlays: root=${parent} rootHash=${System.identityHashCode(parent)}")
        parent.findViewWithTag<View>("pedal_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("tc_abs_indicator")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay_edit")?.let { parent.removeView(it) }
        // ⚠️ 变暗层/提示层**不在这里移除，字段引用也绝不能清**：它们的生命
        // 周期由 syncEditMode 的 alpha 动画管理（退出后 alpha=0 常驻，不消费
        // 触摸）。编辑模式中配置变更重建会走到这里——若清了引用，ensureDimView/
        // ensureHintView 命中不了 takeIf 复用，会**新建第二份** dim/hint 层
        // （旧层 alpha=1 仍挂在树上无人管理），用户长按退出时 animateFade 只
        // 淡出新层，旧层 alpha=1 永久盖屏（实测：编辑模式切踏板模式 → 退出
        // → 黑屏 + 三行提示常驻、BGM 正常）。不清引用则 rebuild 命中复用：
        // 旧层 alpha 已是 1，1→1 动画为空操作，天然不重播渐暗。整层拆除只在
        // removeExisting（showOverlays 全量重建）里做。编辑层三个 tag 仍在此
        // 移除并清引用——它们随配置重建，且移除后残留引用才真的是死 view。
        pedalView = null
        brakeView = null
        gearView = null
        indicatorView = null
        pedalEditView = null
        brakeEditView = null
        gearEditView = null
        // dimView/hintView 保留（见上）：rebuild 复用，编辑模式期间永远只存在一份。
    }

    private fun removeExisting() {
        val parent = root ?: return
        parent.findViewWithTag<View>("ala_tool_toggle")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("tc_abs_indicator")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("pedal_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("brake_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("gear_shift_overlay_edit")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("overlay_dim")?.let { parent.removeView(it) }
        parent.findViewWithTag<View>("overlay_edit_hint")?.let { parent.removeView(it) }
        pedalView = null
        brakeView = null
        gearView = null
        toggleButton = null
        indicatorView = null
        pedalEditView = null
        brakeEditView = null
        gearEditView = null
        dimView = null
        hintView = null
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
