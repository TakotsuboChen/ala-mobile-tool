package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.bytedance.shadowhook.ShadowHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import tools.alamobile.mod.config.ConfigReceiver
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.hook.BillingHook
import tools.alamobile.mod.overlay.OverlayManager
import tools.alamobile.mod.util.isSupportedVersion
import java.io.File

class AlaMobileModule : XposedModule() {

    companion object {
        const val TAG = "AlaMobileTool"

        /**
         * XposedInterface 实例引用，供非 XposedModule 子类（如 BillingHook）调用
         * xposedInterface.log() 写日志到 NPatch 日志文件。
         * 在 onPackageLoaded 时设置。
         */
        var xposedInterface: io.github.libxposed.api.XposedInterface? = null
            private set

        /**
         * 通过 XposedInterface 写日志到 NPatch 日志文件（Android/media/.../npatch/log/）。
         * XposedInterface.log() 直接调 XposedBridge.log() → XposedLogPrinter → 写文件，
         * 不走 XLog 白名单过滤，所以即使用户 tag 不是 "NPatch" 也能写入。
         * 同时 fallback 到 android.util.Log 确保 logcat 也能看到。
         */
        fun logX(priority: Int, tag: String, msg: String) {
            xposedInterface?.log(priority, tag, msg)
            android.util.Log.println(priority, tag, msg)
        }

        /**
         * 进程级"模块已被框架加载"标记的 property key。
         *
         * ConfigActivity 与 AlaMobileModule 跑在同一模块进程，java.lang.System
         * 由 bootstrap ClassLoader 加载、进程内全局共享。onModuleLoaded 由
         * libxposed 框架在模块进程启动时调用——只有模块真的被 LSPosed /
         * Non-root 框架（LSPatch 等）启用才会调到。ConfigActivity 读这个
         * property 即可严格判断"当前进程本次启动是否真被框架加载"，不受
         * 旧 flag 文件长期残留污染（LSPosed 关掉模块后重启进程，property
         * 自然不存在）。
         *
         * 注意：onModuleLoaded 的调用时机由框架决定，可能在 Application.onCreate
         * 之后。ConfigActivity 读检测时若太早可能还没设上——[tools.alamobile.mod.ActivationStatus]
         * 用带轮询的 evaluate 兜住这个时序窗口。
         */
        const val MODULE_LOADED_FLAG = "tools.alamobile.mod.module_loaded"

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
        logX(Log.INFO, TAG, "Module loaded in process ${param.processName}")
        markActivated()
    }

