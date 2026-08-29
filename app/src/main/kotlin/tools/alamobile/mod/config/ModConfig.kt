package tools.alamobile.mod.config

import android.content.Context
import android.content.Intent
import android.os.Environment
import org.json.JSONObject
import java.io.File
import tools.alamobile.mod.overlay.OverlayPosition

/**
 * JSON-backed configuration for Ala Mobile Tool.
 *
 * The ConfigActivity writes settings to a JSON file in external storage
 * so the target game process can read the same file without relying on
 * deprecated [Context.MODE_WORLD_READABLE].
 */
object ModConfig {

    private const val TAG = "AlaMobileTool"
    private const val FILE_NAME = "ala_tool_config.json"
    private const val MODULE_PACKAGE = "tools.alamobile.mod"

    // 目标游戏包名（原版 + 共存版）。ConfigActivity（模块进程）写配置时
    // 需通过 createPackageContext 拿到游戏包的 externalFilesDir，才能让
    // 游戏进程读到——因为 Android 11+ scoped storage 下两个进程的
    // externalFilesDir 是不同沙箱，按 packageName 分流会让它们读写不同文件。
    private val GAME_PACKAGES = setOf(
        "com.Vince.AlamobileFormula",
        "com.Takotsubo.AlamobileFormula"
    )

    // 游戏进程读取模块权威配置的注入点。
    //
    // Remote Preferences 路线（libxposed API 102）：AlaMobileModule（继承 XposedModule）
    // 在 onPackageReady 调 getRemotePreferences(PREF_GROUP).getString(KEY_CONFIG_JSON)，
    // 经 Binder 路由到 LSPosed daemon（常驻进程），读 daemon SQLite 里的模块配置 JSON。
    // 不依赖模块进程或游戏进程是否在运行——根治"游戏没运行→广播丢失→
    // 下次启动读旧值"的 M11 首次滞后 bug。
    //
    // 设为可空：模块进程（ConfigActivity）不设置它，只读本地 filesDir；
    // 游戏进程 AlaMobileModule 在 onPackageReady 早期设置。readFromTargetProcess
    // 优先用它，失败（null 或异常）回退现有 externalFilesDir 路径——广播方案
    // 在游戏运行时仍可靠，作为兜底。
    var remoteConfigReader: (() -> String?)? = null

    // Feature toggles
    const val KEY_ENABLE_AUTO_DRS = "enable_auto_drs"
    const val KEY_DISABLE_AUTO_GEAR = "disable_auto_gear"
    const val KEY_ENABLE_MANUAL_SHIFT = "enable_manual_shift"
    const val KEY_ENABLE_UNLOCK = "enable_unlock"
    // 原生牵引力控制（TC）/ 防抱死（ABS）。默认开启。
    // 借"游戏手柄已连接"机制：非手柄模式下游戏默认关 TC/ABS，
    // 模块强制玩家车 tclEnable/absEnable 打开即可原生生效，且只作用玩家车
    //（IRDSPlayerControls 组件只挂玩家车）——不破坏陀螺仪/触摸转向，也顺手
    // 根治 M18 的 AI 误控（原生输入链路天然只处理玩家车）。
    const val KEY_ENABLE_TC = "enable_tc"
    const val KEY_ENABLE_ABS = "enable_abs"
    // TC 档位调节（v1 TC_LEVEL_DESIGN）：模式 + 强度 + 时机。
    // 游戏设置无任何 TC 参数可调（仅手柄生效的开关，且开关位被游戏每帧覆写），
    // 模块档位是移动端唯一 TC 调节途径。存字符串枚举值（非浮点），实机标定
    // 改预设表数值不动用户存档。
    const val KEY_TC_MODE = "tc_mode"
    const val KEY_TC_STRENGTH = "tc_strength"
    const val KEY_TC_TIMING = "tc_timing"
    // ABS 档位调节（ABS_LEVEL_DESIGN v2）：模式 + 干预强度 + 制动压力。
    // 干预强度 = pulse 释放深度 b(0x3E0) 绝对覆写——游戏默认配平（bias=60）下
    // b=0，pulse 帧完全泄压，方波 [F_base·Ω, 0] 平均 0.5（"全段几乎不锁死"
    // 过度保护的根源）；抬 b 抬方波平均 (1+b)/2。制动压力 = T_b(0x88) 等比
    // 缩放（F1 官方游戏同名 setup 项 80-100%），独立于 ABS 模式生效——修
    // "关 ABS 秒锁死"（制动基数远超抓地极限）。存字符串枚举 + 浮点，
    // 实机标定只改预设值不动用户存档。
    const val KEY_ABS_MODE = "abs_mode"
    const val KEY_ABS_STRENGTH = "abs_strength"
    const val KEY_ABS_PRESSURE = "abs_pressure"

    // 主菜单音乐替换开关：替换为 Hans Zimmer - F1
    const val KEY_ENABLE_MUSIC_REPLACE = "enable_music_replace"

    // V10 引擎声浪开关：替换开场动画的引擎声为 V10 声浪
    const val KEY_ENABLE_V10_SOUND = "enable_v10_sound"

    // 隐藏游戏原生油门和刹车按钮（不隐藏离合）。
    // native 层通过 IRDSUIMobileControls 单例遍历布局 GameObject 子物体，
    // 按名字匹配 "Throttle"/"Brake" 并 SetActive(false)。
    const val KEY_HIDE_GAME_PEDALS = "hide_game_pedals"

    // Pedal mapping
    const val KEY_PEDAL_MODE = "pedal_mode"
    const val KEY_PEDAL_DEADZONE = "pedal_deadzone"
    const val KEY_PEDAL_TRANSITION = "pedal_transition"
    const val KEY_BRAKE_TRANSITION = "brake_transition"
    // 油门过渡点：THROTTLE_VALUE 仲裁策略下，油门值 ≥ 此点 → 油门优先。
    const val KEY_THROTTLE_TRANSITION = "throttle_transition"
    // 双踏板仲裁策略：决定油门/刹车同时按下时谁优先。
    const val KEY_PEDAL_PRIORITY = "pedal_priority"
    // 踏板方向反转：DUAL 模式下反转油门/刹车踏板的填充方向。
    // 关闭=默认方向（手指顶部满）；仅油门/仅刹车/油门和刹车分别控制各踏板。
    const val KEY_PEDAL_INVERT = "pedal_invert"
    const val KEY_THROTTLE_CURVE = "throttle_curve"
    const val KEY_BRAKE_CURVE = "brake_curve"
    // 自定义曲线控制点列表 (x,y) ∈ [0,1]²。曲线是过 (0,0)、各控制点、(1,1) 的
    // 分段线性插值。空列表 = 只有两端点 = 线性。
    const val KEY_THROTTLE_CURVE_POINTS = "throttle_curve_points"
    const val KEY_BRAKE_CURVE_POINTS = "brake_curve_points"
    // Overlay 视觉属性（透明度/边框粗细/圆角），作用于踏板和换挡控件。
    const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    const val KEY_OVERLAY_BORDER_WIDTH = "overlay_border_width"
    const val KEY_OVERLAY_CORNER_RADIUS = "overlay_corner_radius"

    // Legacy keys (kept only for one-way migration on read)
    const val KEY_LEGACY_ENABLE_CONTROL_REPLACEMENT = "enable_control_replacement"
    const val KEY_LEGACY_PEDAL_CURVE = "pedal_curve"
    // Legacy: 刹车踏板方向反转（boolean），迁移到 pedal_invert 枚举。
    // true → PedalInvert.BRAKE（仅刹车踏板），false → PedalInvert.OFF（关闭）。
    const val KEY_LEGACY_BRAKE_INVERT = "brake_invert"

    // Overlay positions
    const val KEY_PEDAL_POSITION = "pedal_position"
    const val KEY_GEAR_POSITION = "gear_position"
    const val KEY_BRAKE_POSITION = "brake_position"
    // SINGLE 模式专用位置字段：与 DUAL 油门位置（pedal_position）分离，
    // 避免用户在 SINGLE 模式拖拽的 position 污染 DUAL 油门 view 的位置。
    const val KEY_SINGLE_PEDAL_POSITION = "single_pedal_position"
    // "工具" 按钮位置。架构上和 pedal/gear/brake 一致（OverlayPosition 比例存），
    // 默认启用记忆：游戏进程拖拽时经 saveOverlayPosition 写本地 externalFilesDir
    // （未拖过时本地无此 key，回放用 Defaults.TOOL_BUTTON_POSITION = 原默认位置，
    // 行为与旧版"每次重置"一致）。
    const val KEY_TOOL_POSITION = "tool_button_position"

