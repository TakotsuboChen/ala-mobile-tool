package tools.alamobile.mod.config

import android.content.Context
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
    const val KEY_THROTTLE_CURVE = "throttle_curve"
    const val KEY_BRAKE_CURVE = "brake_curve"

    // Legacy keys (kept only for one-way migration on read)
    const val KEY_LEGACY_ENABLE_CONTROL_REPLACEMENT = "enable_control_replacement"
    const val KEY_LEGACY_PEDAL_CURVE = "pedal_curve"

    // Overlay positions
    const val KEY_PEDAL_POSITION = "pedal_position"
    const val KEY_GEAR_POSITION = "gear_position"
    const val KEY_BRAKE_POSITION = "brake_position"

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
        val THROTTLE_CURVE = PedalCurve.LINEAR
        val BRAKE_CURVE = PedalCurve.LINEAR
        val PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        val GEAR_POSITION = OverlayPosition.DEFAULT_GEAR
        val BRAKE_POSITION = OverlayPosition.DEFAULT_BRAKE
        const val LOG_ENABLED = false
    }

    /**
     * Returns the shared config file.
     *
     * Uses the module's own external files directory when running in the
     * module process; otherwise falls back to a world-readable path in
     * external storage that works in the target game process on Android 10+.
     */
    private fun getConfigFile(context: Context): File {
        val baseDir = context.getExternalFilesDir(null)
        return if (baseDir != null && context.packageName == MODULE_PACKAGE) {
            File(baseDir, FILE_NAME)
        } else {
            File(Environment.getExternalStorageDirectory(), "AlaMobileTool/$FILE_NAME")
        }
    }

    private fun getSharedConfigDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null)
        return if (baseDir != null && context.packageName == MODULE_PACKAGE) {
            baseDir
        } else {
            File(Environment.getExternalStorageDirectory(), "AlaMobileTool")
        }
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
                throttleCurve = PedalCurve.from(
                    json.optString(KEY_THROTTLE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.THROTTLE_CURVE.value))
                ),
                brakeCurve = PedalCurve.from(
                    json.optString(KEY_BRAKE_CURVE, json.optString(KEY_LEGACY_PEDAL_CURVE, Defaults.BRAKE_CURVE.value))
                ),
                pedalPosition = readOverlayPosition(json, KEY_PEDAL_POSITION, Defaults.PEDAL_POSITION),
                gearPosition = readOverlayPosition(json, KEY_GEAR_POSITION, Defaults.GEAR_POSITION),
                brakePosition = readOverlayPosition(json, KEY_BRAKE_POSITION, Defaults.BRAKE_POSITION),
                logEnabled = json.optBoolean(KEY_LOG_ENABLED, Defaults.LOG_ENABLED)
            )
        } catch (e: Throwable) {
            defaultSettings()
        }
    }

    /**
     * Writes the module settings to the shared JSON file.
     * Should only be called from the module's own process.
     */
    fun write(context: Context, settings: Settings) {
        val file = getConfigFile(context)
        file.parentFile?.mkdirs()

        val json = JSONObject().apply {
            put(KEY_PEDAL_MODE, settings.pedalMode.value)
            put(KEY_ENABLE_AUTO_DRS, settings.enableAutoDrs)
            put(KEY_SHOW_OVERLAY, settings.showOverlay)
            put(KEY_DISABLE_AUTO_GEAR, settings.disableAutoGear)
            put(KEY_ENABLE_MANUAL_SHIFT, settings.enableManualShift)
            put(KEY_ENABLE_UNLOCK, settings.enableUnlock)
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_THROTTLE_CURVE, settings.throttleCurve.value)
            put(KEY_BRAKE_CURVE, settings.brakeCurve.value)
            put(KEY_PEDAL_POSITION, settings.pedalPosition.toJson())
            put(KEY_GEAR_POSITION, settings.gearPosition.toJson())
            put(KEY_BRAKE_POSITION, settings.brakePosition.toJson())
            put(KEY_LOG_ENABLED, settings.logEnabled)
        }

        file.writeText(json.toString(2))
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
     * The shared JSON file is world-readable through external storage.
     */
    fun readFromTargetProcess(context: Context): Settings {
        return read(context)
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
            throttleCurve = Defaults.THROTTLE_CURVE,
            brakeCurve = Defaults.BRAKE_CURVE,
            pedalPosition = Defaults.PEDAL_POSITION,
            gearPosition = Defaults.GEAR_POSITION,
            brakePosition = Defaults.BRAKE_POSITION,
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
        val throttleCurve: PedalCurve,
        val brakeCurve: PedalCurve,
        val pedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val gearPosition: OverlayPosition = OverlayPosition.DEFAULT_GEAR,
        val brakePosition: OverlayPosition = OverlayPosition.DEFAULT_BRAKE,
        val logEnabled: Boolean = Defaults.LOG_ENABLED
    )
}
