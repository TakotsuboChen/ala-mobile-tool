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
    const val KEY_SHOW_OVERLAY = "show_overlay"
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

    // 主菜单音乐替换开关：替换为 Hans Zimmer - F1
    const val KEY_ENABLE_MUSIC_REPLACE = "enable_music_replace"

    // Pedal mapping
    const val KEY_PEDAL_MODE = "pedal_mode"
    const val KEY_PEDAL_DEADZONE = "pedal_deadzone"
    const val KEY_PEDAL_TRANSITION = "pedal_transition"
    const val KEY_BRAKE_TRANSITION = "brake_transition"
    // 刹车踏板方向反转：DUAL 模式下红色填充从默认的"手指顶部满、往下退"
    // 改成"手指底部满、往上涨"，对应用户从下往上拉的触感偏好。
    const val KEY_BRAKE_INVERT = "brake_invert"
    const val KEY_THROTTLE_CURVE = "throttle_curve"
    const val KEY_BRAKE_CURVE = "brake_curve"

    // Legacy keys (kept only for one-way migration on read)
    const val KEY_LEGACY_ENABLE_CONTROL_REPLACEMENT = "enable_control_replacement"
    const val KEY_LEGACY_PEDAL_CURVE = "pedal_curve"

    // Overlay positions
    const val KEY_PEDAL_POSITION = "pedal_position"
    const val KEY_GEAR_POSITION = "gear_position"
    const val KEY_BRAKE_POSITION = "brake_position"
    // SINGLE 模式专用位置字段：与 DUAL 油门位置（pedal_position）分离，
    // 避免用户在 SINGLE 模式拖拽的 position 污染 DUAL 油门 view 的位置。
    const val KEY_SINGLE_PEDAL_POSITION = "single_pedal_position"
    // "工具" 按钮位置。架构上和 pedal/gear/brake 一致（OverlayPosition 比例存），
    // 但**当前不持久化**（不写 write、不读 read/fromJson、不在 POSITION_KEYS
    // 合并）——按需求"每次打开游戏重置到默认位置"。保留字段是为以后加"记忆
    // 位置"开关时零架构改动。
    const val KEY_TOOL_POSITION = "tool_button_position"

    // position 字段由游戏进程持有（拖拽时 saveOverlayPosition 写），
    // ConfigActivity 广播的 JSON 不含这些字段。合并写时（ConfigReceiver
    // 收到广播、readFromTargetProcess 从 provider 拉取）跳过这些 key，
    // 保留游戏进程已有的 position 值。
    val POSITION_KEYS = setOf(
        KEY_PEDAL_POSITION,
        KEY_GEAR_POSITION,
        KEY_BRAKE_POSITION,
        KEY_SINGLE_PEDAL_POSITION
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
     * The "exponential" curve here is an ease-out approximation tuned so that
     * ~30% physical travel yields ~60% in-game output (fast initial rise,
     * soft tail) — the realistic throttle/brake feel players expect. The
     * exponent is < 1, deliberately the opposite direction of the old
     * quadratic curve (which was "slow start, fast end" and felt dead).
     */
    enum class PedalCurve(val value: String) {
        LINEAR("linear"),
        EXPONENTIAL("exponential");

        companion object {
            fun from(value: String?): PedalCurve {
                // Legacy configs may carry "quadratic"; map it to exponential
                // so old users keep a non-linear feel instead of falling back
                // to linear silently.
                if (value == "quadratic") return EXPONENTIAL
                return entries.find { it.value == value } ?: LINEAR
            }
        }
    }

    private object Defaults {
        const val ENABLE_AUTO_DRS = false
        const val SHOW_OVERLAY = true
        const val DISABLE_AUTO_GEAR = false
        const val ENABLE_MANUAL_SHIFT = false
        const val ENABLE_UNLOCK = false
        // 原生 TC/ABS 默认开启。
        const val ENABLE_TC = true
        const val ENABLE_ABS = true
        // 主菜单音乐替换默认开启
        const val ENABLE_MUSIC_REPLACE = true
        val PEDAL_MODE = PedalMode.SINGLE
        const val PEDAL_DEADZONE = 0.05f
        const val PEDAL_TRANSITION = 0.5f
        // 双踏板模式下油门/刹车仲裁的过渡点（用户配置 0..0.2）。
        // 刹车值 ≥ 此点 → 刹车优先屏蔽油门；< 此点且油门>0 → 油门优先屏蔽刹车。
        const val BRAKE_TRANSITION = 0.1f
        // 刹车踏板方向反转：默认 false（红色从手指位置往下填到底部，
        // 手指顶部=满刹车）。true 时改成从手指位置往上填到顶部，
        // 手指底部=满刹车（对应用户"从下往上拉"的触感偏好）。
        const val BRAKE_INVERT = false
        val THROTTLE_CURVE = PedalCurve.LINEAR
        val BRAKE_CURVE = PedalCurve.LINEAR
        val PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        val GEAR_POSITION = OverlayPosition.DEFAULT_GEAR
        val BRAKE_POSITION = OverlayPosition.DEFAULT_BRAKE
        val SINGLE_PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        // 工具按钮默认位置：x=0.03 ≈ 8dp@360dp 屏，y=0.04 ≈ 40dp@1000dp 高。
        // width/height 字段对 ToolButtonView 无意义（控件固定 96dp），保留只为
        // 架构统一。运行时 resetToDefault() 只用 leftPx()/topPx()，不读 width/height。
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
            Settings(
                pedalMode = migratePedalMode(json),
                // 自动 DRS 功能未实现，强制读成 false，忽略任何旧配置里的 true，
                // 避免老用户升级后开关显示"开"但实际无效果。
                enableAutoDrs = false,
                showOverlay = json.optBoolean(
                    KEY_SHOW_OVERLAY,
                    Defaults.SHOW_OVERLAY
                ),
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
                enableTc = json.optBoolean(
                    KEY_ENABLE_TC,
                    Defaults.ENABLE_TC
                ),
                enableAbs = json.optBoolean(
                    KEY_ENABLE_ABS,
                    Defaults.ENABLE_ABS
                ),
                enableMusicReplace = json.optBoolean(
                    KEY_ENABLE_MUSIC_REPLACE,
                    Defaults.ENABLE_MUSIC_REPLACE
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
                brakeInvert = json.optBoolean(KEY_BRAKE_INVERT, Defaults.BRAKE_INVERT),
                throttleCurve = PedalCurve.from(
                    json.optString(KEY_THROTTLE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.THROTTLE_CURVE.value))
                ),
                brakeCurve = PedalCurve.from(
                    json.optString(KEY_BRAKE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.BRAKE_CURVE.value))
                ),
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
            put(KEY_SHOW_OVERLAY, settings.showOverlay)
            put(KEY_DISABLE_AUTO_GEAR, settings.disableAutoGear)
            put(KEY_ENABLE_MANUAL_SHIFT, settings.enableManualShift)
            put(KEY_ENABLE_UNLOCK, settings.enableUnlock)
            put(KEY_ENABLE_TC, settings.enableTc)
            put(KEY_ENABLE_ABS, settings.enableAbs)
            put(KEY_ENABLE_MUSIC_REPLACE, settings.enableMusicReplace)
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_BRAKE_TRANSITION, settings.brakeTransition.toDouble())
            put(KEY_BRAKE_INVERT, settings.brakeInvert)
            put(KEY_THROTTLE_CURVE, settings.throttleCurve.value)
            put(KEY_BRAKE_CURVE, settings.brakeCurve.value)
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
            val merged = mergePositionFromLocal(remoteJson, localJson)
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
            val merged = mergePositionFromLocal(moduleConfigJson, localJson)
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
    private fun mergePositionFromLocal(remoteJson: String, localJson: String?): String {
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
            Settings(
                pedalMode = migratePedalMode(j),
                enableAutoDrs = false,
                showOverlay = j.optBoolean(KEY_SHOW_OVERLAY, Defaults.SHOW_OVERLAY),
                disableAutoGear = j.optBoolean(KEY_DISABLE_AUTO_GEAR, Defaults.DISABLE_AUTO_GEAR),
                enableManualShift = j.optBoolean(KEY_ENABLE_MANUAL_SHIFT, Defaults.ENABLE_MANUAL_SHIFT),
                enableUnlock = j.optBoolean(KEY_ENABLE_UNLOCK, Defaults.ENABLE_UNLOCK),
                enableTc = j.optBoolean(KEY_ENABLE_TC, Defaults.ENABLE_TC),
                enableAbs = j.optBoolean(KEY_ENABLE_ABS, Defaults.ENABLE_ABS),
                enableMusicReplace = j.optBoolean(KEY_ENABLE_MUSIC_REPLACE, Defaults.ENABLE_MUSIC_REPLACE),
                pedalDeadzone = j.optDouble(KEY_PEDAL_DEADZONE, Defaults.PEDAL_DEADZONE.toDouble()).toFloat(),
                pedalTransition = j.optDouble(KEY_PEDAL_TRANSITION, Defaults.PEDAL_TRANSITION.toDouble()).toFloat(),
                brakeTransition = j.optDouble(KEY_BRAKE_TRANSITION, Defaults.BRAKE_TRANSITION.toDouble()).toFloat(),
                brakeInvert = j.optBoolean(KEY_BRAKE_INVERT, Defaults.BRAKE_INVERT),
                throttleCurve = PedalCurve.from(
                    j.optString(KEY_THROTTLE_CURVE, j.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.THROTTLE_CURVE.value))
                ),
                brakeCurve = PedalCurve.from(
                    j.optString(KEY_BRAKE_CURVE, j.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.BRAKE_CURVE.value))
                ),
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

    private fun OverlayPosition.toJson(): JSONObject {
        return JSONObject().apply {
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("width", width.toDouble())
            put("height", height.toDouble())
        }
    }

    private fun defaultSettings(): Settings {
        return Settings(
            pedalMode = Defaults.PEDAL_MODE,
            enableAutoDrs = Defaults.ENABLE_AUTO_DRS,
            showOverlay = Defaults.SHOW_OVERLAY,
            disableAutoGear = Defaults.DISABLE_AUTO_GEAR,
            enableManualShift = Defaults.ENABLE_MANUAL_SHIFT,
            enableUnlock = Defaults.ENABLE_UNLOCK,
            enableTc = Defaults.ENABLE_TC,
            enableAbs = Defaults.ENABLE_ABS,
            enableMusicReplace = Defaults.ENABLE_MUSIC_REPLACE,
            pedalDeadzone = Defaults.PEDAL_DEADZONE,
            pedalTransition = Defaults.PEDAL_TRANSITION,
            brakeTransition = Defaults.BRAKE_TRANSITION,
            brakeInvert = Defaults.BRAKE_INVERT,
            throttleCurve = Defaults.THROTTLE_CURVE,
            brakeCurve = Defaults.BRAKE_CURVE,
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
        val showOverlay: Boolean,
        val disableAutoGear: Boolean,
        val enableManualShift: Boolean,
        val enableUnlock: Boolean,
        val enableTc: Boolean = Defaults.ENABLE_TC,
        val enableAbs: Boolean = Defaults.ENABLE_ABS,
        val enableMusicReplace: Boolean = Defaults.ENABLE_MUSIC_REPLACE,
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val brakeTransition: Float,
        val brakeInvert: Boolean = Defaults.BRAKE_INVERT,
        val throttleCurve: PedalCurve,
        val brakeCurve: PedalCurve,
        val pedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val gearPosition: OverlayPosition = OverlayPosition.DEFAULT_GEAR,
        val brakePosition: OverlayPosition = OverlayPosition.DEFAULT_BRAKE,
        val singlePedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        // 工具按钮位置。当前不写 write() / 不进 POSITION_KEYS 合并——
        // 每次打开游戏 OverlayManager 调 resetToDefault() 回到 Defaults。
        // 字段保留是为以后加"记忆位置"开关时零架构改动。
        val toolButtonPosition: OverlayPosition = Defaults.TOOL_BUTTON_POSITION,
        val logEnabled: Boolean = Defaults.LOG_ENABLED
    )
}
