package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * "工具" 按钮：96×96 dp 圆角矩形 + 居中的 App 图标前景。
 *
 * 设计要点：
 * 1. **视觉**：圆角矩形底色 #000814（同 adaptive icon 背景色），前景是
 *    `R.mipmap.ic_launcher_foreground` 的 PNG 解码出的 Bitmap，按 density
 *    自适应缩放并居中绘制。Android 系统对 launcher 图标会按设备 mask 切形状，
 *    这里我们手动画圆角矩形——形状确定，不随设备 launcher 变。
 *
 * 2. **交互**（三套手势互不冲突）：
 *    - **单击**（不超 touchSlop 且 < 500ms）：切换 overlay 展开/折叠。
 *    - **长按**（500ms 内未超 touchSlop）：进入其他 overlay 的编辑模式
 *      （让用户拖拽/缩放油门/刹车/换挡），和原 Button 长按语义一致。
 *    - **拖动**（超 touchSlop 立刻转 MOVE）：移动按钮自身位置，拖动结束经
 *      onPositionChanged 上抛 OverlayManager → saveOverlayPosition 落盘
 *      （KEY_TOOL_POSITION，默认启用记忆位置）。
 *    MOVE 与长按互斥：MOVE 取消长按 runnable；长按 runnable 触发时设
 *    `longPressHandled` 拦截后续 MOVE 升级。
 *
 * 3. **位置持久化**：构造函数传入的 defaultPosition 是记忆位置（未拖过时
 *    = Defaults.TOOL_BUTTON_POSITION），[applySavedPosition] 应用它；
 *    拖动结束回调上抛 OverlayManager 写本地 externalFilesDir JSON。
 */
