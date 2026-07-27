package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.util.Log
import com.bytedance.shadowhook.ShadowHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.overlay.OverlayManager
import tools.alamobile.mod.util.isSupportedVersion

class AlaMobileModule : XposedModule() {

    companion object {
        const val TAG = "AlaMobileTool"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Module loaded in process ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "Package loaded: ${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, TAG, "Package ready: ${param.packageName}")
        if (!param.isFirstPackage) return

        val context = getAppContext()
        if (!isSupportedVersion(context)) {
            log(Log.WARN, TAG, "Unsupported game version, skipping native hooks")
            return
        }

        log(Log.INFO, TAG, "Target version supported; installing hooks")

        try {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.SHARED)
                    .build()
            )
            log(Log.INFO, TAG, "ShadowHook initialized")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "Failed to initialize ShadowHook: ${e.message}")
            return
        }

        val settings = if (context != null) ModConfig.readFromTargetProcess(context) else null

        val enableControlReplacement = settings?.enableControlReplacement ?: true
        val enableAutoDrs = settings?.enableAutoDrs ?: true
        val showOverlay = settings?.showOverlay ?: true

        if (context != null && showOverlay) {
            val overlayManager = OverlayManager(context)
            overlayManager.showOverlays()
        }

        NativeBridge.initWithOffsets(
            enableControlReplacement = enableControlReplacement,
            enableAutoDRS = enableAutoDrs
        )
    }

    private fun getAppContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getMethod("currentApplication")
            val app = method.invoke(null) as? Application
            app?.applicationContext
        } catch (e: Throwable) {
            null
        }
    }
}
