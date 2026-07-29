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

        /**
         * 进程级"native hooks 已装"标记的 property key。
         *
         * LSPosed 在重打包/共存版游戏上会用双 ClassLoader
         * (LspModuleClassLoader + VectorModuleClassLoader) 各注入一次模块。
         * 第二个 ClassLoader 的 inline hook 全部失败 (ShadowHook
         * "Not initialized")，但 PedalOverlayView 仍调到第二个空壳副本
         * 的 JNI；同时第二个副本会启动第二个 writer 线程，与第一个
         * 抢同一个 IPC 文件、写同一个 IL2CPP 字段，引发踏板抖动。
         *
         * System.setProperty 进程级共享 (java.lang.System 由
         * bootstrap ClassLoader 加载)，第二个 ClassLoader 能读到第一个
         * 设的标记，跳过重复装 hook，从而消除第二个 writer 线程。
         *
         * 关键：标记只在 native 真正装好之后再立，不能在 onModuleLoaded
         * 里立——那会拦掉同一个 ClassLoader 自己后续的 onPackageReady，
         * 导致整个模块初始化逻辑全被跳过 (上一版的回归)。
         */
        private const val NATIVE_INSTALLED_FLAG = "tools.alamobile.mod.native_installed"

        private fun isNativeInstalled(): Boolean =
            System.getProperty(NATIVE_INSTALLED_FLAG) == "true"

        private fun markNativeInstalled() {
            System.setProperty(NATIVE_INSTALLED_FLAG, "true")
        }
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

        val pedalMode = settings?.pedalMode ?: ModConfig.PedalMode.SINGLE
        val enableControlReplacement = pedalMode != ModConfig.PedalMode.OFF
        val enableAutoDrs = settings?.enableAutoDrs ?: false
        val showOverlay = settings?.showOverlay ?: true
        // 手动换挡开 ⇒ 关闭游戏自动换挡（disableAutoGear 由 enableManualShift 派生）。
        // 当前 enableManualShift 默认 false，所以 disableAutoGear=false，游戏自动换挡保持原样。
        val enableManualShift = settings?.enableManualShift ?: false
        val disableAutoGear = enableManualShift
        val enableUnlock = settings?.enableUnlock ?: false

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            // 双 ClassLoader 守卫：LSPosed 在共存版上注入两次模块，第一个
            // ClassLoader 装好 hook + overlay 后会 markNativeInstalled()。
            // 第二个 ClassLoader 跑到这里时直接跳过，避免：
            // 1) 重复装 overlay（两个 PedalOverlayView 同时写 IPC 文件）
            // 2) 第二个 .so 副本装 hook 失败但启动第二个 writer 线程
            if (isNativeInstalled()) {
                Log.i(TAG, "Native already installed by another ClassLoader, skipping overlay+hooks")
                return@postDelayed
            }
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
                // native 真正装好之后才立标记 —— 在此之前不拦，确保
                // 第一个 ClassLoader 自己的 onPackageReady 流程不被自己拦掉
                markNativeInstalled()
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