    /**
     * 写"模块已被框架加载"的进程级 property `MODULE_LOADED_FLAG`。
     *
     * 语义：onModuleLoaded 只在模块被框架注入的**目标进程**（游戏进程）里调用，
     * 模块自己的 ConfigActivity 进程**从不调用**。所以此 property 对游戏进程
     * 是可靠的"模块被真正启用"信号（禁用后进程重启消失），但 ConfigActivity
     * 进程里永远为 false——[tools.alamobile.mod.LsposedStatus.evaluate] 必须
     * 额外加 daemon scope 判定（见 LsposedStatus 的判定优先级说明）。
     */
    private fun markActivated() {
        System.setProperty(MODULE_LOADED_FLAG, "true")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        logX(Log.INFO, TAG, "Package loaded: ${param.packageName}")
        xposedInterface = this
        logX(Log.INFO, TAG, "xposedInterface set: $this")

        // ⚠️⚠️ vivo/OriginOS/Android 16 闪退修复：onPackageLoaded 也在
        // createOrUpdateClassLoaderLocked 内部同步调用（比 onPackageReady 更早）。
        // 在这里跑 BillingHook.install（Class.forName + xposedInterface.hook）
        // 会干扰 Resources 初始化 → makeApplicationInner NPE 闪退。
        // 修复：也延迟到 next main loop。BillingBridge 的 Java hook 是辅助路径
        //（native SetUnlocked 才是主路径），延迟几毫秒不影响功能。
        logX(Log.INFO, TAG, "NPatch: deferring BillingHook.install to next main loop")
        Handler(Looper.getMainLooper()).post {
            try {
                BillingHook.install(this, param)
                logX(Log.INFO, TAG, "Java hooks installed successfully (deferred)")
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Failed to install Java hooks: ${e.message}")
            }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        logX(Log.INFO, TAG, "Package ready: ${param.packageName}, isFirstPackage=${param.isFirstPackage}")
        if (!param.isFirstPackage) {
            logX(Log.WARN, TAG, "Not the first package, continuing anyway")
        }

        // ⚠️⚠️ vivo/OriginOS/Android 16 闪退修复：onPackageReady 在
        // createOrUpdateClassLoaderLocked 内部同步调用（handleBindApplication 路径）。
        // 此时 LoadedApk 的 Resources 尚未初始化。如果在 onPackageReady 里做任何
        // 重操作（ShadowHook.init、forceLoad、JNI 调用、甚至 Context 访问），
        // 会干扰 Resources 创建链路 → makeApplicationInner 时
        // Resources.getAssets() NPE 闪退。
        // 修复：onPackageReady 里只做最轻量操作（logX + Handler.post），
        // 所有重操作全部延迟到主线程下一个循环（handleBindApplication 先完成）。
        //
        // 不在这里调 getAppContext() —— context==null 在 vivo 上是正常的（Resources
        // 还没好），Thread.sleep(500) 也在 main thread，会阻塞 handleBindApplication。
        // context 的获取移到 deferred block 里。
        logX(Log.INFO, TAG, "NPatch: deferring all native init + config to next main loop")

        Handler(Looper.getMainLooper()).post {
            // === 以下所有代码都在 next main loop 执行，handleBindApplication 已完成 ===
            doPackageReadyDeferred(param)
        }
    }

    private fun doPackageReadyDeferred(param: PackageReadyParam) {
        var context = getAppContext()
        logX(Log.INFO, TAG, "Deferred context: $context")
        if (context == null) {
            Thread.sleep(500)
            context = getAppContext()
            logX(Log.INFO, TAG, "Delayed deferred context: $context")
        }

        // 诊断：检查设备 GMS 状态（onPackageReady 时 context 才可用）
        if (context != null) {
            try {
                val pm = context.packageManager
                try {
                    pm.getPackageInfo("com.android.vending", 0)
                    logX(Log.INFO, TAG, "DIAG: Google Play Store installed (com.android.vending)")
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    logX(Log.WARN, TAG, "DIAG: Google Play Store NOT installed")
                }
                try {
                    pm.getPackageInfo("com.google.android.gms", 0)
                    logX(Log.INFO, TAG, "DIAG: Google Play Services installed (com.google.android.gms)")
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    logX(Log.WARN, TAG, "DIAG: Google Play Services NOT installed")
                }
            } catch (e: Throwable) {
                logX(Log.WARN, TAG, "DIAG: GMS check failed: ${e.message}")
            }
        }

        if (context != null && !isSupportedVersion(context)) {
            logX(Log.WARN, TAG, "Unsupported game version, attempting hooks anyway for debugging")
        }

        // ShadowHook + forceLoad + initUnlock 全部在这里同步执行
        //（已通过 Handler.post 从 onPackageReady 延迟到 next main loop，
        // handleBindApplication 已完成，Resources 已就绪）。
        try {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.UNIQUE)
                    .build()
            )
            logX(Log.INFO, TAG, "ShadowHook initialized (UNIQUE mode, deferred)")
        } catch (e: Throwable) {
            logX(Log.ERROR, TAG, "Failed to initialize ShadowHook: ${e.message}")
            return
        }

        // 注入 Remote Preferences reader：readFromTargetProcess 经此回调读 LSPosed
        // daemon SQLite 里的权威配置 JSON。getRemotePreferences 是 XposedModule 基类方法
        // （libxposed API 102），经 Binder 路由到 daemon（常驻），不依赖游戏进程是否
        // 在运行——根治"游戏没运行→广播丢失→下次启动读旧值"的 M11 首次滞后。
        // 必须在 onPackageReady 早期注入，这样后面的 readFromTargetProcess 调用就能用上。
        // key 不存在或异常返回 null，readFromTargetProcess 会回退本地 externalFilesDir。
        ModConfig.remoteConfigReader = {
            try {
                val prefs = getRemotePreferences(tools.alamobile.mod.App.PREF_GROUP)
                val json = prefs.getString(tools.alamobile.mod.App.KEY_CONFIG_JSON, null)
                if (json != null) {
                    logX(Log.INFO, TAG, "getRemotePreferences ok, len=${json.length}")
                } else {
                    logX(Log.WARN, TAG, "getRemotePreferences: key not found in daemon db")
                }
                json
            } catch (e: Throwable) {
                logX(Log.WARN, TAG, "getRemotePreferences failed: ${e.message}")
                null
            }
        }

