package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.util.Log
import com.bytedance.shadowhook.ShadowHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.hook.BillingHook
import tools.alamobile.mod.overlay.OverlayManager
import tools.alamobile.mod.util.isSupportedVersion
import java.io.File

class AlaMobileModule : XposedModule() {

    companion object {
        const val TAG = "AlaMobileTool"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Module loaded in process ${param.processName}")
        markActivated()
    }

    private fun markActivated() {
        try {
            val flag = File(Environment.getExternalStorageDirectory(), "AlaMobileTool/activated.flag")
            flag.parentFile?.mkdirs()
            flag.writeText("1")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to write activation flag", e)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.i(TAG, "Package loaded: ${param.packageName}")

        // Install Java hooks for billing bypass
        try {
            BillingHook.install(this, param)
            Log.i(TAG, "Java hooks installed successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to install Java hooks: ${e.message}")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "Package ready: ${param.packageName}, isFirstPackage=${param.isFirstPackage}")
        if (!param.isFirstPackage) {
            Log.w(TAG, "Not the first package, continuing anyway")
        }

        var context = getAppContext()
        Log.i(TAG, "Initial context: $context")
        if (context == null) {
            Thread.sleep(500)
            context = getAppContext()
            Log.i(TAG, "Delayed context: $context")
        }

        if (context != null && !isSupportedVersion(context)) {
            Log.w(TAG, "Unsupported game version, attempting hooks anyway for debugging")
        }

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

        val settings = if (context != null) {
            try {
                ModConfig.readFromTargetProcess(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read config, using defaults", e)
                null
            }
        } else null

        val enableControlReplacement = settings?.enableControlReplacement ?: true
        val enableAutoDrs = settings?.enableAutoDrs ?: true
        val showOverlay = settings?.showOverlay ?: true
        val disableAutoGear = settings?.disableAutoGear ?: false
        val enableUnlock = settings?.enableUnlock ?: false

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
                // Force-load native library in case ClassLoader isolation
                // prevented the standard System.loadLibrary from working.
                if (ctx != null) {
                    NativeBridge.forceLoad(ctx)
                }

                NativeBridge.initWithOffsets(
                    enableControlReplacement = enableControlReplacement,
                    enableAutoDRS = enableAutoDrs,
                    disableAutoGear = disableAutoGear,
                    enableUnlock = enableUnlock
                )
                Log.i(TAG, "Native hooks installed (isAvailable=${NativeBridge.isAvailable})")
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
