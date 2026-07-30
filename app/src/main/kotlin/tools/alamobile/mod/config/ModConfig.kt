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

    // Feature toggles
    const val KEY_ENABLE_AUTO_DRS = "enable_auto_drs"
    const val KEY_SHOW_OVERLAY = "show_overlay"
    const val KEY_DISABLE_AUTO_GEAR = "disable_auto_gear"
    const val KEY_ENABLE_MANUAL_SHIFT = "enable_manual_shift"
    const val KEY_ENABLE_UNLOCK = "enable_unlock"

    // Pedal mapping
    const val KEY_PEDAL_MODE = "pedal_mode"
    const val KEY_PEDAL_DEADZONE = "pedal_deadzone"
    const val KEY_PEDAL_TRANSITION = "pedal_transition"
    const val KEY_BRAKE_TRANSITION = "brake_transition"
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
        val PEDAL_MODE = PedalMode.SINGLE
        const val PEDAL_DEADZONE = 0.05f
        const val PEDAL_TRANSITION = 0.5f
        // 双踏板模式下油门/刹车仲裁的过渡点（用户配置 0..0.2）。
        // 刹车值 ≥ 此点 → 刹车优先屏蔽油门；< 此点且油门>0 → 油门优先屏蔽刹车。
        const val BRAKE_TRANSITION = 0.1f
        val THROTTLE_CURVE = PedalCurve.LINEAR
        val BRAKE_CURVE = PedalCurve.LINEAR
        val PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        val GEAR_POSITION = OverlayPosition.DEFAULT_GEAR
        val BRAKE_POSITION = OverlayPosition.DEFAULT_BRAKE
        val SINGLE_PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
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
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_BRAKE_TRANSITION, settings.brakeTransition.toDouble())
            put(KEY_THROTTLE_CURVE, settings.throttleCurve.value)
            put(KEY_BRAKE_CURVE, settings.brakeCurve.value)
            // 不写 position 三字段：position 由游戏进程持有（拖拽时
            // saveOverlayPosition 写游戏 externalFilesDir），ConfigActivity
            // 不管 position。广播 JSON 不含 position，ConfigReceiver 收到
            // 后合并——保留游戏进程已有的 position，只更新这里的非 position 字段。
            put(KEY_LOG_ENABLED, settings.logEnabled)
        }.toString(2)

        // 1. 写模块 filesDir 作持久化备份（模块进程天然可写）。
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json)
            android.util.Log.i("AlaMobileTool", "Config written to module filesDir: ${file.absolutePath}")
        } catch (e: Throwable) {
            android.util.Log.e("AlaMobileTool", "ModConfig.write to filesDir failed", e)
        }

        // 2. 发定向广播给所有目标游戏包，让游戏进程 ConfigReceiver 写自己目录。
        //    setPackage 定向派发，不查 PackageManager 可见性，绕过包可见性限制。
        //    已知限制：游戏没运行时广播丢失，下次启动读到旧值（M11 遗留），
        //    所有跨进程拉取路径（公共目录/ContentProvider/createPackageContext）
        //    在 Android 11+ scoped storage + 包可见性下全部失效，待 LSPosed
        //    特性研究后用正确方案修复。
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
     * Android 11+ 包可见性 + scoped storage 让游戏进程既看不到模块 ContentProvider
     * 也读不到模块 filesDir。广播方案下，ConfigActivity 发定向广播带 JSON，
     * 游戏进程 ConfigReceiver 收到后用自己 context 写自己 getExternalFilesDir
     * （游戏进程天然可读写）。这里读游戏自己目录的配置。
     */
    fun readFromTargetProcess(context: Context): Settings {
        // 游戏进程读自己 externalFilesDir 的配置——这是 ConfigReceiver 收到
        // 广播后写入的位置。游戏进程对它天然可读，无需权限，无 scoped storage 限制。
        val dir = context.getExternalFilesDir(null) ?: run {
            android.util.Log.w("AlaMobileTool", "readFromTargetProcess: externalFilesDir null, using defaults")
            return defaultSettings()
        }
        val file = File(dir, FILE_NAME)
        // 诊断日志：打印路径、是否存在、lastModified、内容预览，用于排查
        // "游戏启动读到陈旧配置"的 M11 首次滞后 bug。
        android.util.Log.i(
            "AlaMobileTool",
            "readFromTargetProcess: path=${file.absolutePath} exists=${file.exists()} " +
                "lastModified=${if (file.exists()) java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(file.lastModified())) else "n/a"} " +
                "now=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
        )

        // 已知限制（M11 遗留）：广播在游戏没运行时丢失，游戏 externalFilesDir
        // 的配置会陈旧。所有跨进程拉取路径（ContentProvider/createPackageContext
        // /公共目录）在 Android 11+ 都已实测失效，待 LSPosed 特性研究后用
        // 正确方案修复。当前先读游戏目录，保证游戏运行时广播路径正常工作。
        // 诊断日志保留，用于排查"读到陈旧配置"场景。

        return if (file.exists()) {
            try {
                val json = file.readText()
                val settings = fromJson(json)
                android.util.Log.i(
                    "AlaMobileTool",
                    "Config read from game dir: pedalMode=${settings.pedalMode} " +
                        "jsonPreview=${json.take(120).replace('\n', ' ')}"
                )
                settings
            } catch (e: Throwable) {
                android.util.Log.w("AlaMobileTool", "Config read failed, using defaults", e)
                defaultSettings()
            }
        } else {
            android.util.Log.i("AlaMobileTool", "No config in game dir, using defaults")
            defaultSettings()
        }
    }

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
                pedalDeadzone = j.optDouble(KEY_PEDAL_DEADZONE, Defaults.PEDAL_DEADZONE.toDouble()).toFloat(),
                pedalTransition = j.optDouble(KEY_PEDAL_TRANSITION, Defaults.PEDAL_TRANSITION.toDouble()).toFloat(),
                brakeTransition = j.optDouble(KEY_BRAKE_TRANSITION, Defaults.BRAKE_TRANSITION.toDouble()).toFloat(),
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
            pedalDeadzone = Defaults.PEDAL_DEADZONE,
            pedalTransition = Defaults.PEDAL_TRANSITION,
            brakeTransition = Defaults.BRAKE_TRANSITION,
            throttleCurve = Defaults.THROTTLE_CURVE,
            brakeCurve = Defaults.BRAKE_CURVE,
            pedalPosition = Defaults.PEDAL_POSITION,
            gearPosition = Defaults.GEAR_POSITION,
            brakePosition = Defaults.BRAKE_POSITION,
            singlePedalPosition = Defaults.SINGLE_PEDAL_POSITION,
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
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val brakeTransition: Float,
        val throttleCurve: PedalCurve,
        val brakeCurve: PedalCurve,
        val pedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val gearPosition: OverlayPosition = OverlayPosition.DEFAULT_GEAR,
        val brakePosition: OverlayPosition = OverlayPosition.DEFAULT_BRAKE,
        val singlePedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val logEnabled: Boolean = Defaults.LOG_ENABLED
    )
}