class ToolButtonView(
    context: Context,
    private val defaultPosition: OverlayPosition
) : View(context) {

    /**
     * XML 兼容构造器（满足 lint ViewConstructor 警告）。
     * 当前没在 layout XML 里用（OverlayManager 都是 new 出来的），但 Android
     * inflate 路径会反射调 (Context, AttributeSet) 构造器——缺了运行时崩溃。
     * 转发到主构造器并给个兜底 defaultPosition。运行时 applySavedPosition 仍会
     * 把位置拉回这个兜底值（不影响主流程，因为主流程都是程序化构造）。
     */
    @Suppress("unused")
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(
        context,
        OverlayPosition(0.03f, 0.04f, 0.12f, 0.12f)
    )

    /** 单击触发（切换 overlay 展开/折叠）。 */
    var onClick: (() -> Unit)? = null

    /** 长按触发（进入其他 overlay 的编辑模式）。 */
    var onLongPress: (() -> Unit)? = null

    /**
     * 拖动结束触发（left, top, width, height in pixels）。
     * OverlayManager 接到 saveOverlayPosition(KEY_TOOL_POSITION) 落盘——
     * 下次 showOverlays 时经 settings.toolButtonPosition 回放。
     */
    var onPositionChanged: ((left: Int, top: Int, width: Int, height: Int) -> Unit)? = null

    companion object {
        // 工具按钮大小 = 屏幕高度的 10%（动态像素）。如 1080×2400 屏 → 240px。
        // 既不是 dp 也不是固定 px——跨设备视觉比例一致（占屏高 1/10）。
        private const val SIZE_RATIO = 0.10f
        // 圆角半径 = size 的 25%（240px → 60px 圆角），视觉比例跟 adaptive icon 一致。
        private const val CORNER_RADIUS_RATIO = 0.25f
        // 前景 PNG 内边距 = 0：直接铺满圆角矩形，不手动 inset。
        // 但 adaptive icon 前景 PNG 自带 34% 透明 padding——铺满后仍有
        // "一圈黑"。解法：用 FOREGROUND_SCALE=1.5 把 PNG 放大 50% 画上去，
        // 让中心 66% 内容铺满圆角矩形，外围 34% 透明区被 canvas.clipRoundRect
        // 裁掉。这等价于"只显示中心 66%"——但 66% 内容填满整个圆角矩形。
        private const val FOREGROUND_INSET_RATIO = 0.0f
        private const val FOREGROUND_SCALE = 1.5f

        // 与 adaptive icon 背景色保持一致（drawable/ic_launcher_background.xml
        // 的 #000814），让工具按钮和应用图标视觉同源。
        private const val BG_COLOR = 0xFF000814.toInt()

        // 长时间不点击的微弱呼吸提示（alpha 在 220~255 间 1.6s 一次循环）。
        // 关闭→255，循环让用户知道按钮"活着"但不打拢游戏。0.0f 表示关闭。
        private const val PULSE_ENABLED = true
        private const val PULSE_PERIOD_MS = 1600L
    }

    // 动态尺寸：屏幕高度的 10%。init 时算一次，后续 onMeasure/onDraw 复用。
    // 横屏游戏：heightPixels 是当前方向的可用高度（短边），如 1080×2400 横屏
    // → heightPixels=1080 → sizePx=108。
    private val screenHeightPx = context.resources.displayMetrics.heightPixels
    private val screenWidthPx = context.resources.displayMetrics.widthPixels
    private val sizePx = (screenHeightPx * SIZE_RATIO).toInt()
    private val cornerRadiusPx = (sizePx * CORNER_RADIUS_RATIO).toInt()
    private val foregroundInsetPx = (sizePx * FOREGROUND_INSET_RATIO).toInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG_COLOR
        style = Paint.Style.FILL
    }
    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    // 缓存前景 bitmap：96dp ≈ 288px@xxxhdpi，foreground PNG 是 576×576，
    // 缩放下采样一次就够，无需每次 onDraw 重 decode。attachToWindow 时
    // 加载，detachFromWindow 时回收——避免 background process 持 bitmap。
    private var foregroundBitmap: Bitmap? = null
    // 复用的 RectF + Path：onDraw 高频调用，preallocate 避免 DrawAllocation
    // lint 警告 + 减少 GC 压力（每帧分配会被 lint 标记）。
    private val drawRect = RectF()
    private val foregroundRect = RectF()
    private val clipPath = Path()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressHandler = android.os.Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (mode == Mode.NONE && !longPressHandled) {
            longPressHandled = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            mode = Mode.LONG_PRESS_FIRED
            onLongPress?.invoke()
        }
    }

    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var longPressHandled = false

    init {
        isClickable = true
        android.util.Log.i(
            "AlaMobileTool",
            "ToolButtonView init: screen ${screenWidthPx}x${screenHeightPx} " +
                "sizePx=$sizePx cornerRadiusPx=$cornerRadiusPx insetPx=$foregroundInsetPx"
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadForegroundBitmap()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        longPressHandler.removeCallbacks(longPressRunnable)
        // 不 recycle bitmap——多个 OverlayManager 重建/挂载场景下 bitmap
        // 是 immutable input，回收会导致下一次 decode 之前的 view 拿到
        // 已回收的引用。让 GC 回收即可。
        foregroundBitmap = null
    }

    private fun loadForegroundBitmap() {
        if (foregroundBitmap != null) return
        foregroundBitmap = try {
            // ToolButtonView 跑在游戏进程（LSPosed 注入），不在模块自己的进程。
            // 游戏进程的 Resources / PackageManager 不认识模块包（Android 11+
            // 包可见性 + scoped storage），decodeResource 返回 null，
            // getResourcesForApplication 抛 NameNotFoundException。
            //
            // 解法：把 ldpi 144x144 PNG base64 嵌入 Kotlin 源码（见
            // [ToolButtonIcon.kt]），运行时 Base64.decode + decodeByteArray。
            // 完全绕开资源系统，跨进程 100% 可靠。
            val bytes = android.util.Base64.decode(
                TOOL_BUTTON_ICON_BASE64,
                android.util.Base64.DEFAULT
            )
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
            android.util.Log.i(
                "AlaMobileTool",
                "ToolButtonView.loadForegroundBitmap: " +
                    "bytes=${bytes.size} " +
                    "result=${if (bmp != null) "${bmp.width}x${bmp.height} config=${bmp.config}" else "null"}"
            )
            bmp
        } catch (e: Throwable) {
            android.util.Log.e("AlaMobileTool", "ToolButtonView.loadForegroundBitmap threw", e)
            null
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 强制以 96dp×96dp 渲染——layoutParams.width/height 可能被 OverlayManager
        // 误设其他值；这里是固定控件，忽略 MeasureSpec。
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 圆角矩形底（复用 drawRect，preallocated 避免每帧分配）。
        drawRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(drawRect, cornerRadiusPx.toFloat(), cornerRadiusPx.toFloat(), backgroundPaint)

        // 2. App 图标前景：放大 FOREGROUND_SCALE 倍（1.5x）居中绘制，
        //    用 clipRoundRect 裁掉超出圆角矩形的部分。
        //    adaptive icon 前景 PNG 自带 34% 透明 padding，放大 1.5x 后
        //    中心 66% 内容铺满圆角矩形，外围 34% 透明区被裁掉——
        //    不再有"一圈黑"。
        val bmp = foregroundBitmap
        if (bmp != null) {
            canvas.save()
            // clipRoundRect 在 Canvas API 里不存在——用 Path + clipPath 替代。
            // 构造圆角矩形 Path，clipPath 后超出圆角矩形的内容被裁掉。
            clipPath.reset()
            clipPath.addRoundRect(
                0f, 0f, w, h,
                cornerRadiusPx.toFloat(), cornerRadiusPx.toFloat(),
                Path.Direction.CW
            )
            canvas.clipPath(clipPath)
            // 放大 1.5x 居中：drawW = w * 1.5, 超出 w 的部分被 clip 裁掉
            val drawW = w * FOREGROUND_SCALE
            val drawH = h * FOREGROUND_SCALE
            val left = (w - drawW) / 2f
            val top = (h - drawH) / 2f
            foregroundRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, null, foregroundRect, foregroundPaint)
            canvas.restore()
        } else {
            // 临时诊断：bitmap 没加载到位时画个白色的"?"提示。
            val text = "?"
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 48f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(text, w / 2f, h / 2f + 16f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                startTouchX = event.rawX
                startTouchY = event.rawY
                val params = layoutParams as? FrameLayout.LayoutParams
                startLeft = params?.leftMargin ?: 0
                startTop = params?.topMargin ?: 0
                longPressHandled = false
                mode = Mode.NONE
                longPressHandler.postDelayed(longPressRunnable, 500)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE && !longPressHandled) {
                    if (abs(event.rawX - startTouchX) > touchSlop ||
                        abs(event.rawY - startTouchY) > touchSlop
                    ) {
                        // 超过 touchSlop 转 MOVE，立刻取消长按 runnable——
                        // 一旦开始拖动，再触发"进入编辑模式"会让用户困惑。
                        mode = Mode.MOVE
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
                if (mode == Mode.MOVE) {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    // 双向钳制：下界 0 防负 margin，上界保证按钮至少留一半在屏内——
                    // 位置现在会持久化（记忆位置），无上界时按钮可被拖到屏外且
                    // 永久丢在屏外（旧版每次启动重置会自愈，记忆化后不会）。
                    val maxLeft = max(0, screenWidthPx - sizePx / 2)
                    val maxTop = max(0, screenHeightPx - sizePx / 2)
                    val newLeft = (startLeft + dx).coerceIn(0, maxLeft)
                    val newTop = (startTop + dy).coerceIn(0, maxTop)
                    applyLayout(newLeft, newTop, false)
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                when (mode) {
                    Mode.NONE -> {
                        // 没拖动、没触发长按 → 视为单击。
                        // 调 performClick() 让 Android a11y 服务（TalkBack / switch control）
                        // 也能触发 onClick——避免 ClickableViewAccessibility lint 警告。
                        if (!longPressHandled) performClick()
                    }
                    Mode.MOVE -> {
                        // 拖动结束：上抛最终位置。OverlayManager 决定是否落盘——
                        // 当前需求下不落盘，所以这个回调其实是 no-op（OverlayManager
                        // 把它接到一个空 lambda）。保留回调是为以后"打开持久化"用。
                        val params = layoutParams as? FrameLayout.LayoutParams
                        onPositionChanged?.invoke(
                            params?.leftMargin ?: 0,
                            params?.topMargin ?: 0,
                            width,
                            height
                        )
                    }
                    Mode.LONG_PRESS_FIRED -> {
                        // 长按已触发；UP 不再做事
                    }
                }
                mode = Mode.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 覆写 performClick()：onTouchEvent 在 ACTION_UP 调它，a11y 服务合成
     * click 事件时也走这里——两条路径都触发 onClick 回调。
     * 同时满足 [ClickableViewAccessibility] lint 警告（要求自定义触摸
     * 处理的 View 覆写 performClick）。
     */
    override fun performClick(): Boolean {
        val handled = super.performClick()
        onClick?.invoke()
        return handled || onClick != null
    }

    /**
     * 把 View 位置/大小应用到 layoutParams。fireCallback=false 时只更新
     * View（拖动过程中减少回调噪声），true 时同时通知外部（拖动结束）。
     */
    private fun applyLayout(left: Int, top: Int, fireCallback: Boolean) {
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        params.leftMargin = left
        params.topMargin = top
        params.width = sizePx
        params.height = sizePx
        layoutParams = params
        if (fireCallback) {
            onPositionChanged?.invoke(left, top, sizePx, sizePx)
        }
    }

    /**
     * 应用记忆位置（未拖过时 = 默认位置）。由 OverlayManager 在
     * [showOverlays] 调用——位置来自本地 externalFilesDir 的持久化值
     * （KEY_TOOL_POSITION），随 settings.toolButtonPosition 传入。
     */
    fun applySavedPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        // 回放也要钳制：防旧记录/跨设备比例越界（如从平板备份到小屏手机）。
        val maxLeft = max(0, screenWidth - sizePx / 2)
        val maxTop = max(0, screenHeight - sizePx / 2)
        val left = defaultPosition.leftPx(screenWidth).coerceIn(0, maxLeft)
        val top = defaultPosition.topPx(screenHeight).coerceIn(0, maxTop)
        applyLayout(left, top, fireCallback = false)
    }

    private enum class Mode { NONE, MOVE, LONG_PRESS_FIRED }
}
