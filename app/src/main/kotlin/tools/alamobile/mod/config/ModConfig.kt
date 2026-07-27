package tools.alamobile.mod.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * SharedPreferences-backed configuration for Ala Mobile Tool.
 *
 * The ConfigActivity writes settings in the module's own process;
 * [AlaMobileModule] reads the same file in the target game process.
 * MODE_WORLD_READABLE is required so the game process can read the
 * module's preferences file.
 */
object ModConfig {

    private const val PREFS_NAME = "tools.alamobile.mod_preferences"

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

    private const val MODULE_PACKAGE = "tools.alamobile.mod"

    @Suppress("DEPRECATION")
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
    }

    /**
     * Reads the module settings from the module's own package context.
     * This must be used from the target game process, where the default
     * [Context] belongs to the game rather than the module.
     */
    fun readFromTargetProcess(context: Context): Settings {
        return try {
            val moduleContext = context.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            read(moduleContext)
        } catch (e: Throwable) {
            // Fallback to defaults if the module context cannot be created.
            Settings(
                enableControlReplacement = true,
                enableAutoDrs = true,
                showOverlay = true,
                pedalDeadzone = 0.05f,
                pedalTransition = 0.5f,
                pedalCurve = PedalCurve.LINEAR
            )
        }
    }

    data class Settings(
        val enableControlReplacement: Boolean,
        val enableAutoDrs: Boolean,
        val showOverlay: Boolean,
        val pedalDeadzone: Float,
        val pedalTransition: Float,
        val pedalCurve: PedalCurve
    )

    fun read(context: Context): Settings {
        val prefs = getPreferences(context)
        return Settings(
            enableControlReplacement = prefs.getBoolean(
                KEY_ENABLE_CONTROL_REPLACEMENT,
                Defaults.ENABLE_CONTROL_REPLACEMENT
            ),
            enableAutoDrs = prefs.getBoolean(
                KEY_ENABLE_AUTO_DRS,
                Defaults.ENABLE_AUTO_DRS
            ),
            showOverlay = prefs.getBoolean(
                KEY_SHOW_OVERLAY,
                Defaults.SHOW_OVERLAY
            ),
            pedalDeadzone = prefs.getFloat(KEY_PEDAL_DEADZONE, Defaults.PEDAL_DEADZONE),
            pedalTransition = prefs.getFloat(KEY_PEDAL_TRANSITION, Defaults.PEDAL_TRANSITION),
            pedalCurve = PedalCurve.from(
                prefs.getString(KEY_PEDAL_CURVE, Defaults.PEDAL_CURVE.value)
            )
        )
    }

    fun write(context: Context, settings: Settings) {
        getPreferences(context).edit {
            putBoolean(KEY_ENABLE_CONTROL_REPLACEMENT, settings.enableControlReplacement)
            putBoolean(KEY_ENABLE_AUTO_DRS, settings.enableAutoDrs)
            putBoolean(KEY_SHOW_OVERLAY, settings.showOverlay)
            putFloat(KEY_PEDAL_DEADZONE, settings.pedalDeadzone)
            putFloat(KEY_PEDAL_TRANSITION, settings.pedalTransition)
            putString(KEY_PEDAL_CURVE, settings.pedalCurve.value)
        }
    }
}
