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
    const val KEY_ENABLE_CONTROL_REPLACEMENT = "enable_control_replacement"
    const val KEY_ENABLE_AUTO_DRS = "enable_auto_drs"
    const val KEY_SHOW_OVERLAY = "show_overlay"
    const val KEY_DISABLE_AUTO_GEAR = "disable_auto_gear"

    // Pedal mapping
    const val KEY_PEDAL_DEADZONE = "pedal_deadzone"
    const val KEY_PEDAL_TRANSITION = "pedal_transition"
    const val KEY_PEDAL_CURVE = "pedal_curve"

    // Overlay positions
    const val KEY_PEDAL_POSITION = "pedal_position"
    const val KEY_GEAR_POSITION = "gear_position"

    // Debug/logging
    const val KEY_LOG_ENABLED = "log_enabled"

    enum class PedalCurve(val value: String) {
        LINEAR("linear"),
        EXPONENTIAL("exponential"),
        QUADRATIC("quadratic");

        companion object {
            fun from(value: String?): PedalCurve {
                return entries.find { it.value == value } ?: LINEAR
            }
        }
    }

    private object Defaults {
        const val ENABLE_CONTROL_REPLACEMENT = true
        const val ENABLE_AUTO_DRS = true
        const val SHOW_OVERLAY = true
        const val DISABLE_AUTO_GEAR = false
        const val PEDAL_DEADZONE = 0.05f
        const val PEDAL_TRANSITION = 0.5f
        val PEDAL_CURVE = PedalCurve.LINEAR
        val PEDAL_POSITION = OverlayPosition.DEFAULT_PEDAL
        val GEAR_POSITION = OverlayPosition.DEFAULT_GEAR
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
                enableControlReplacement = json.optBoolean(
                    KEY_ENABLE_CONTROL_REPLACEMENT,
                    Defaults.ENABLE_CONTROL_REPLACEMENT
                ),
                enableAutoDrs = json.optBoolean(
                    KEY_ENABLE_AUTO_DRS,
                    Defaults.ENABLE_AUTO_DRS
                ),
                showOverlay = json.optBoolean(
                    KEY_SHOW_OVERLAY,
                    Defaults.SHOW_OVERLAY
                ),
                disableAutoGear = json.optBoolean(
                    KEY_DISABLE_AUTO_GEAR,
                    Defaults.DISABLE_AUTO_GEAR
                ),
                pedalDeadzone = json.optDouble(
                    KEY_PEDAL_DEADZONE,
                    Defaults.PEDAL_DEADZONE.toDouble()
                ).toFloat(),
                pedalTransition = json.optDouble(
                    KEY_PEDAL_TRANSITION,
                    Defaults.PEDAL_TRANSITION.toDouble()
                ).toFloat(),
                pedalCurve = PedalCurve.from(
                    json.optString(KEY_PEDAL_CURVE, Defaults.PEDAL_CURVE.value)
                ),
                pedalPosition = readOverlayPosition(json, KEY_PEDAL_POSITION, Defaults.PEDAL_POSITION),
                gearPosition = readOverlayPosition(json, KEY_GEAR_POSITION, Defaults.GEAR_POSITION),
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
            put(KEY_ENABLE_CONTROL_REPLACEMENT, settings.enableControlReplacement)
            put(KEY_ENABLE_AUTO_DRS, settings.enableAutoDrs)
            put(KEY_SHOW_OVERLAY, settings.showOverlay)
            put(KEY_DISABLE_AUTO_GEAR, settings.disableAutoGear)
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_PEDAL_CURVE, settings.pedalCurve.value)
            put(KEY_PEDAL_POSITION, settings.pedalPosition.toJson())
            put(KEY_GEAR_POSITION, settings.gearPosition.toJson())
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
            enableControlReplacement = Defaults.ENABLE_CONTROL_REPLACEMENT,
            enableAutoDrs = Defaults.ENABLE_AUTO_DRS,
            showOverlay = Defaults.SHOW_OVERLAY,
            disableAutoGear = Defaults.DISABLE_AUTO_GEAR,
            pedalDeadzone = Defaults.PEDAL_DEADZONE,
            pedalTransition = Defaults.PEDAL_TRANSITION,
            pedalCurve = Defaults.PEDAL_CURVE,
            pedalPosition = Defaults.PEDAL_POSITION,
            gearPosition = Defaults.GEAR_POSITION,
            logEnabled = Defaults.LOG_ENABLED
        )
    }

    data class Settings(
        val enableControlReplacement: Boolean,
        val enableAutoDrs: Boolean,
        val showOverlay: Boolean,
        val disableAutoGear: Boolean,
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val pedalCurve: PedalCurve,
        val pedalPosition: OverlayPosition = OverlayPosition.DEFAULT_PEDAL,
        val gearPosition: OverlayPosition = OverlayPosition.DEFAULT_GEAR,
        val logEnabled: Boolean = Defaults.LOG_ENABLED
    )
}