    // position 字段由游戏进程持有（拖拽时 saveOverlayPosition 写），
    // ConfigActivity 广播的 JSON 不含这些字段。合并写时（ConfigReceiver
    // 收到广播、readFromTargetProcess 从 provider 拉取）跳过这些 key，
    // 保留游戏进程已有的 position 值。
    val POSITION_KEYS = setOf(
        KEY_PEDAL_POSITION,
        KEY_GEAR_POSITION,
        KEY_BRAKE_POSITION,
        KEY_SINGLE_PEDAL_POSITION,
        KEY_TOOL_POSITION
    )

    // Debug/logging
    const val KEY_LOG_ENABLED = "log_enabled"

    /**
     * Pedal overlay topology.
     * - OFF: no pedal overlay (game default input untouched).
     * - SINGLE: one vertical view split into throttle (top) + brake (bottom)
     *   around [Settings.pedalTransition]; deadzone + transition apply.
     * - DUAL: two independent full-travel views, one for throttle, one for
     *   brake. No transition line and no deadzone — each finger maps directly
     *   0..1 across its own view.
     */
    enum class PedalMode(val value: String) {
        OFF("off"),
        SINGLE("single"),
        DUAL("dual");

        companion object {
            fun from(value: String?): PedalMode {
                return entries.find { it.value == value } ?: SINGLE
            }
        }
    }

    /**
     * Response curve applied on top of the raw 0..1 pedal travel.
     *
     * LINEAR is identity. CUSTOM uses a list of draggable control points;
     * the curve is a piecewise-linear interpolation through (0,0), each
     * control point, and (1,1). An empty list = linear.
     */
    enum class PedalCurve(val value: String) {
        LINEAR("linear"),
        CUSTOM("custom");

        companion object {
            fun from(value: String?): PedalCurve {
                // Legacy configs may carry "quadratic" or "exponential"; map both
                // to CUSTOM so old users keep a non-linear feel instead of falling
                // back to linear silently. The control point list defaults to empty
                // (= linear); users can add points in the custom curve editor.
                if (value == "quadratic" || value == "exponential") return CUSTOM
                return entries.find { it.value == value } ?: LINEAR
            }
        }
    }

    /**
     * A single control point on the custom response curve, in normalized
     * [0,1]×[0,1] space (x = travel, y = output). Points must be sorted by
     * x and are interpolated with a monotone cubic spline.
     */
    data class CurvePoint(val x: Float, val y: Float)

    /**
     * Pedal direction reverse mode for DUAL pedal mode.
     *
     * - OFF: neither pedal inverted (default — finger top = full).
     * - THROTTLE: only the throttle pedal fills top-down (finger bottom = full).
     * - BRAKE: only the brake pedal fills top-down (finger bottom = full).
     * - BOTH: both pedals inverted.
     *
     * Migration: the legacy `brake_invert` boolean (true) maps to [BRAKE].
     */
    enum class PedalInvert(val value: String) {
        OFF("off"),
        THROTTLE("throttle"),
        BRAKE("brake"),
        BOTH("both");

        val invertThrottle: Boolean get() = this == THROTTLE || this == BOTH
        val invertBrake: Boolean get() = this == BRAKE || this == BOTH

        companion object {
            fun from(value: String?): PedalInvert {
                return entries.find { it.value == value } ?: OFF
            }
        }
    }

    /**
     * Dual-pedal arbitration strategy: decides which pedal wins when both
     * are pressed simultaneously.
     *
     * - FIRST_PRESSED: the pedal that was pressed earliest and is still held
     *   wins. When it is released, the other pedal takes over.
     * - LAST_TOUCHED: the pedal that was most recently touched wins.
     * - ALWAYS_THROTTLE: throttle always wins when both are pressed.
     * - ALWAYS_BRAKE: brake always wins when both are pressed.
     * - THROTTLE_VALUE: throttle raw value ≥ [Settings.throttleTransition] →
     *   throttle wins, otherwise brake wins (if brake > 0).
     * - BRAKE_VALUE: brake raw value ≥ [Settings.brakeTransition] → brake wins,
     *   otherwise throttle wins (if throttle > 0). This is the legacy behavior.
     *
     * Migration: old configs without `pedal_priority` key default to
     * [BRAKE_VALUE], preserving the legacy arbitration behavior.
     */
    enum class PedalPriority(val value: String) {
        FIRST_PRESSED("first_pressed"),
        LAST_TOUCHED("last_touched"),
        ALWAYS_THROTTLE("always_throttle"),
        ALWAYS_BRAKE("always_brake"),
        THROTTLE_VALUE("throttle_value"),
        BRAKE_VALUE("brake_value");

        companion object {
            fun from(value: String?): PedalPriority {
                return entries.find { it.value == value } ?: BRAKE_VALUE
            }
        }
    }

    /**
     * TC 调节模式。
     * - DEFAULT: 游戏默认（native 纯透传，行为等价于 CUSTOM + MAX + LATE）
     * - CUSTOM: 展开强度/时机两个调节卡片
     *
     * 迁移：旧配置无 `tc_mode` 键时，从旧 `enable_tc` 布尔派生
     *（false → CUSTOM+OFF；true → DEFAULT），见 [migrateTc]。
     */
    enum class TcMode(val value: String) {
        DEFAULT("default"),
        CUSTOM("custom");

        companion object {
            fun from(value: String?): TcMode {
                return entries.find { it.value == value } ?: DEFAULT
            }
        }
    }

    /**
     * TC 介入强度档：TractionFilter 返回值插值系数 [mix]。
     *
     * 游戏原生合成在 carController 内联完成：τ' = 0.15τ + 0.85·filtered。
     * 模块在 TractionFilter 返回点做线性插值 f_m = τ + (f−τ)·[mix]，代入得
     * τ' = τ·(1 − 0.85·[mix]·S)——数学上严格等价于把削减系数缩放为 0.85×mix：
     * mix=1 逐位等同游戏原厂（削减上限 85%），mix=0 完全关闭（proxy 直接
     * return accel，走既有实测路径）。
     *
     * 初值标定于 2026-08；实机手感调优只改 [mix] 常数，不动存档结构。
     */
    enum class TcStrength(val value: String, val mix: Float) {
        OFF("off", 0f),
        // value 沿用历史 JSON 键值（"weak"/"strong"/"stock"），改档位名不做存档迁移
        LOW("weak", 0.15f),
        MEDIUM("medium", 0.4f),
        HIGH("strong", 0.6f),
        MAX("stock", 1f);

        companion object {
            fun from(value: String?): TcStrength {
                return entries.find { it.value == value } ?: MAX
            }
        }
    }

    /**
     * TC 介入时机档：每帧覆写 TCLSlip (0x34) 实例字段为 [eps]（绝对值，勿缩放写，
     * 防每帧复利衰减）。游戏削减触发条件 W > 1/(1−ε)，ε=0.45（游戏默认）→ W>1.82。
     * [eps] 越小介入越早、曲线越陡；ε=0.02 ≈ W>1.02（几乎任何打滑立即削）。
     *
     * [eps] = 0 表示"游戏默认"——不覆写字段，TC 阈值行为与原生一致
     *（游戏设置无 TC 参数 UI，原生值即实际行为）。
     */
    // 介入时机档 = (eps, minspd) 配对。反汇编确认（v1.4）：TractionFilter
    // 门控顺序 ①carSpeed<TCLminSPD → 透传（在读 ε 之前）→ ②TCLSlip==0 →
    // ③tclEnable → ④(1-ε)·W>1。游戏运行时 minSPD=11.0 m/s（≈40km/h），
    // 只调 ε 时起步打滑区间被门控①整段挡死——所以每个非默认时机档必须
    // 同时给出 minSPD 覆写值（m/s）。LATE 双 0 = 不写字段。
    enum class TcTiming(val value: String, val eps: Float, val minspd: Float) {
        LATE("default", 0f, 0f),
        EARLY("earlier", 0.35f, 8.0f),
        VERY_EARLY("very_early", 0.25f, 4.0f),
        REALTIME("realtime", 0.02f, 0.5f);

        companion object {
            fun from(value: String?): TcTiming {
                return entries.find { it.value == value } ?: LATE
            }
        }
    }

    /**
     * ABS 调节模式。
     * - DEFAULT: 游戏默认（不覆写 b，usesABS 保持原生）
     * - CUSTOM: 展开干预强度调节卡片
     *
     * 迁移：旧配置无 `abs_mode` 键时，从旧 `enable_abs` 布尔派生
     *（false → CUSTOM+OFF；true → DEFAULT），见 [migrateAbs]。
     */
    enum class AbsMode(val value: String) {
        DEFAULT("default"),
        CUSTOM("custom");