        // 注册 ConfigReceiver：接收 ConfigActivity 发来的定向广播（带最新配置 JSON），
        // 写入游戏进程自己的 externalFilesDir。Remote Preferences 路线下广播的价值是
        // "游戏运行时即时更新"——service 异步绑定可能延迟，广播立即推送让 overlay 马上
        // 重建。setPackage 定向派发，不查 PackageManager 可见性，绕过 Android 11+ 包可见性
        // 限制。游戏没运行时广播丢失不再造成问题：下次启动 readFromTargetProcess 走
        // Remote Preferences 读 daemon 的最新权威值。
        if (context != null) {
            try {
                val receiver = ConfigReceiver()
                val filter = android.content.IntentFilter(ConfigReceiver.ACTION_CONFIG_UPDATE)
                // RECEIVER_EXPORTED：广播来自模块进程（不同应用），跨应用派发，
                // 必须用 EXPORTED 标志（Android 13+ 强制要求）。用 ContextCompat
                // 重载：内部按 SDK_INT 自动分发旧/新 API，且对 lint 的
                // UnspecifiedRegisterReceiverFlag 检查友好（显式传 flag）。
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                logX(Log.INFO, TAG, "ConfigReceiver registered")
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Failed to register ConfigReceiver: ${e.message}")
            }
        }

        val settings = if (context != null) {
            try {
                val s = ModConfig.readFromTargetProcess(context)
                logX(Log.INFO, TAG, "onPackageReady read config: pedalMode=${s.pedalMode} enableUnlock=${s.enableUnlock} enableManualShift=${s.enableManualShift} enableTc=${s.enableTc} enableAbs=${s.enableAbs}")
                s
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Failed to read config, using defaults: ${e.message}")
                null
            }
        } else null

        val pedalMode = settings?.pedalMode ?: ModConfig.PedalMode.SINGLE
        val enableControlReplacement = pedalMode != ModConfig.PedalMode.OFF
        val enableAutoDrs = settings?.enableAutoDrs ?: false
        // 手动换挡开 ⇒ 关闭游戏自动换挡（disableAutoGear 由 enableManualShift 派生）。
        // 当前 enableManualShift 默认 false，所以 disableAutoGear=false，游戏自动换挡保持原样。
        val enableManualShift = settings?.enableManualShift ?: false
        val disableAutoGear = enableManualShift
        // 原生 TC/ABS 开关，默认 true。native 层据此打开玩家车的
        // tclEnable/absEnable，让游戏自带 TC/ABS 在非手柄模式下也生效。
        val enableTc = settings?.enableTc ?: true
        val enableAbs = settings?.enableAbs ?: true
        // ⚠️ enableUnlock 兜底：settings==null（context 还没可用，NPatch 下 onPackageReady
        // 早期常 context=null）时默认 true，不默认 false。
        // 根因：NPatch 启动慢，context 要 15s 才可用，但 BillingManager.Awake() 在 ~2s
        // 触发。如果默认 false，早期 unlock hooks 被跳过，hook_awake 赶不上 Awake，
        // native 主动注入 OnAlreadyOwned 的窗口被错过。vivo 等设备上游戏不主动调
        // Java checkOwned，只有 native 主动注入能解锁——错过 Awake = 解锁失败。
        // 配套 ModConfig.readFromTargetProcess 的 remoteJson==null fallback（强制
        // enableUnlock=true），两条路径一致：配置读不到时默认开 unlock。
        // 用户若真不想解锁，关掉开关 + 游戏在跑时改配置让广播写 local 即可覆盖
        // （广播路径 settings 非空，enableUnlock 按用户值走）。
        val enableUnlock = settings?.enableUnlock ?: true
        // 主菜单音乐替换开关，默认 false
        val enableMusicReplace = settings?.enableMusicReplace ?: false
        // V10 引擎声浪开关，默认 false
        val enableV10Sound = settings?.enableV10Sound ?: false

