package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
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
        Log.i(TAG, "Module loaded in process ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.i(TAG, "Package loaded: ${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "Package ready: ${param.packageName}")
        if (!param.isFirstPackage) return

        var context = getAppContext()
        Log.i(TAG, "Initial context: $context")
        if (context == null) {
            Thread.sleep(500)
            context = getAppContext()
            Log.i(TAG, "Delayed context: $context")
        }

        if (context != null && !isSupportedVersion(context)) {
            Log.w(TAG, "Unsupported game version, skipping native hooks")
            return
        }

        Log.i(TAG, "Target version supported; installing hooks")

        try {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.SHARED)
                    .build()
            )
            Log.i(TAG, "ShadowHook initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ShadowHook: ${e.message}")
            return
        }

        val settings = if (context != null) ModConfig.readFromTargetProcess(context) else null

        val enableControlReplacement = settings?.enableControlReplacement ?: true
        val enableAutoDrs = settings?.enableAutoDrs ?: true
        val showOverlay = settings?.showOverlay ?: true

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            val ctx = getAppContext()
            Log.i(TAG, "Delayed context: $ctx")

            if (ctx != null && showOverlay) {
                try {
                    val overlayManager = OverlayManager(ctx)
                    overlayManager.showOverlays()
                    Log.i(TAG, "Overlay shown")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to show overlay: ${e.message}")
                }
            }

            try {
                NativeBridge.initWithOffsets(
                    enableControlReplacement = enableControlReplacement,
                    enableAutoDRS = enableAutoDrs
                )
                Log.i(TAG, "Native hooks installed")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to install native hooks: ${e.message}")
            }
        }, 15000)
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
