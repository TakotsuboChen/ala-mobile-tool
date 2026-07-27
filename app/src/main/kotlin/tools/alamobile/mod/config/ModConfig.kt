package tools.alamobile.mod.config

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * JSON-backed configuration for Ala Mobile Tool.
 *
 * The ConfigActivity writes settings to the module's external files
 * directory so the target game process can read the same file without
 * relying on deprecated [Context.MODE_WORLD_READABLE].
 */
object ModConfig {

    private const val FILE_NAME = "ala_tool_config.json"
    private const val MODULE_PACKAGE = "tools.alamobile.mod"

    // Feature toggles
    const val KEY_ENABLE_CONTROL_REPLACEMENT = "enable_control_replacement"
    const val KEY_ENABLE_AUTO_DRS = "enable_auto_drs"
    const val KEY_SHOW_OVERLAY = "show_overlay"

    // Pedal mapping
    const val KEY_PEDAL_DEADZONE = "pedal_deadzone"
    const val KEY_PEDAL_TRANSITION = "pedal_transition"
    const val KEY_PEDAL_CURVE = "pedal_curve"

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
        const val PEDAL_DEADZONE = 0.05f
        const val PEDAL_TRANSITION = 0.5f
        val PEDAL_CURVE = PedalCurve.LINEAR
    }

    /**
     * Returns the shared config file.
     *
     * We store the file directly on external storage (not in an
     * app-specific directory) so the target game process can read it on
     * Android 10+ without relying on the deprecated
     * [Context.MODE_WORLD_READABLE].
     */
    private fun getConfigFile(context: Context): File {
        val baseDir = context.getExternalFilesDir(null)
        return if (baseDir != null && context.packageName == MODULE_PACKAGE) {
            File(baseDir, FILE_NAME)
        } else {
            File(Environment.getExternalStorageDirectory(), "AlaMobileTool/$FILE_NAME")
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
                )
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
            put(KEY_PEDAL_DEADZONE, settings.pedalDeadzone.toDouble())
            put(KEY_PEDAL_TRANSITION, settings.pedalTransition.toDouble())
            put(KEY_PEDAL_CURVE, settings.pedalCurve.value)
        }

        file.writeText(json.toString(2))
    }

    /**
     * Reads the module settings from the target game process.
     * The shared JSON file is world-readable through external storage.
     */
    fun readFromTargetProcess(context: Context): Settings {
        return read(context)
    }

    private fun defaultSettings(): Settings {
        return Settings(
            enableControlReplacement = Defaults.ENABLE_CONTROL_REPLACEMENT,
            enableAutoDrs = Defaults.ENABLE_AUTO_DRS,
            showOverlay = Defaults.SHOW_OVERLAY,
            pedalDeadzone = Defaults.PEDAL_DEADZONE,
            pedalTransition = Defaults.PEDAL_TRANSITION,
            pedalCurve = Defaults.PEDAL_CURVE
        )
    }

    data class Settings(
        val enableControlReplacement: Boolean,
        val enableAutoDrs: Boolean,
        val showOverlay: Boolean,
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val pedalCurve: PedalCurve
    )
}