        // forceLoad + initUnlock（deferred 后同步执行，ShadowHook 已 init）
        logX(Log.INFO, TAG, "NPatch early unlock path: forceLoad + initUnlock (deferred)")
        if (enableUnlock) {
            try {
                if (!NativeBridge.isAvailable) {
                    NativeBridge.forceLoad(getAppContext())
                }
                if (NativeBridge.isAvailable) {
                    NativeBridge.initUnlock(
                        enableUnlock = true,
                        billingManagerAwake = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_AWAKE,
                        billingManagerGetInstance = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_GET_INSTANCE,
                        billingManagerInitializeBilling = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_INITIALIZE_BILLING,
                        billingManagerOnOwnedNone = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_ON_OWNED_NONE,
                        billingManagerOnPurchaseFailed = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_ON_PURCHASE_FAILED,
                        billingManagerSetUnlocked = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_SET_UNLOCKED,
                        billingManagerOnAlreadyOwned = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_ON_ALREADY_OWNED,
                        billingManagerIsUnlockedField = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_IS_UNLOCKED_FIELD,
                        billingManagerHasStoreConnectionField = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD,
                        billingManagerHasCompletedOwnershipCheckField = tools.alamobile.mod.offsets.OffsetTable.BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD
                    )
                    logX(Log.INFO, TAG, "Early unlock hooks installed (deferred, contextWasNull=${getAppContext() == null}, nativeAvail=${NativeBridge.isAvailable})")
                } else {
                    logX(Log.ERROR, TAG, "Early unlock: NativeBridge not available after forceLoad")
                }
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Early unlock hooks failed: ${e.message}")
            }
        } else {
            logX(Log.INFO, TAG, "enableUnlock=false, skipping early unlock hooks")
        }

        // ⚠️ V10 引擎声浪 intro hooks 早期安装——IntroLogoManager.Start() 在游戏启动
        // ~2s 内就触发，不等 15s 延迟，否则 hook 装上时开场已过。与 initUnlock 同理。
        // forceLoad 确保 native 库在当前 ClassLoader 可用。
        logX(Log.INFO, TAG, "NPatch early intro path: forceLoad + initIntro (deferred)")
        if (enableV10Sound) {
            try {
                if (!NativeBridge.isAvailable) {
                    NativeBridge.forceLoad(getAppContext())
                }
                if (NativeBridge.isAvailable) {
                    NativeBridge.initIntro(
                        enableV10 = true,
                        introLogoManagerStart = tools.alamobile.mod.offsets.OffsetTable.INTRO_LOGO_MANAGER_START,
                        audioSourceSetVolumeReal = tools.alamobile.mod.offsets.OffsetTable.AUDIO_SOURCE_SET_VOLUME_REAL
                    )
                    logX(Log.INFO, TAG, "Early intro hooks installed (deferred, nativeAvail=${NativeBridge.isAvailable})")
                } else {
                    logX(Log.ERROR, TAG, "Early intro: NativeBridge not available after forceLoad")
                }
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Early intro hooks failed: ${e.message}")
            }
        } else {
            logX(Log.INFO, TAG, "enableV10Sound=false, skipping early intro hooks")
        }

