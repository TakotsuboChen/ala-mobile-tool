package tools.alamobile.mod

import android.content.Context
import android.util.Log
import tools.alamobile.mod.offsets.OffsetTable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object NativeBridge {

    private const val TAG = "AlaMobileTool"
    private const val LIB_NAME = "ala-core"
    private const val MODULE_PKG = "tools.alamobile.mod"

    /**
     * Whether the native library is available for JNI calls in the current ClassLoader.
     *
     * In coexistence builds, LSPosed may use isolated ClassLoaders that each
     * load NativeBridge independently. The Android linker rejects loading the
     * same .so path twice. We work around this by extracting the .so to a
     * temp file and loading it with System.load(absolutePath).
     */
    @JvmStatic
    var isAvailable: Boolean = false
        private set

    init {
        // Try standard load first
        try {
            System.loadLibrary(LIB_NAME)
            isAvailable = true
            Log.i(TAG, "libala-core.so loaded via standard System.loadLibrary")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Standard loadLibrary failed (ClassLoader conflict): ${e.message?.take(80)}")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error loading libala-core.so: ${e.message}")
        }
    }

    /**
     * Force-loads the native library by extracting it to a temp file.
     *
     * Call this from BOTH AlaMobileModule.onPackageReady (LspModuleClassLoader)
     * AND OverlayManager (VectorModuleClassLoader) to ensure the JNI methods
     * are bound in every ClassLoader that uses NativeBridge.
     *
     * context==null 兜底：NPatch 早期 onPackageReady 时 context 常为 null，
     * 但 System.loadLibrary 在 NPatch 的隔离 ClassLoader 下可能失败
     *（vivo/OriginOS 上实测失败）。这时需要 forceLoad，但 forceLoad 原本
     * 依赖 Context 拿模块 APK 路径。改为先从 ClassLoader 反射拿 APK 路径
     *（NPatch 的 PathClassLoader 的 dex path 里含模块 APK 绝对路径），
     * 不依赖 Context。
     */
    @JvmStatic
    fun forceLoad(context: Context?) {
        if (isAvailable) return

        try {
            Log.i(TAG, "forceLoad: extracting libala-core.so for classloader: ${NativeBridge::class.java.classLoader}")

            // 优先用 Context 拿模块 APK 路径（最可靠）。
            // Context 不可用时（NPatch 早期 onPackageReady context=null），
            // 从 ClassLoader 反射拿 APK 路径——NPatch 用 PathClassLoader 加载模块，
            // 其 dex path 含模块 APK 绝对路径。
            val apkPath = if (context != null) {
                context.packageManager.getApplicationInfo(MODULE_PKG, 0).sourceDir
            } else {
                findModuleApkPathFromClassLoader() ?: run {
                    Log.e(TAG, "forceLoad: cannot find module APK path (context=null and ClassLoader reflection failed)")
                    return
                }
            }
            Log.i(TAG, "forceLoad: module APK path = $apkPath")

            val tempLib = File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp")),
                "libala-core-${System.currentTimeMillis()}.so")
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/lib${LIB_NAME}.so")
                    ?: throw IllegalStateException("lib/arm64-v8a/lib${LIB_NAME}.so not found in APK")

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(tempLib).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Load the temp file - linker treats it as a new library
            System.load(tempLib.absolutePath)
            isAvailable = true
            Log.i(TAG, "forceLoad successful: ${tempLib.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "forceLoad failed: ${e.message}", e)
        }
    }

    /**
     * 从 ClassLoader 反射拿模块 APK 路径。
     * NPatch 用 PathClassLoader（继承 BaseDexClassLoader）加载模块，
     * 其 pathList.dexElements[].path 含 APK 绝对路径。
     * 找到含 "tools.alamobile.mod" 的路径即为模块 APK。
     */
    /**
     * 解析模块 APK 的绝对路径。
     *
     * MusicPlayer 等组件需要从模块 APK 里直接读资源（raw/MP3），但游戏进程的
     * ClassLoader.getResourceAsStream 在 LSPosed/NPatch 下常找不到 APK 里的 raw
     * 资源（模块 ClassLoader 的 dexElements[].path 指向优化后的 dex 而非原 APK，
     * zip 查找落空——M23 真机"静音不播放"根因），必须拿 APK 绝对路径后自己解压。
     * 这与 forceLoad 提取 libala-core.so 是同一思路（M19 已实机验证）。
     *
     * 优先级：Context.getApplicationInfo（LSPosed 注入场景部分可用）→ ClassLoader
     * 反射（NPatch/LSPosed 的 PathClassLoader dexElements[].path 含模块 APK 绝对
     * 路径）→ 类 codeSource location（最后兜底，Android 上可能为 null）。
     */
    @JvmStatic
    fun resolveModuleApkPath(context: Context?): String? {
        if (context != null) {
            try {
                return context.packageManager.getApplicationInfo(MODULE_PKG, 0).sourceDir
            } catch (_: Throwable) {
                // Android 11+ 包可见性：游戏进程常查不到模块包，fallthrough 到反射
            }
        }
        findModuleApkPathFromClassLoader()?.let { return it }
        return try {
            val location = NativeBridge::class.java.protectionDomain?.codeSource?.location
                ?: return null
            val file = File(location.toURI())
            if (file.isFile && file.name.endsWith(".apk")) file.absolutePath else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findModuleApkPathFromClassLoader(): String? {
        return try {
            val cl = NativeBridge::class.java.classLoader ?: return null
            // BaseDexClassLoader.pathList (DexPathList)
            val pathListField = cl.javaClass.superclass?.getDeclaredField("pathList")
                ?: cl.javaClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return null
            // DexPathList.dexElements (Element[])
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val elements = dexElementsField.get(pathList) as? Array<Any> ?: return null
            for (element in elements) {
                // Element.dexFile (DexFile) or Element.path (String)
                val pathField = try {
                    element.javaClass.getDeclaredField("path")
                } catch (_: NoSuchFieldException) {
                    null
                }
                if (pathField != null) {
                    pathField.isAccessible = true
                    val path = pathField.get(element) as? String
                    if (path != null && path.contains(MODULE_PKG)) {
                        return path
                    }
                }
                // Fallback: Element.dexFile.fileName
                val dexFileField = try {
                    element.javaClass.getDeclaredField("dexFile")
                } catch (_: NoSuchFieldException) {
                    null
                }
                if (dexFileField != null) {
                    dexFileField.isAccessible = true
                    val dexFile = dexFileField.get(element) ?: continue
                    val fileNameField = try {
                        dexFile.javaClass.getDeclaredField("mFileName")
                    } catch (_: NoSuchFieldException) {
                        dexFile.javaClass.getDeclaredField("fileName")
                    }
                    fileNameField.isAccessible = true
                    val fileName = fileNameField.get(dexFile) as? String
                    if (fileName != null && fileName.contains(MODULE_PKG)) {
                        return fileName
                    }
                }
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "findModuleApkPathFromClassLoader failed: ${e.message}")
            null
        }
    }

    @JvmStatic
    external fun init(
        setThrottleInput: Long,
        setBrakeInput: Long,
        setClutchInput: Long,
        shiftUpOffset: Long,
        shiftDownOffset: Long,
        setGearOffset: Long,
        fixedUpdateOffset: Long,
        throttleField: Long,
        brakeField: Long,
        actualThrottleField: Long,
        actualBrakeField: Long,
        clutchField: Long,
        drivetrainGearField: Long,
        drivetrainFixedUpdateOffset: Long,
        drivetrainAutomaticField: Long,
        drivetrainDoGearShiftingOffset: Long,
        tractionFilterOffset: Long,
        handleAbsOffset: Long,
        playerControlsUpdateOffset: Long,
        drsToggle: Long,
        billingManagerAwake: Long,
        billingManagerGetInstance: Long,
        billingManagerInitializeBilling: Long,
        billingManagerOnOwnedNone: Long,
        billingManagerOnPurchaseFailed: Long,
        billingManagerSetUnlocked: Long,
        billingManagerOnAlreadyOwned: Long,
        billingManagerIsUnlockedField: Long,
        billingManagerHasStoreConnectionField: Long,
        billingManagerHasCompletedOwnershipCheckField: Long,
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean,
        enableUnlock: Boolean,
        enableTc: Boolean,
        enableAbs: Boolean,
        musicVolumeUpdate: Long,
        musicVolumeStart: Long,
        audioSourceSetVolume: Long,
        introLogoManagerStart: Long,
        audioSourceSetVolumeReal: Long
    )

    /**
     * 独立的 unlock hooks 早期安装——在 onPackageReady 早期调用，
     * 不等 15 秒延迟，让 hook_awake/hook_get_instance 能赶上 BillingManager 早期调用。
     */
    @JvmStatic
    external fun initUnlock(
        enableUnlock: Boolean,
        billingManagerAwake: Long,
        billingManagerGetInstance: Long,
        billingManagerInitializeBilling: Long,
        billingManagerOnOwnedNone: Long,
        billingManagerOnPurchaseFailed: Long,
        billingManagerSetUnlocked: Long,
        billingManagerOnAlreadyOwned: Long,
        billingManagerIsUnlockedField: Long,
        billingManagerHasStoreConnectionField: Long,
        billingManagerHasCompletedOwnershipCheckField: Long
    )

    /**
     * 独立的 intro hooks 早期安装——在 onPackageReady 早期调用，
     * 不等 15 秒延迟，让 IntroLogoManager.Start() hook 赶上开场动画。
     */
    @JvmStatic
    external fun initIntro(
        enableV10: Boolean,
        introLogoManagerStart: Long,
        audioSourceSetVolumeReal: Long
    )

    @JvmStatic
    external fun setThrottle(value: Float)

    @JvmStatic
    external fun setBrake(value: Float)

    @JvmStatic
    external fun setClutch(value: Float)

    @JvmStatic
    external fun shiftUp()

    @JvmStatic
    external fun shiftDown()

    @JvmStatic
    external fun setGear(gear: Int)

    @JvmStatic
    external fun setDRSActive(active: Boolean)

    @JvmStatic
    external fun setTcAbs(enableTc: Boolean, enableAbs: Boolean)

    // TC 档位（强度插值 mix ∈ [0,1]，0=关闭 1=游戏默认；时机 (eps, minspd)
    // 成对覆写 TCLSlip/TCLminSPD 字段，二者任一 ≤0 = 整对不写=游戏默认）。
    // 独立 setter——不动 init() 的参数签名，运行中改档经此即时生效。
    @JvmStatic
    external fun setTcParams(mix: Float, eps: Float, minspd: Float)

    // ABS 档位（干预强度 bOverride = pulse 释放深度 b 绝对值，<0 = 不覆写
    // =恢复捕获基线；制动压力 brakeScale = 刹车输入请求等比缩放（v5——
    // 同样行程的制动力 ×scale，tempBrakeF/F_base 内部曲线不碰），1.0 =
    // 原生（Java 侧已 clamp [0.5,1.0]）——与 ABS 模式/档位完全无关）。
    // mix ≤ 0 时忽略 b 覆写（关闭语义走 setTcAbs 的 enableAbs 通道）。
    // 独立 setter——不动 init() 的参数签名，运行中改档经此即时生效。
    @JvmStatic
    external fun setAbsParams(mix: Float, bOverride: Float, brakeScale: Float)

    @JvmStatic
    external fun setMusicReplace(enabled: Boolean)

    @JvmStatic
    external fun isMusicReplaceEnabled(): Boolean

    @JvmStatic
    external fun isInMainMenu(): Boolean

    // 设置 V10 引擎声浪开关（配置变更时调用）
    @JvmStatic
    external fun setV10Sound(enabled: Boolean)

    // 查询开场动画是否已开始（one-shot：返回并清零）
    @JvmStatic
    external fun isIntroStarted(): Boolean

    /**
     * 设置 native 层日志开关。
     * logcat 始终输出，文件写入受此开关控制。
     * 配置变更时由 ConfigReceiver / AlaMobileModule 调用。
     */
    @JvmStatic
    external fun setLogEnabled(enabled: Boolean)

    /**
     * 主动触发一次强制解锁，不依赖 hook 触发时机。
     * 在 15s 延迟路径中作为 one-shot 调用：通过 get_Instance() 获取 BillingManager
     * 单例指针，直接调 SetUnlocked(true) 解锁 + OnAlreadyOwned 辅助。
     * 返回 true 表示解锁执行成功，false 表示拿不到实例（可能 BillingManager 尚未初始化）。
     */
    @JvmStatic
    external fun forceUnlockNow(): Boolean

    /**
     * 初始化"隐藏游戏原生油门/刹车按钮"功能。
     * 启动 native 后台轮询线程，每 2 秒遍历 IRDSUIMobileControls 布局 GameObject
     * 子物体，按名字匹配 "Throttle"/"Brake" 并 SetActive(false)，跳过 "Clutch"。
     * enabled=false 时启动线程但不执行隐藏。
     */
    @JvmStatic
    external fun initHidePedals(enabled: Boolean)

    /**
     * 实时切换"隐藏游戏原生油门/刹车按钮"开关。
     * 配置广播到达游戏进程后由 ConfigReceiver 调用，无需重启游戏。
     */
    @JvmStatic
    external fun setHidePedalsEnabled(enabled: Boolean)

    /**
     * 从 Android 主线程（Java Handler.postDelayed）调用。
     * 补充 hide_pedals_tick——计时赛加载期间 proxy_player_controls_update
     * 调用频率极低，Java Handler 每 100ms 调用确保按钮及时隐藏。
     */
    @JvmStatic
    external fun hidePedalsApply()

    @JvmStatic
    fun setThrottleSafe(value: Float) {
        if (!isAvailable) return
        try { setThrottle(value) } catch (e: Throwable) { Log.w(TAG, "setThrottle failed", e) }
    }

    @JvmStatic
    fun setBrakeSafe(value: Float) {
        if (!isAvailable) return
        try { setBrake(value) } catch (e: Throwable) { Log.w(TAG, "setBrake failed", e) }
    }

    @JvmStatic
    fun shiftUpSafe() {
        if (!isAvailable) return
        try { shiftUp() } catch (e: Throwable) { Log.w(TAG, "shiftUp failed", e) }
    }

    @JvmStatic
    fun shiftDownSafe() {
        if (!isAvailable) return
        try { shiftDown() } catch (e: Throwable) { Log.w(TAG, "shiftDown failed", e) }
    }

    @JvmStatic
    fun setDRSActiveSafe(active: Boolean) {
        if (!isAvailable) return
        try { setDRSActive(active) } catch (e: Throwable) { Log.w(TAG, "setDRSActive failed", e) }
    }

    @JvmStatic
    fun initWithOffsets(
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean = false,
        enableUnlock: Boolean = false,
        enableTc: Boolean = true,
        enableAbs: Boolean = true,
        musicVolumeUpdate: Long = OffsetTable.HANDLE_MUSIC_VOLUME_UPDATE,
        musicVolumeStart: Long = OffsetTable.HANDLE_MUSIC_VOLUME_START,
        audioSourceSetVolume: Long = OffsetTable.AUDIO_SOURCE_SET_VOLUME,
        introLogoManagerStart: Long = OffsetTable.INTRO_LOGO_MANAGER_START,
        audioSourceSetVolumeReal: Long = OffsetTable.AUDIO_SOURCE_SET_VOLUME_REAL
    ) {
        if (!isAvailable) {
            Log.w(TAG, "Native library not available, skipping initWithOffsets")
            return
        }
        init(
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_BRAKE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SHIFT_UP,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN,
            OffsetTable.IRDS_DRIVETRAIN_SET_GEAR,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_FIXED_UPDATE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_ACTUAL_THROTTLE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_ACTUAL_BRAKE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_FIXED_UPDATE,
            OffsetTable.IRDS_DRIVETRAIN_AUTOMATIC_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_DO_GEAR_SHIFTING,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_TRACTION_FILTER,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_HANDLE_ABS,
            OffsetTable.IRDS_PLAYER_CONTROLS_UPDATE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE,
            OffsetTable.BILLING_MANAGER_AWAKE,
            OffsetTable.BILLING_MANAGER_GET_INSTANCE,
            OffsetTable.BILLING_MANAGER_INITIALIZE_BILLING,
            OffsetTable.BILLING_MANAGER_ON_OWNED_NONE,
            OffsetTable.BILLING_MANAGER_ON_PURCHASE_FAILED,
            OffsetTable.BILLING_MANAGER_SET_UNLOCKED,
            OffsetTable.BILLING_MANAGER_ON_ALREADY_OWNED,
            OffsetTable.BILLING_MANAGER_IS_UNLOCKED_FIELD,
            OffsetTable.BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD,
            OffsetTable.BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD,
            enableControlReplacement,
            enableAutoDRS,
            disableAutoGear,
            enableUnlock,
            enableTc,
            enableAbs,
            musicVolumeUpdate,
            musicVolumeStart,
            audioSourceSetVolume,
            introLogoManagerStart,
            audioSourceSetVolumeReal
        )
    }
}