        companion object {
            fun from(value: String?): AbsMode {
                return entries.find { it.value == value } ?: DEFAULT
            }
        }
    }

    /**
     * ABS 干预强度档：pulse 相位释放深度 b (0x3E0) 的绝对覆写值。
     *
     * 游戏 SetBrakeBiasValues 计算：前轮 b = clamp01((bias−60)/10)×0.3、后轮
     * 对称——bias=60（中点）时前后轮 b 全为 0，pulse 帧 T×0 **完全泄压**，
     * 方波 [F_base·Ω, 0] 平均 0.5×F_base·Ω（"全段几乎不锁死"过度保护的
     * 根源，ABS_LEVEL_DESIGN v2 §2.2）。抬 b 直接抬方波平均 (1+b)/2；
     * b≥0.3 后 β=clamp01(b/0.3) 饱和、Ω 摩擦圆耦合关死——释放深度成为
     * 零副作用杠杆。游戏原生 UI 上限只能到 0.3（bias=70 单侧钳位），
     * 档位 LOW 越界到 0.8（贴极限工作区）是模块存在的意义之一。
     *
     * [bOverride] < 0 表示不覆写字段（"最高（默认）"/OFF——恢复捕获基线）；
     * ≥0 时每帧绝对值写（勿用现值×系数，防复利衰减，TC v1.2 教训）。
     * OFF 档的"关闭"语义经 enableAbs 派生布尔走既有 usesABS=false 通道，
     * 不占用 bOverride。
     */
    enum class AbsStrength(val value: String, val bOverride: Float) {
        OFF("off", -1f),
        LOW("weak", 0.80f),
        MEDIUM("medium", 0.60f),
        HIGH("strong", 0.40f),
        MAX("stock", -1f);

        companion object {
            fun from(value: String?): AbsStrength {
                return entries.find { it.value == value } ?: MAX
            }
        }
    }