        // IntroSoundPlayer 也在早期初始化——开场在 ~2s 触发，轮询要尽快开始。
        val earlyCtx = getAppContext()
        if (enableV10Sound && earlyCtx != null) {
            try {
                IntroSoundPlayer.init(earlyCtx)
                IntroSoundPlayer.setEnabled(true)
                logX(Log.INFO, TAG, "IntroSoundPlayer early initialized, v10Enabled=true")
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "IntroSoundPlayer early init failed: ${e.message}")
            }
        }

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            // 双 ClassLoader 守卫：LSPosed 在共存版上注入两次模块，第一个
            // ClassLoader 装好 hook + overlay 后会 markNativeInstalled()。
            // 第二个 ClassLoader 跑到这里时直接跳过，避免：
            // 1) 重复装 overlay（两个 PedalOverlayView 同时写 IPC 文件）
            // 2) 第二个 .so 副本装 hook 失败但启动第二个 writer 线程
            if (isNativeInstalled()) {
                logX(Log.INFO, TAG, "Native already installed by another ClassLoader, skipping overlay+hooks")
                return@postDelayed
            }
            val ctx = getAppContext()
            logX(Log.INFO, TAG, "Delayed context: $ctx")

            if (ctx != null) {
                try {
                    val overlayManager = OverlayManager(ctx)
                    overlayManager.showOverlays()
                    logX(Log.INFO, TAG, "Overlay shown")
                } catch (e: Throwable) {
                    logX(Log.ERROR, TAG, "Failed to show overlay: ${e.message}")
                }
            }

            try {
                // Force-load native library in case ClassLoader isolation
                // prevented the standard System.loadLibrary from working.
                if (ctx != null) {
                    NativeBridge.forceLoad(ctx)
                }

                // ⚠️ 15s 延迟路径：one-shot 强制解锁兜底。
                // 在早期 unlock hooks 路径（hook_awake 赶不上 Awake）
                // 失败时，这里直接调 BillingManager.get_Instance() 获取
                // 单例，调 SetUnlocked(true) 解锁。不依赖 hook 触发时机。
                // 先试 unlock hooks 安装（如果早期没装上的话），再试 forceUnlockNow。
                if (NativeBridge.isAvailable) {
                    NativeBridge.initWithOffsets(
                        enableControlReplacement = enableControlReplacement,
                        enableAutoDRS = enableAutoDrs,
                        disableAutoGear = disableAutoGear,
                        enableUnlock = enableUnlock,
                        enableTc = enableTc,
                        enableAbs = enableAbs
                    )
                    // 主菜单音乐替换：native hooks 装好后初始化播放器。
                    // 提取 APK 内置 MP3 → 轮询主菜单状态 → 在主菜单播放。
                    // 开关由广播/初始化同步。
                    if (ctx != null) {
                        try {
                            MusicPlayer.init(ctx)
                            MusicPlayer.setEnabled(enableMusicReplace)
                            logX(Log.INFO, TAG, "MusicPlayer initialized, replaceEnabled=$enableMusicReplace")
                        } catch (e: Throwable) {
                            logX(Log.ERROR, TAG, "MusicPlayer init failed: ${e.message}")
                        }
                        try {
                            IntroSoundPlayer.init(ctx)
                            IntroSoundPlayer.setEnabled(enableV10Sound)
                            logX(Log.INFO, TAG, "IntroSoundPlayer initialized, v10Enabled=$enableV10Sound")
                        } catch (e: Throwable) {
                            logX(Log.ERROR, TAG, "IntroSoundPlayer init failed: ${e.message}")
                        }
                    }
                    // one-shot 强制解锁兜底：只在用户开了解锁开关时调。
                    // enableUnlock=false 时跳过——用户明确不想解锁，不能强制。
                    //（之前这里无条件调 forceUnlockNow，导致开关关了仍弹窗。）
                    if (enableUnlock) {
                        val unlocked = NativeBridge.forceUnlockNow()
                        logX(Log.INFO, TAG, "15s delay: forceUnlockNow returned $unlocked")
                    } else {
                        logX(Log.INFO, TAG, "15s delay: enableUnlock=false, skipping forceUnlockNow")
                    }
                } else {
                    logX(Log.WARN, TAG, "NativeBridge not available, skipping 15s init and unlock")
                }
                // native 真正装好之后才立标记 —— 在此之前不拦，确保
                // 第一个 ClassLoader 自己的 onPackageReady 流程不被自己拦掉
                markNativeInstalled()
                logX(Log.INFO, TAG, "Native hooks installed (isAvailable=${NativeBridge.isAvailable})")
            } catch (e: Throwable) {
                logX(Log.ERROR, TAG, "Failed to install native hooks: ${e.message}")
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