    // 切线解缓存：曲线预览（每帧 ~41 次采样）与踏板求值（1-2 次/帧）都以
    // 相同点集连续调本函数，避免每次重解 QP。未命中最坏重解一次，解是
    // 确定性的，无正确性影响。
    private val tangentCache = object : LinkedHashMap<List<CurvePoint>, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<CurvePoint>, FloatArray>): Boolean = size > 8
    }

    /**
     * Monotone response curve through the control points plus the fixed
     * endpoints (0,0) and (1,1).
     *
     * 切线不再是 Fritsch–Carlson 调和平均启发式：在每点的 FC 单调可行盒
     * （切线 ∈ [3·min(邻 secant,0), 3·max(邻 secant,0)]，Hermite 无过冲）
     * 内取整条曲线弯曲能量 Σ∫(y'')² 最小的解——盒约束凸 QP，坐标下降
     * 收敛到唯一全局最优。相比旧版：过点与 C1 行为不变、共线控制点精确
     * 保持直线、单点弯曲意图的形状误差降约 40%（2026-08-28 数值验证：
     * 单调/无过冲/直线保持/非单调点序/空点集全通过）。
     *
     * 控制点 y 允许局部非单调（编辑器允许任意拖 y）：此时盒退化为带符号
     * 区间（负 secant 段允许负切线），仍无过冲。
     *
     * @param points control points (may be empty → linear)
     * @param x      input in [0,1]
     * @return       interpolated output in [0,1]
     */
    fun monotoneCubic(points: List<CurvePoint>, x: Float): Float {
        val sorted = points.sortedBy { it.x }
        if (sorted.isEmpty()) return x.coerceIn(0f, 1f)
        // 固定端点 (0,0) 和 (1,1)。
        val pts = listOf(CurvePoint(0f, 0f)) + sorted + listOf(CurvePoint(1f, 1f))
        val tangents = synchronized(tangentCache) { tangentCache[sorted] }
            ?: solveCurveTangents(pts).also { solution ->
                synchronized(tangentCache) { tangentCache[sorted] = solution }
            }
        return evalHermite(pts, tangents, x)
    }

    /**
     * 曲率能量最小化切线。初值取旧版 FC 调和平均切线——QP 未收敛的极端
     * 情形下行为自然退回旧版。
     */
    private fun solveCurveTangents(pts: List<CurvePoint>): FloatArray {
        val n = pts.size - 1
        val m = FloatArray(pts.size)
        if (n <= 0) return m
        // 段几何；x 重复（编辑器已防，防御外部构造）的退化段剔除出能量与约束。
        val h = FloatArray(n)
        val dy = FloatArray(n)
        val valid = BooleanArray(n)
        for (i in 0 until n) {
            h[i] = pts[i + 1].x - pts[i].x
            dy[i] = pts[i + 1].y - pts[i].y
            valid[i] = h[i] > 0f
        }
        if (!valid.any()) return m

        // FC 调和平均切线作初值。
        m[0] = if (valid[0]) dy[0] / h[0] else 0f
        m[n] = if (valid[n - 1]) dy[n - 1] / h[n - 1] else 0f
        for (i in 1 until n) {
            val l = i - 1
            val r = i
            m[i] = if (valid[l] && valid[r] && dy[l] * dy[r] > 0f) {
                2f / (h[l] / dy[l] + h[r] / dy[r])
            } else 0f
        }

        // FC 单调盒（带符号）：全增点序下即经典 [0, 3·min(邻 secant)]；
        // 局部下降段允许负切线，Hermite 仍无过冲。
        val lo = FloatArray(pts.size)
        val hi = FloatArray(pts.size)
        for (i in pts.indices) {
            var loI = 0f
            var hiI = 0f
            for (k in intArrayOf(i - 1, i)) {
                if (k in 0 until n && valid[k]) {
                    val s3 = 3f * dy[k] / h[k]
                    if (s3 < loI) loI = s3
                    if (s3 > hiI) hiI = s3
                }
            }
            lo[i] = loI
            hi[i] = hiI
        }

        // E = Σ_i [12Δ² − 12hΔ(mₗ+mᵣ) + 4h²(mₗ² + mᵣ² + mₗmᵣ)]：
        // 对每个 m_i 是凸二次 → 逐点一维解析最小化 + 盒 clamp，坐标下降。
        repeat(200) {
            var maxDelta = 0f
            for (i in pts.indices) {
                var a = 0f
                var b = 0f
                val l = i - 1
                val r = i
                if (l in 0 until n && valid[l]) {
                    a += 8f * h[l] * h[l]
                    b += 12f * h[l] * dy[l] - 4f * h[l] * h[l] * m[l]
                }
                if (r in 0 until n && valid[r]) {
                    a += 8f * h[r] * h[r]
                    b += 12f * h[r] * dy[r] - 4f * h[r] * h[r] * m[r + 1]
                }
                val clamped = (if (a > 0f) b / a else m[i]).coerceIn(lo[i], hi[i])
                val delta = kotlin.math.abs(clamped - m[i])
                if (delta > maxDelta) maxDelta = delta
                m[i] = clamped
            }
            if (maxDelta < 1e-6f) return m
        }
        return m
    }

    /** 三次 Hermite 段求值（切线为 xy 空间斜率）。 */
    private fun evalHermite(pts: List<CurvePoint>, m: FloatArray, x: Float): Float {
        val n = pts.size - 1
        val xc = x.coerceIn(0f, 1f)
        var i = 0
        while (i < n - 1 && xc > pts[i + 1].x) i++
        val h = pts[i + 1].x - pts[i].x
        if (h <= 0f) return pts[i + 1].y

        val t = (xc - pts[i].x) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * pts[i].y + h10 * h * m[i] + h01 * pts[i + 1].y + h11 * h * m[i + 1]
    }

    private object Defaults {
        const val ENABLE_AUTO_DRS = false
        const val DISABLE_AUTO_GEAR = false
        const val ENABLE_MANUAL_SHIFT = false
        const val ENABLE_UNLOCK = false
        // 原生 TC/ABS 默认开启。
        const val ENABLE_TC = true
        const val ENABLE_ABS = true
        // TC 档位默认：游戏默认（纯透传，等价于旧 enableTc=true 的行为）。
        val TC_MODE = TcMode.DEFAULT
        val TC_STRENGTH = TcStrength.MAX
        val TC_TIMING = TcTiming.LATE
        val ABS_MODE = AbsMode.DEFAULT
        val ABS_STRENGTH = AbsStrength.MAX
        // 100% = 不缩放 T_b。50-100% 无级（读取路径 coerceIn(0.5f, 1f) 钳位下限，
        // 0% 观察值已收敛掉，防止误存导致制动几乎消失）。字段写入生效已由 ABSdiag 实证（tb=3375=4500×0.75）。
        const val ABS_PRESSURE = 1.0f
        // 主菜单音乐替换默认开启
        const val ENABLE_MUSIC_REPLACE = true
        // V10 引擎声浪默认关闭
        const val ENABLE_V10_SOUND = false
        // 隐藏游戏原生油门和刹车按钮默认关闭
        const val HIDE_GAME_PEDALS = false
        val PEDAL_MODE = PedalMode.SINGLE
        const val PEDAL_DEADZONE = 0.05f
        const val PEDAL_TRANSITION = 0.5f
        // 双踏板模式下刹车仲裁的过渡点（BRAKE_VALUE 策略，用户配置 0.01..0.99）。
        // 刹车值 ≥ 此点 → 刹车优先屏蔽油门；< 此点且油门>0 → 油门优先屏蔽刹车。
        const val BRAKE_TRANSITION = 0.2f
        // 双踏板模式下油门仲裁的过渡点（THROTTLE_VALUE 策略，用户配置 0.01..0.99）。
        // 油门值 ≥ 此点 → 油门优先屏蔽刹车；< 此点且刹车>0 → 刹车优先屏蔽油门。
        const val THROTTLE_TRANSITION = 0.2f
        // 双踏板仲裁策略：默认 BRAKE_VALUE（保持旧行为不变）。
        val PEDAL_PRIORITY = PedalPriority.BRAKE_VALUE
        // 踏板方向反转：默认 OFF（手指顶部=满，填充从底往上）。
        val PEDAL_INVERT = PedalInvert.OFF
        // Overlay 视觉属性默认值：透明度 50%、边框 5dp、圆角比例 50%。
        const val OVERLAY_ALPHA = 0.5f
        const val OVERLAY_BORDER_WIDTH = 5.0f
        const val OVERLAY_CORNER_RADIUS = 0.5f
        val THROTTLE_CURVE = PedalCurve.LINEAR
        val BRAKE_CURVE = PedalCurve.LINEAR
        // 自定义曲线控制点列表默认空 = 线性（只有两端点）。
        val THROTTLE_CURVE_POINTS: List<CurvePoint> = emptyList()
        val BRAKE_CURVE_POINTS: List<CurvePoint> = emptyList()
        val PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        val GEAR_POSITION = OverlayPosition.DEFAULT_GEAR
        val BRAKE_POSITION = OverlayPosition.DEFAULT_BRAKE
        val SINGLE_PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        // 工具按钮默认位置：x=0.03 ≈ 8dp@360dp 屏，y=0.04 ≈ 40dp@1000dp 高。
        // width/height 字段对 ToolButtonView 无意义（控件固定 96dp），保留只为
        // 架构统一。运行时 applySavedPosition() 只用 leftPx()/topPx()，不读 width/height。
        val TOOL_BUTTON_POSITION = OverlayPosition(0.03f, 0.04f, 0.12f, 0.12f)
        const val LOG_ENABLED = false
    }

    /**
     * Returns the shared config file.
     *
     * Config 跨进程路径策略（Android 11+ scoped storage）：
     * - 模块进程（ConfigActivity/ConfigProvider）：用 context.filesDir（应用私有
     *   内部存储，天然可读写，无需权限，不受 scoped storage 影响）。这个文件
     *   游戏进程不能直接读，但游戏进程通过 ContentResolver 调 ConfigProvider
     *   间接读取——Binder 路由到模块进程执行，模块进程对自己 filesDir 有权。
     * - 游戏进程直接读文件走不通（scoped storage 隔离 Android/data/<pkg>，
     *   外部存储根也 EACCES），所以游戏进程必须走 ConfigProvider 的 call(READ)。
     *   ModConfig.readFromTargetProcess 会先试 ContentProvider，失败再回退文件。
     *
     * 旧的"按 packageName 分流 + 外部存储根"路径在 targetSdk 35 下两边都不可达，
     * 是 M10 配置不流动的真正根因。
     */
    private fun getConfigFile(context: Context): File {
        // 模块进程：filesDir 始终可达（应用私有内部存储）。
        if (context.packageName == MODULE_PACKAGE) {
            return File(context.filesDir, FILE_NAME)
        }
        // 游戏进程直接读文件：理论上读不到模块的 filesDir，但保留分支以防
        // ContentProvider 不可用时回退（实际 readFromTargetProcess 会优先走 Provider）。
        // 走游戏自己的 externalFilesDir——游戏进程对它天然可读，至少不崩。
        val baseDir = context.getExternalFilesDir(null)
            ?: return File(Environment.getExternalStorageDirectory(), "AlaMobileTool/$FILE_NAME")
        return File(baseDir, FILE_NAME)
    }

    private fun getSharedConfigDir(context: Context): File {
        val file = getConfigFile(context)
        return file.parentFile ?: File(Environment.getExternalStorageDirectory(), "AlaMobileTool")
    }

    /**
     * Reads the module settings from the shared JSON file.
     * This works in both the module process and the target game process.
     */
    fun read(context: Context): Settings {
        return try {
            val file = getConfigFile(context)
            if (!file.exists()) {
                return defaultSettings()
            }

            val json = JSONObject(file.readText())
            val (tcMode, tcStrength, tcTiming) = migrateTc(json)
            val (absMode, absStrength, absPressure) = migrateAbs(json)
            Settings(
                pedalMode = migratePedalMode(json),
                // 自动 DRS 功能未实现，强制读成 false，忽略任何旧配置里的 true，
                // 避免老用户升级后开关显示"开"但实际无效果。
                enableAutoDrs = false,
                disableAutoGear = json.optBoolean(
                    KEY_DISABLE_AUTO_GEAR,
                    Defaults.DISABLE_AUTO_GEAR
                ),
                enableManualShift = json.optBoolean(
                    KEY_ENABLE_MANUAL_SHIFT,
                    Defaults.ENABLE_MANUAL_SHIFT
                ),
                enableUnlock = json.optBoolean(
                    KEY_ENABLE_UNLOCK,
                    Defaults.ENABLE_UNLOCK
                ),
                enableTc = tcMode == TcMode.DEFAULT || tcStrength != TcStrength.OFF,
                enableAbs = absMode == AbsMode.DEFAULT || absStrength != AbsStrength.OFF,
                tcMode = tcMode,
                tcStrength = tcStrength,
                tcTiming = tcTiming,
                absMode = absMode,
                absStrength = absStrength,
                absPressure = absPressure,
                enableMusicReplace = json.optBoolean(
                    KEY_ENABLE_MUSIC_REPLACE,
                    Defaults.ENABLE_MUSIC_REPLACE
                ),
                enableV10Sound = json.optBoolean(
                    KEY_ENABLE_V10_SOUND,
                    Defaults.ENABLE_V10_SOUND
                ),
                hideGamePedals = json.optBoolean(
                    KEY_HIDE_GAME_PEDALS,
                    Defaults.HIDE_GAME_PEDALS
                ),
                pedalDeadzone = json.optDouble(
                    KEY_PEDAL_DEADZONE,
                    Defaults.PEDAL_DEADZONE.toDouble()
                ).toFloat(),
                pedalTransition = json.optDouble(
                    KEY_PEDAL_TRANSITION,
                    Defaults.PEDAL_TRANSITION.toDouble()
                ).toFloat(),
                brakeTransition = json.optDouble(
                    KEY_BRAKE_TRANSITION,
                    Defaults.BRAKE_TRANSITION.toDouble()
                ).toFloat(),
                throttleTransition = json.optDouble(
                    KEY_THROTTLE_TRANSITION,
                    Defaults.THROTTLE_TRANSITION.toDouble()
                ).toFloat(),
                pedalPriority = PedalPriority.from(
                    json.optString(KEY_PEDAL_PRIORITY, Defaults.PEDAL_PRIORITY.value)
                ),
                pedalInvert = migratePedalInvert(json),
                overlayAlpha = json.optDouble(KEY_OVERLAY_ALPHA, Defaults.OVERLAY_ALPHA.toDouble()).toFloat(),
                overlayBorderWidth = json.optDouble(KEY_OVERLAY_BORDER_WIDTH, Defaults.OVERLAY_BORDER_WIDTH.toDouble()).toFloat(),
                overlayCornerRadius = json.optDouble(KEY_OVERLAY_CORNER_RADIUS, Defaults.OVERLAY_CORNER_RADIUS.toDouble()).toFloat(),
                throttleCurve = PedalCurve.from(
                    json.optString(KEY_THROTTLE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.THROTTLE_CURVE.value))
                ),
                brakeCurve = PedalCurve.from(
                    json.optString(KEY_BRAKE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.BRAKE_CURVE.value))
                ),
                throttleCurvePoints = readCurvePoints(json, KEY_THROTTLE_CURVE_POINTS),
                brakeCurvePoints = readCurvePoints(json, KEY_BRAKE_CURVE_POINTS),
                pedalPosition = readOverlayPosition(json, KEY_PEDAL_POSITION, Defaults.PEDAL_POSITION),
                gearPosition = readOverlayPosition(json, KEY_GEAR_POSITION, Defaults.GEAR_POSITION),
                brakePosition = readOverlayPosition(json, KEY_BRAKE_POSITION, Defaults.BRAKE_POSITION),
                singlePedalPosition = readOverlayPosition(json, KEY_SINGLE_PEDAL_POSITION, Defaults.SINGLE_PEDAL_POSITION),
                // 工具按钮位置：当前不持久化（不写 write），但读路径保留——读出
                // JSON 里可能含这个 key（未来若开持久化），无 key 时落 Defaults。
                toolButtonPosition = readOverlayPosition(json, KEY_TOOL_POSITION, Defaults.TOOL_BUTTON_POSITION),
                logEnabled = json.optBoolean(KEY_LOG_ENABLED, Defaults.LOG_ENABLED)
            )
        } catch (e: Throwable) {
            defaultSettings()
        }
    }

    /**
     * Writes the module settings to the module's filesDir (persistence backup)
     * AND broadcasts the JSON to the target game processes via ConfigReceiver.
     *
     * Android 11+ 包可见性 + scoped storage 让文件直读跨进程不可行：
     * - 模块 filesDir：模块进程可写，但游戏进程读不到（包不可见 + scoped）。
     * - 游戏 externalFilesDir：游戏进程可写，但模块进程写不到（uid 隔离）。
     *
     * 所以模块进程写完备份后，发定向广播给游戏包；游戏进程 ConfigReceiver
     * 收到后用自己 context 写自己 externalFilesDir（天然可写）。OverlayManager
     * 读同一路径生效。定向广播 setPackage() 不查 PackageManager 可见性，
     * 绕过 Android 11+ 的包可见性限制。
     */
    fun write(context: Context, settings: Settings) {
        val json = JSONObject().apply {
            put(KEY_PEDAL_MODE, settings.pedalMode.value)
            put(KEY_ENABLE_AUTO_DRS, settings.enableAutoDrs)
            put(KEY_DISABLE_AUTO_GEAR, settings.disableAutoGear)
            put(KEY_ENABLE_MANUAL_SHIFT, settings.enableManualShift)
            put(KEY_ENABLE_UNLOCK, settings.enableUnlock)
            put(KEY_ENABLE_TC, settings.enableTc)
            put(KEY_ENABLE_ABS, settings.enableAbs)
            // TC 档位：字符串枚举值持久化。enableTc 上行照写（派生值），
            // 供旧版本 APK 回滚时读取。
            put(KEY_TC_MODE, settings.tcMode.value)
            put(KEY_TC_STRENGTH, settings.tcStrength.value)
            put(KEY_TC_TIMING, settings.tcTiming.value)
            // ABS 档位：同 TC 模式。enableAbs 上行照写（派生值）。
            put(KEY_ABS_MODE, settings.absMode.value)
            put(KEY_ABS_STRENGTH, settings.absStrength.value)
            put(KEY_ABS_PRESSURE, settings.absPressure.toDouble())
            put(KEY_ENABLE_MUSIC_REPLACE, settings.enableMusicReplace)
            put(KEY_ENABLE_V10_SOUND, settings.enableV10Sound)
            put(KEY_HIDE_GAME_PEDALS, settings.hideGamePedals)
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_BRAKE_TRANSITION, settings.brakeTransition.toDouble())
            put(KEY_THROTTLE_TRANSITION, settings.throttleTransition.toDouble())
            put(KEY_PEDAL_PRIORITY, settings.pedalPriority.value)
            put(KEY_PEDAL_INVERT, settings.pedalInvert.value)
            put(KEY_OVERLAY_ALPHA, settings.overlayAlpha.toDouble())
            put(KEY_OVERLAY_BORDER_WIDTH, settings.overlayBorderWidth.toDouble())
            put(KEY_OVERLAY_CORNER_RADIUS, settings.overlayCornerRadius.toDouble())
            put(KEY_THROTTLE_CURVE, settings.throttleCurve.value)
            put(KEY_BRAKE_CURVE, settings.brakeCurve.value)
            put(KEY_THROTTLE_CURVE_POINTS, writeCurvePoints(settings.throttleCurvePoints))
            put(KEY_BRAKE_CURVE_POINTS, writeCurvePoints(settings.brakeCurvePoints))
            // 不写 position 三字段：position 由游戏进程持有（拖拽时
            // saveOverlayPosition 写游戏 externalFilesDir），ConfigActivity
            // 不管 position。广播 JSON 不含 position，ConfigReceiver 收到
            // 后合并——保留游戏进程已有的 position，只更新这里的非 position 字段。
            put(KEY_LOG_ENABLED, settings.logEnabled)
        }.toString(2)

        // 1. 优先走 Remote Preferences（LSPosed daemon SQLite，无视进程存活）。
        //    ConfigActivity（模块进程）经 App.xposedService Binder 到 daemon 写，
        //    游戏进程经 XposedModule.getRemotePreferences 读到。daemon 常驻，
        //    不依赖游戏进程是否在运行——根治"游戏没运行→广播丢失→下次启动读旧值"。
        //    service 异步绑定，可能此时仍为 null（首次进 ConfigActivity 太快），
        //    失败回退到 filesDir + 广播兜底。
        val service = tools.alamobile.mod.App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(tools.alamobile.mod.App.PREF_GROUP)
                    .edit()
                    .putString(tools.alamobile.mod.App.KEY_CONFIG_JSON, json)
                    .apply()
                android.util.Log.i("AlaMobileTool", "Config written via remote preferences")
            } catch (e: Throwable) {
                android.util.Log.w("AlaMobileTool", "Remote preferences write failed, falling back", e)
            }
        } else {
            android.util.Log.w("AlaMobileTool", "XposedService not bound yet, using local fallback")
        }

        // 2. 写模块 filesDir 作持久化备份（模块进程天然可写，service 不可用时兜底）。
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json)
            android.util.Log.i("AlaMobileTool", "Config written to module filesDir: ${file.absolutePath}")
        } catch (e: Throwable) {
            android.util.Log.e("AlaMobileTool", "ModConfig.write to filesDir failed", e)
        }

        // 3. 发定向广播给所有目标游戏包，让游戏进程 ConfigReceiver 写自己目录
        //    （externalFilesDir）。Remote Preferences 路线下，广播的价值是"游戏运行时
        //    即时更新"——service 异步绑定可能延迟，广播立即推送让 overlay 马上重建。
        //    setPackage 定向派发，不查 PackageManager 可见性，绕过 Android 11+ 包可见性限制。
        //    游戏没运行时广播丢失不再造成问题：下次启动 readFromTargetProcess 走
        //    Remote Preferences 读 daemon 的最新权威值。
        for (pkg in GAME_PACKAGES) {
            try {
                val intent = Intent(ConfigReceiver.ACTION_CONFIG_UPDATE)
                    .setPackage(pkg)
                    .putExtra(ConfigReceiver.EXTRA_JSON, json)
                context.sendBroadcast(intent)
                android.util.Log.i("AlaMobileTool", "Config broadcast sent to $pkg")
            } catch (e: Throwable) {
                android.util.Log.w("AlaMobileTool", "Config broadcast to $pkg failed", e)
            }
        }
    }

    /**
     * Saves a single overlay position into the existing config without
     * touching other keys. Safe to call from the target game process.
     *
     * If the shared directory cannot be created (e.g. missing storage
     * permission on Android 10+), the save is silently skipped so the
     * overlay editor does not crash the game.
     */
    fun saveOverlayPosition(context: Context, key: String, position: OverlayPosition) {
        try {
            val file = getConfigFile(context)
            file.parentFile?.mkdirs()
            val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            json.put(key, position.toJson())
            file.writeText(json.toString(2))
        } catch (_: Throwable) {
            // Storage may not be writable from the target game process; ignore.
        }
    }

    /**
     * Reads the module settings from the target game process.
     *
     * 读取优先级（M14-B 配置同步迁移）：
     * 1. **Remote Preferences**（libxposed API 102）：经 Binder 到 LSPosed daemon
     *    读 daemon SQLite 里的权威配置 JSON（ConfigActivity 经 App.xposedService 写入）。
     *    不依赖游戏进程是否在运行——daemon 常驻，根治 M11 首次滞后 bug
     *    （游戏没运行时广播丢失→下次启动读旧值）。
     * 2. **本地 externalFilesDir 回退**：Remote Preferences 不可用或失败时，读游戏进程
     *    自己 externalFilesDir 的 JSON（ConfigReceiver 收广播后写）。游戏运行时
     *    广播方案仍可靠，作为兜底。
     * 3. **默认值**：两者都没有时用 defaultSettings。
     *
     * position 字段（拖拽时 saveOverlayPosition 写）始终从本地 externalFilesDir 合并——
     * 模块 filesDir 的 JSON 不含 position（ConfigActivity 不管 position）。
     */
    fun readFromTargetProcess(context: Context): Settings {
        // 优先走 Remote Preferences（libxposed API 102）：经 Binder 到 LSPosed daemon
        // 读 daemon SQLite 里的模块配置 JSON。不依赖游戏进程是否在运行——daemon 常驻，
        // ConfigActivity 经 App.xposedService 写入 daemon，根治"游戏没运行→广播丢失
        // →下次启动读旧值"的 M11 首次滞后 bug。position 字段不在 daemon 的 JSON 里
        // （由游戏进程拖拽时写本地 externalFilesDir），下面单独从本地合并。
        val remoteJson = try {
            val reader = remoteConfigReader
            if (reader != null) {
                val content = reader()
                android.util.Log.i(
                    TAG,
                    "readFromTargetProcess: remote prefs ${if (content != null) "ok len=${content.length}" else "null (key not in daemon db)"}"
                )
                content
            } else null
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "readFromTargetProcess: remote prefs failed, falling back to local", e)
            null
        }

        // 如 Remote Preferences 已有数据，直接走 remote 路径（LSPosed daemon 或 NPatch
        // 管理器 ContentProvider 写入的），这是最权威的路径。
        if (remoteJson != null) {
            val dir = context.getExternalFilesDir(null)
            val localJson = if (dir != null) {
                val file = File(dir, FILE_NAME)
                if (file.exists()) {
                    try { file.readText() } catch (e: Throwable) { null }
                } else null
            } else null
            val merged = mergePositionFromLocalPublic(remoteJson, localJson)
            val settings = fromJson(merged)
            android.util.Log.i(
                TAG,
                "Config via remote prefs (merged local position): pedalMode=${settings.pedalMode} " +
                    "remotePreview=${remoteJson.take(80).replace('\n', ' ')}"
            )
            return settings
        }

        // Remote Preferences 不可用时（NPatch local 模式无 daemon），尝试从模块进程的
        // ConfigProvider ContentProvider 读取模块 filesDir 的最新配置。
        // NPatch local 模式下，游戏进程的 PackageManager 能看到模块包（NPatch loader
        // 绕过了 Android 11+ 包可见性限制），ConfigProvider 已 exported=true，可访问。
        // LSPosed 模式下此调用会抛 IllegalArgumentException（Unknown authority）或
        // SecurityException（包不可见），静默回退到本地文件路径。
        val moduleConfigJson = try {
            val uri = android.net.Uri.parse("content://tools.alamobile.mod.config")
            val result = context.contentResolver.call(uri, "read_config", null, null)
            val json = result?.getString("json")
            if (json != null && json.isNotEmpty()) {
                android.util.Log.i(TAG, "readFromTargetProcess: ConfigProvider ok, len=${json.length}")
                json
            } else {
                android.util.Log.w(TAG, "readFromTargetProcess: ConfigProvider returned empty/null")
                null
            }
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "readFromTargetProcess: ConfigProvider not reachable (expected in LSPosed mode): ${e.message?.take(80)}")
            null
        }

        // 有模块 ConfigProvider 数据：用模块配置（非 position 字段）+ 本地 position 合并。
        // 这修复了 NPatch local 模式下"游戏不运行时改配置→启动游戏读旧值"的问题。
        if (moduleConfigJson != null) {
            // 本地 externalFilesDir JSON：收 position 字段（拖拽时 saveOverlayPosition 写）。
            val dir = context.getExternalFilesDir(null)
            val localJson = if (dir != null) {
                val file = File(dir, FILE_NAME)
                if (file.exists()) {
                    try { file.readText() } catch (e: Throwable) { null }
                } else null
            } else null
            val merged = mergePositionFromLocalPublic(moduleConfigJson, localJson)
            val settings = fromJson(merged)
            android.util.Log.i(
                TAG,
                "Config via module ConfigProvider (merged local position): pedalMode=${settings.pedalMode} "
            )
            return settings
        }

        // 本地 externalFilesDir JSON：ConfigReceiver 收广播后写，且拖拽 position
        // 存这里。游戏运行时广播方案仍可靠，作为兜底。
        // 无论 Remote Preferences 成功与否，position 都必须从本地读——daemon
        // 的 JSON 不含 position（ConfigActivity 不管 position）。
        val dir = context.getExternalFilesDir(null) ?: run {
            android.util.Log.w(TAG, "readFromTargetProcess: externalFilesDir null, using defaults")
            return defaultSettings()
        }
        val file = File(dir, FILE_NAME)
        android.util.Log.i(
            TAG,
            "readFromTargetProcess: local path=${file.absolutePath} exists=${file.exists()} " +
                "lastModified=${if (file.exists()) java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(file.lastModified())) else "n/a"} " +
                "now=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
        )

        val localJson = if (file.exists()) {
            try { file.readText() } catch (e: Throwable) {
                android.util.Log.w(TAG, "readFromTargetProcess: local read failed", e)
                null
            }
        } else null

        if (localJson != null) {
            val settings = fromJson(localJson)
            android.util.Log.i(
                TAG,
                "Config via local fallback (remote+provider unavailable): pedalMode=${settings.pedalMode} " +
                    "localPreview=${localJson.take(80).replace('\n', ' ')}"
            )
            return settings
        }

        android.util.Log.i(TAG, "No config (remote+provider+local all empty), using defaults")
        return defaultSettings()
    }

    /**
     * 把 remote JSON（不含 position）与 local JSON（含 position）合并：
     * 以 remote 为基底，对每个 POSITION_KEY 用 local 里同 key 的值覆盖。
     * local 缺该 key 时保留 remote 的（remote 里通常是默认值）。local 为 null
     * 时直接返回 remote，position 走 fromJson 的默认。
     */
    fun mergePositionFromLocalPublic(remoteJson: String, localJson: String?): String {
        if (localJson == null) return remoteJson
        return try {
            val remote = JSONObject(remoteJson)
            val local = JSONObject(localJson)
            for (key in POSITION_KEYS) {
                if (local.has(key)) {
                    remote.put(key, local[key])
                }
            }
            remote.toString()
        } catch (e: Throwable) {
            remoteJson
        }
    }

    /** 把 Settings 的 position 字段重置为默认（本地目录拿不到 position 时用）。 */
    @Suppress("unused")
    private fun withPositionDefaults(s: Settings): Settings = s.copy(
        pedalPosition = Defaults.PEDAL_POSITION,
        gearPosition = Defaults.GEAR_POSITION,
        brakePosition = Defaults.BRAKE_POSITION,
        singlePedalPosition = Defaults.SINGLE_PEDAL_POSITION
    )

    /** 从 JSON 字符串解析 Settings，供 ConfigProvider 和 readFromTargetProcess 复用。 */
    fun fromJson(json: String): Settings {
        return try {
            val j = JSONObject(json)
            val (tcMode, tcStrength, tcTiming) = migrateTc(j)
            val (absMode, absStrength, absPressure) = migrateAbs(j)
            Settings(
                pedalMode = migratePedalMode(j),
                enableAutoDrs = false,
                disableAutoGear = j.optBoolean(KEY_DISABLE_AUTO_GEAR, Defaults.DISABLE_AUTO_GEAR),
                enableManualShift = j.optBoolean(KEY_ENABLE_MANUAL_SHIFT, Defaults.ENABLE_MANUAL_SHIFT),
                enableUnlock = j.optBoolean(KEY_ENABLE_UNLOCK, Defaults.ENABLE_UNLOCK),
                enableTc = tcMode == TcMode.DEFAULT || tcStrength != TcStrength.OFF,
                enableAbs = absMode == AbsMode.DEFAULT || absStrength != AbsStrength.OFF,
                tcMode = tcMode,
                tcStrength = tcStrength,
                tcTiming = tcTiming,
                absMode = absMode,
                absStrength = absStrength,
                absPressure = absPressure,
                enableMusicReplace = j.optBoolean(KEY_ENABLE_MUSIC_REPLACE, Defaults.ENABLE_MUSIC_REPLACE),
                enableV10Sound = j.optBoolean(KEY_ENABLE_V10_SOUND, Defaults.ENABLE_V10_SOUND),
                hideGamePedals = j.optBoolean(KEY_HIDE_GAME_PEDALS, Defaults.HIDE_GAME_PEDALS),
                pedalDeadzone = j.optDouble(KEY_PEDAL_DEADZONE, Defaults.PEDAL_DEADZONE.toDouble()).toFloat(),
                pedalTransition = j.optDouble(KEY_PEDAL_TRANSITION, Defaults.PEDAL_TRANSITION.toDouble()).toFloat(),
                brakeTransition = j.optDouble(KEY_BRAKE_TRANSITION, Defaults.BRAKE_TRANSITION.toDouble()).toFloat(),
                throttleTransition = j.optDouble(KEY_THROTTLE_TRANSITION, Defaults.THROTTLE_TRANSITION.toDouble()).toFloat(),
                pedalPriority = PedalPriority.from(j.optString(KEY_PEDAL_PRIORITY, Defaults.PEDAL_PRIORITY.value)),
                pedalInvert = migratePedalInvert(j),
                overlayAlpha = j.optDouble(KEY_OVERLAY_ALPHA, Defaults.OVERLAY_ALPHA.toDouble()).toFloat(),
                overlayBorderWidth = j.optDouble(KEY_OVERLAY_BORDER_WIDTH, Defaults.OVERLAY_BORDER_WIDTH.toDouble()).toFloat(),
                overlayCornerRadius = j.optDouble(KEY_OVERLAY_CORNER_RADIUS, Defaults.OVERLAY_CORNER_RADIUS.toDouble()).toFloat(),
                throttleCurve = PedalCurve.from(
                    j.optString(KEY_THROTTLE_CURVE, j.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.THROTTLE_CURVE.value))
                ),
                brakeCurve = PedalCurve.from(
                    j.optString(KEY_BRAKE_CURVE, j.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.BRAKE_CURVE.value))
                ),
                throttleCurvePoints = readCurvePoints(j, KEY_THROTTLE_CURVE_POINTS),
                brakeCurvePoints = readCurvePoints(j, KEY_BRAKE_CURVE_POINTS),
                pedalPosition = readOverlayPosition(j, KEY_PEDAL_POSITION, Defaults.PEDAL_POSITION),
                gearPosition = readOverlayPosition(j, KEY_GEAR_POSITION, Defaults.GEAR_POSITION),
                brakePosition = readOverlayPosition(j, KEY_BRAKE_POSITION, Defaults.BRAKE_POSITION),
                singlePedalPosition = readOverlayPosition(j, KEY_SINGLE_PEDAL_POSITION, Defaults.SINGLE_PEDAL_POSITION),
                toolButtonPosition = readOverlayPosition(j, KEY_TOOL_POSITION, Defaults.TOOL_BUTTON_POSITION),
                logEnabled = j.optBoolean(KEY_LOG_ENABLED, Defaults.LOG_ENABLED)
            )
        } catch (e: Throwable) {
            defaultSettings()
        }
    }

    /**
     * One-way migration: if the new `pedal_mode` key is present, use it.
     * Otherwise derive from the legacy `enable_control_replacement` bool:
     *   true  -> SINGLE (the legacy single-view default)
     *   false -> OFF
     */
    private fun migratePedalMode(json: JSONObject): PedalMode {
        val explicit = json.optString(KEY_PEDAL_MODE, "")
        if (explicit.isNotEmpty()) return PedalMode.from(explicit)
        return if (json.optBoolean(KEY_LEGACY_ENABLE_CONTROL_REPLACEMENT, true)) PedalMode.SINGLE
        else PedalMode.OFF
    }

    /**
     * One-way migration: if the new `pedal_invert` key is present, use it.
     * Otherwise derive from the legacy `brake_invert` boolean:
     *   true  -> BRAKE (仅刹车踏板)
     *   false -> OFF (关闭)
     */
    private fun migratePedalInvert(json: JSONObject): PedalInvert {
        val explicit = json.optString(KEY_PEDAL_INVERT, "")
        if (explicit.isNotEmpty()) return PedalInvert.from(explicit)
        return if (json.optBoolean(KEY_LEGACY_BRAKE_INVERT, false)) PedalInvert.BRAKE else PedalInvert.OFF
    }

    /**
     * TC 档位生效值派生：模式说了算。DEFAULT 恒为原厂透传（mix=1 / eps=minspd=0
     * 不覆写），与缓存的 strength/timing 无关——否则"调回游戏默认"无法恢复原生
     * 行为（strength/timing 是记忆值，mode 才是生效开关）。CUSTOM 时按所选档
     * 生效，返回 (mix, eps, minspd) 三元组——eps/minspd 必须成对覆写（v1.4，
     * 见 [TcTiming] 注释）。
     */
    fun tcEffectiveParams(
        mode: TcMode,
        strength: TcStrength,
        timing: TcTiming
    ): Triple<Float, Float, Float> {
        return if (mode == TcMode.DEFAULT) Triple(1f, 0f, 0f)
        else Triple(strength.mix, timing.eps, timing.minspd)
    }

    /**
     * ABS 档位生效值派生：模式说了算（与 [tcEffectiveParams] 同构）。
     * DEFAULT 恒为原厂透传（bOverride=-1 不覆写，与缓存 strength 无关——
     * 否则"调回游戏默认"无法恢复原生行为）；CUSTOM 时按所选档生效。
     * 返回 (mix, bOverride, brakeScale) 三元组：
     * - mix：b 覆写总闸（CUSTOM+OFF → 0；native 端 mix≤0 忽略 b 覆写，
     *   "关闭 ABS"语义经 enableAbs 派生布尔走既有 usesABS=false 通道）
     * - bOverride：pulse 释放深度绝对值（<0 = 不覆写，恢复捕获基线）
     * - brakeScale：刹车输入请求等比缩放（v5，1.0 = 原生；与 ABS 模式/
     *   档位完全无关，任意状态下全局生效——tempBrakeF/F_base 内部曲线
     *   不触碰，输出压力全程 ×brakeScale）
     */
    fun absEffectiveParams(
        mode: AbsMode,
        strength: AbsStrength,
        pressure: Float
    ): Triple<Float, Float, Float> {
        return if (mode == AbsMode.DEFAULT) Triple(1f, -1f, pressure)
        else Triple(if (strength == AbsStrength.OFF) 0f else 1f, strength.bOverride, pressure)
    }

    /**
     * TC 档位读取 + 一代迁移（与 brake_invert → pedal_invert 的单向迁移模式一致）：
     * 新键 `tc_mode` 存在时直接用三键；否则从旧 `enable_tc` 布尔派生——
     * true（默认）→ 游戏默认；false → 自定义+关闭（旧"TC 开关关闭"语义）。
     * `enable_tc` 本身不再直接读取（由 [TcMode]/[TcStrength] 派生），
     * write 时照写派生值供旧版本回滚兼容。
     */
    private fun migrateTc(json: JSONObject): Triple<TcMode, TcStrength, TcTiming> {
        val explicitMode = json.optString(KEY_TC_MODE, "")
        if (explicitMode.isNotEmpty()) {
            return Triple(
                TcMode.from(explicitMode),
                TcStrength.from(json.optString(KEY_TC_STRENGTH, Defaults.TC_STRENGTH.value)),
                TcTiming.from(json.optString(KEY_TC_TIMING, Defaults.TC_TIMING.value))
            )
        }
        return if (json.optBoolean(KEY_ENABLE_TC, Defaults.ENABLE_TC)) {
            Triple(TcMode.DEFAULT, Defaults.TC_STRENGTH, Defaults.TC_TIMING)
        } else {
            Triple(TcMode.CUSTOM, TcStrength.OFF, Defaults.TC_TIMING)
        }
    }

    /**
     * ABS 档位读取 + 一代迁移（与 [migrateTc] 同构）：
     * 新键 `abs_mode` 存在时直接用三键；否则从旧 `enable_abs` 布尔派生——
     * true（默认）→ 游戏默认；false → 自定义+关闭 ABS（旧"ABS 开关关闭"
     * 语义原样保留，红线：老用户"ABS 关闭"不得悄悄变"游戏默认"）。
     * `enable_abs` 本身不再直接读取（由 [AbsMode]/[AbsStrength] 派生），
     * write 时照写派生值供旧版本回滚兼容。
     * 制动压力独立读取（无 legacy 键，无旧配置时落 1.0 = 不缩放），
     * clamp [0.5, 1.0] 防御异常值（旧配置存过更低的值会被抬到 0.5）。
     */
    private fun migrateAbs(json: JSONObject): Triple<AbsMode, AbsStrength, Float> {
        val pressure = json.optDouble(KEY_ABS_PRESSURE, Defaults.ABS_PRESSURE.toDouble())
            .toFloat()
            .coerceIn(0.5f, 1f)
        val explicitMode = json.optString(KEY_ABS_MODE, "")
        if (explicitMode.isNotEmpty()) {
            return Triple(
                AbsMode.from(explicitMode),
                AbsStrength.from(json.optString(KEY_ABS_STRENGTH, Defaults.ABS_STRENGTH.value)),
                pressure
            )
        }
        return if (json.optBoolean(KEY_ENABLE_ABS, Defaults.ENABLE_ABS)) {
            Triple(AbsMode.DEFAULT, Defaults.ABS_STRENGTH, pressure)
        } else {
            Triple(AbsMode.CUSTOM, AbsStrength.OFF, pressure)
        }
    }

    private fun readOverlayPosition(
        json: JSONObject,
        key: String,
        default: OverlayPosition
    ): OverlayPosition {
        val obj = json.optJSONObject(key) ?: return default
        return try {
            OverlayPosition(
                x = obj.optDouble("x", default.x.toDouble()).toFloat(),
                y = obj.optDouble("y", default.y.toDouble()).toFloat(),
                width = obj.optDouble("width", default.width.toDouble()).toFloat(),
                height = obj.optDouble("height", default.height.toDouble()).toFloat()
            )
        } catch (_: Throwable) {
            default
        }
    }

    /** 从 JSON 读曲线控制点列表。格式：[{x,y},{x,y},...]。解析失败/空返回空列表。 */
    private fun readCurvePoints(json: JSONObject, key: String): List<CurvePoint> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        return try {
            val list = ArrayList<CurvePoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val x = o.optDouble("x", -1.0).toFloat()
                val y = o.optDouble("y", -1.0).toFloat()
                if (x < 0f || x > 1f || y < 0f || y > 1f) continue
                list.add(CurvePoint(x, y))
            }
            list.sortedBy { it.x }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 把曲线控制点列表序列化为 JSON 数组。 */
    private fun writeCurvePoints(points: List<CurvePoint>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        points.sortedBy { it.x }.forEach { p ->
            arr.put(JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
            })
        }
        return arr
    }

    private fun OverlayPosition.toJson(): JSONObject {
        return JSONObject().apply {
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("width", width.toDouble())
            put("height", height.toDouble())
        }
    }

    private fun defaultSettings(): Settings {
        return defaultSettingsPublic()
    }

    /** 供 ViewModel 在 IO 线程加载配置前提供默认值，避免主线程阻塞。 */
    fun defaultSettingsPublic(): Settings {
        return Settings(
            pedalMode = Defaults.PEDAL_MODE,
            enableAutoDrs = Defaults.ENABLE_AUTO_DRS,
            disableAutoGear = Defaults.DISABLE_AUTO_GEAR,
            enableManualShift = Defaults.ENABLE_MANUAL_SHIFT,
            enableUnlock = Defaults.ENABLE_UNLOCK,
            enableTc = Defaults.ENABLE_TC,
            enableAbs = Defaults.ENABLE_ABS,
            tcMode = Defaults.TC_MODE,
            tcStrength = Defaults.TC_STRENGTH,
            tcTiming = Defaults.TC_TIMING,
            absMode = Defaults.ABS_MODE,
            absStrength = Defaults.ABS_STRENGTH,
            absPressure = Defaults.ABS_PRESSURE,
            enableMusicReplace = Defaults.ENABLE_MUSIC_REPLACE,
            enableV10Sound = Defaults.ENABLE_V10_SOUND,
            hideGamePedals = Defaults.HIDE_GAME_PEDALS,
            pedalDeadzone = Defaults.PEDAL_DEADZONE,
            pedalTransition = Defaults.PEDAL_TRANSITION,
            brakeTransition = Defaults.BRAKE_TRANSITION,
            throttleTransition = Defaults.THROTTLE_TRANSITION,
            pedalPriority = Defaults.PEDAL_PRIORITY,
            pedalInvert = Defaults.PEDAL_INVERT,
            overlayAlpha = Defaults.OVERLAY_ALPHA,
            overlayBorderWidth = Defaults.OVERLAY_BORDER_WIDTH,
            overlayCornerRadius = Defaults.OVERLAY_CORNER_RADIUS,
            throttleCurve = Defaults.THROTTLE_CURVE,
            brakeCurve = Defaults.BRAKE_CURVE,
            throttleCurvePoints = Defaults.THROTTLE_CURVE_POINTS,
            brakeCurvePoints = Defaults.BRAKE_CURVE_POINTS,
            pedalPosition = Defaults.PEDAL_POSITION,
            gearPosition = Defaults.GEAR_POSITION,
            brakePosition = Defaults.BRAKE_POSITION,
            singlePedalPosition = Defaults.SINGLE_PEDAL_POSITION,
            toolButtonPosition = Defaults.TOOL_BUTTON_POSITION,
            logEnabled = Defaults.LOG_ENABLED
        )
    }

    data class Settings(
        val pedalMode: PedalMode,
        val enableAutoDrs: Boolean,
        val disableAutoGear: Boolean,
        val enableManualShift: Boolean,
        val enableUnlock: Boolean,
        val enableTc: Boolean = Defaults.ENABLE_TC,
        val enableAbs: Boolean = Defaults.ENABLE_ABS,
        // TC 档位。enableTc 为派生值（DEFAULT 恒 true；CUSTOM 时 strength≠OFF），
        // 由 read()/ViewModel 维护一致性。三字段带默认值——PedalOverlayView 的
        // 命名参数部分构造无需改动。
        val tcMode: TcMode = Defaults.TC_MODE,
        val tcStrength: TcStrength = Defaults.TC_STRENGTH,
        val tcTiming: TcTiming = Defaults.TC_TIMING,
        // ABS 档位。enableAbs 为派生值（DEFAULT 恒 true；CUSTOM 时 strength≠OFF），
        // 三字段带默认值——PedalOverlayView 的命名参数部分构造无需改动。
        val absMode: AbsMode = Defaults.ABS_MODE,
        val absStrength: AbsStrength = Defaults.ABS_STRENGTH,
        // 制动压力（v6：踏板行程重映射标尺，1.0 = 原生），与 absMode/absStrength
        // 完全无关；native 端 0xF0 饱和重映射实现，见 pedal_hook.c。
        val absPressure: Float = Defaults.ABS_PRESSURE,
        val enableMusicReplace: Boolean = Defaults.ENABLE_MUSIC_REPLACE,
        val enableV10Sound: Boolean = Defaults.ENABLE_V10_SOUND,
        val hideGamePedals: Boolean = Defaults.HIDE_GAME_PEDALS,
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val brakeTransition: Float,
        val throttleTransition: Float = Defaults.THROTTLE_TRANSITION,
        val pedalPriority: PedalPriority = Defaults.PEDAL_PRIORITY,
        val pedalInvert: PedalInvert = Defaults.PEDAL_INVERT,
        // Overlay 视觉属性。cornerRadiusPx = ratio * min(width,height)/2：
        // 100% → 短边完全合拢为半圆。全部带默认值——PedalOverlayView 37 行的
        // 部分 Settings 构造（14 字段）无需改动即可编译。
        val overlayAlpha: Float = Defaults.OVERLAY_ALPHA,
        val overlayBorderWidth: Float = Defaults.OVERLAY_BORDER_WIDTH,
        val overlayCornerRadius: Float = Defaults.OVERLAY_CORNER_RADIUS,
        val throttleCurve: PedalCurve,
        val brakeCurve: PedalCurve,
        val throttleCurvePoints: List<CurvePoint> = Defaults.THROTTLE_CURVE_POINTS,
        val brakeCurvePoints: List<CurvePoint> = Defaults.BRAKE_CURVE_POINTS,
        val pedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val gearPosition: OverlayPosition = OverlayPosition.DEFAULT_GEAR,
        val brakePosition: OverlayPosition = OverlayPosition.DEFAULT_BRAKE,
        val singlePedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        // 工具按钮位置。已纳入 POSITION_KEYS 合并（游戏进程拖拽时
        // saveOverlayPosition 写本地 externalFilesDir，回读时经 mergePosition
        // 合并进 settings）。未拖过时本地无此 key，落回 Defaults——
        // 行为与旧版"每次打开游戏回到默认位置"一致。
        val toolButtonPosition: OverlayPosition = Defaults.TOOL_BUTTON_POSITION,
        val logEnabled: Boolean = Defaults.LOG_ENABLED
    )
}
