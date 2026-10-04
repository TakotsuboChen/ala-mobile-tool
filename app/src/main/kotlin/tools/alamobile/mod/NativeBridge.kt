package tools.alamobile.mod

import tools.alamobile.mod.util.Logger
import android.content.Context
import tools.alamobile.mod.config.ModConfig
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
            Logger.i(TAG, "libala-core.so loaded via standard System.loadLibrary")
        } catch (e: UnsatisfiedLinkError) {
            Logger.w(TAG, "Standard loadLibrary failed (ClassLoader conflict): ${e.message?.take(80)}")
        } catch (e: Throwable) {
            Logger.e(TAG, "Unexpected error loading libala-core.so: ${e.message}")
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
            Logger.i(TAG, "forceLoad: extracting libala-core.so for classloader: ${NativeBridge::class.java.classLoader}")

            // 优先用 Context 拿模块 APK 路径（最可靠）。
            // Context 不可用时（NPatch 早期 onPackageReady context=null），
            // 从 ClassLoader 反射拿 APK 路径——NPatch 用 PathClassLoader 加载模块，
            // 其 dex path 含模块 APK 绝对路径。
            val apkPath = if (context != null) {
                context.packageManager.getApplicationInfo(MODULE_PKG, 0).sourceDir
            } else {
                findModuleApkPathFromClassLoader() ?: run {
                    Logger.e(TAG, "forceLoad: cannot find module APK path (context=null and ClassLoader reflection failed)")
                    return
                }
            }
            Logger.i(TAG, "forceLoad: module APK path = $apkPath")

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
            Logger.i(TAG, "forceLoad successful: ${tempLib.absolutePath}")
        } catch (e: Throwable) {
            Logger.e(TAG, "forceLoad failed: ${e.message}", e)
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
            Logger.w(TAG, "findModuleApkPathFromClassLoader failed: ${e.message}")
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
        roadForceAbsWriteOffset: Long,
        playerControlsUpdateOffset: Long,
        drsStateChanged: Long,
        manualDrsUsage: Long,
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

    /**
     * 自锁式超车按键 hooks 安装（不动 init() 参数签名，与 initLap/initIntro 同模式）。
     * hook HybridComponent.EnableOTK/DisableOTK —— 玩家 OTK 输入的唯一汇聚点
     *（屏幕按钮与手柄力学都经此），把「按住」改成「点一下切换」。游戏自己的
     * 合法性判定（ERS 是否解锁 / 电量是否够）完全不动。
     */
    @JvmStatic
    external fun initOvertake(
        enableLatchOvertake: Boolean,
        touchPressOtk: Long,
        touchReleaseOtk: Long,
        disableOtk: Long
    )

    /** 运行时开关自锁式超车按键（配置广播到达后调用，不重装 hook）。 */
    @JvmStatic
    external fun setOvertakeLatch(active: Boolean)

    /**
     * 计时赛有效圈速监听 hooks 安装（log-only，无 UI）。
     * - IRDSLevelLoadVariables.Awake：捕获 LLV 单例 → 读 trackToRace 赛道名
     *   （16 条 GP 赛道自动识别）。
     * - odometerHandler.HandleSectorsTimes：游戏自己的圈段事件（显式
     *   validLap + totalLapTime），维护会话最快有效圈。
     * 全部结果写入 native 日志（无条件写文件）。
     */
    @JvmStatic
    external fun initLap(
        irdsLevelLoadVariablesAwake: Long,
        odometerHandlerHandleSectorsTimes: Long
    )

    /**
     * 围场上传通道（S2）：轮询取走 order==2 有效圈事件（单槽，见 lap_hook.c）。
     * 有未消费事件返回 true，out 至少 9 元素，填充：
     * ```
     * [0] lapSeq（去重，单调递增）   [1] gpIndex∈[0,15]   [2] lapMs
     * [3] pedalMode  [4] tcMode  [5] tcStrength  [6] absMode  [7] absStrength
     * ```
     * 索引 3..7 是**整圈辅助配置码**（0 = 缺失）。非 0 表示该圈全程配置未变过，
     * 可安全作为成绩属性上报。消费成功后调 [markLapUploadConsumed]。
     */
    @JvmStatic
    external fun pollLapUpload(out: IntArray): Boolean

    /** native 单槽消费确认（与 [pollLapUpload] 配对）。 */
    @JvmStatic
    external fun markLapUploadConsumed(lapSeq: Int)

    /**
     * 推送当前辅助配置（模块启动 + 配置广播到达时）。native 只在**真正变化**时
     * 递增内部 epoch，故可重复调用；圈中途改过配置的圈会被判"不一致"记缺失。
     *
     * 码值 = [ModConfig.LapAssistCodes]（0 = 缺失/未知，非 0 为枚举序号 +1）。
     * 传 [ModConfig.LapAssistCodes.MISSING] 表示整维缺失。
     */
    @JvmStatic
    external fun setLapAssistConfig(
        pedalMode: Int,
        tcMode: Int,
        tcStrength: Int,
        absMode: Int,
        absStrength: Int
    )

    /**
     * [setLapAssistConfig] 的防御包装。**只接受原始配置五维**（不是可选类型）——
     * "缺失"由调用方决定不调用本函数表达（初始状态本就是缺失，且用户一旦配置
     * 过就不该回退成"缺失"；那种回退只能靠 [setLapAssistConfigMissing]）。
     */
    fun setLapAssistConfigSafe(cfg: ModConfig.LapAssistConfig) {
        if (!isAvailable) return
        try {
            val c = ModConfig.LapAssistCodes
            setLapAssistConfig(
                c.pedalCode(cfg.pedalMode),
                c.modeCode(cfg.tcCustom),
                cfg.tcStrengthCode,
                c.modeCode(cfg.absCustom),
                cfg.absStrengthCode,
            )
        } catch (e: Throwable) {
            Logger.w(TAG, "setLapAssistConfig failed", e)
        }
    }

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
     * 主动触发一次强制解锁，不依赖 hook 触发时机。
     * 在 15s 延迟路径中作为 one-shot 调用：通过 get_Instance() 获取 BillingManager
     * 单例指针，直接调 SetUnlocked(true) 解锁 + OnAlreadyOwned 辅助。
     * 返回 true 表示解锁执行成功，false 表示拿不到实例（可能 BillingManager 尚未初始化）。
     */
    @JvmStatic
    external fun forceUnlockNow(): Boolean

    /**
     * 查询 TC/ABS 介入指示灯信号（TcAbsIndicatorView 主线程 Handler 轮询，~60Hz）。
     * outTc/outAbs 各为 int[1]：介入电平（0/1，native 侧已合成 25Hz 闪烁
     * 相位——ABS=介入&&pulseBrakes 方波，TC=削减&&帧相位时钟）。
     * native 写侧在物理线程 hook 路径，读侧 volatile 无锁。
     */
    @JvmStatic
    external fun queryTcAbsIndicator(outTc: IntArray, outAbs: IntArray)

    /**
     * 滑移率反馈参数下发（滑移率反馈功能的振感/视觉开关 + 纵向满振点）。
     *
     * - enabled = 模式 ∈ {振感, 视觉, 全部}；关闭时不采样（native 侧电平恒 0）。
     * - fullZ：**纵向通道**的满振点。`z = max_i |σ_i| / maxSlip_i` 是"该轮此刻
     *   用了峰值的多少倍"（z = 1 即轮胎峰值），`z ≥ fullZ` 即满强度。
     *   正常恒为 [ModConfig.SLIP_FEEDBACK_FULL_Z]（= 10.0，即 σ 的物理饱和点）。
     *   **横向通道（α/maxAngle）硬封顶 0.25，不使用这个参数。**
     *
     * ⚠️ 2026-09-22 第八轮定案（**量纲闭合**，前七轮都栽在这里）。
     * 反汇编 `IRDSWheel.SlipRatio`(0x1a7da24) **全函数**后确认：
     *
     *     slipRatio(0x104) = min(1, ω_norm/8) · clamp(−LV.z / max(wMagnitud,1), ≥0)
     *
     * 即 `slipRatio` **本身就是那个函数的输出**，内部已经归一化过一次。
     * 所以旧版算的 `slipRatio / maxSlip` 是**双重归一化**（ψ(σ)/ψ_peak）：
     * 锁死只有 10、普通起步 3~7，两者差不到一个数量级 ⇒ 纵向永远拉不开档次，
     * 这才是「起步没打滑也满振」与「锁死振不强」这对矛盾的统一根因。
     *
     * 修法三条（都是结构性的，不依赖任何坐标系推断）：
     * 1. **分子改 `|σ|`、不除 `slipRatioClamp`** ⇒ 量程回到与 maxSlip 同量纲的
     *    0~10.6（σ 被 RoadForce 夹到 ±1、maxSlip≈0.094~0.103）。
     * 2. **锚点按实测分档重标定**（2026-10-02）：实测巡航 z≈0.02~0.17、
     *    普通过弯 0.88~2.40、极限过弯 3.9~7.7、锁死/空转 10.2~10.75。旧锚点
     *    （静默 0.40 / 膝盖 1.0 / 满振 3.5）把普通过弯全推到 0.2~0.7 电平上
     *    = 「低速拐弯太振」的量化根因。新锚点：静默 1.0、膝盖 4.0（= 1/4 电平）、
     *    满振 10.0（= 饱和点）。膝盖以内用幂次（`t^0.6`）保证小滑移连续可感，
     *    膝盖以后线性且斜率只降不升 ⇒ 用户否定过的「滑了就突然爆发」不可能。
     * 3. **横向硬封顶 1/4**（静默 1.5 / 封顶 4.0）⇒ `SlipAngle` 在低速的 atan
     *    饱和假象（分母被 `max(...,1)` 钳住，α 退化成 `atan(LV.x)`，含打舵
     *    本身的合法横向速度，实测达峰值 7 倍）再大也只是 1/4——恰好是用户对
     *    "用尽抓地力"的规格值。高速没这个假象，因为游戏自己的
     *    `LockSteerAtSlipAngle`(0x1a678c8) 不让打大舵角（这解释了「越低速越振」
     *    「高速打满方向一点都不振」）。
     *
     * 四条工况：锁死 → 纵向满振；出弯空转 → 纵向满振；高 G 过弯 → 横向 1/4；
     * 普通过弯 → 纵向 <0.2 且横向 <0.25 ⇒ 几乎无感。详见 native/src/slip_feedback.h。
     *
     * 静默点/膝盖处电平（1.0 / 4.0 / 0.25）是用户规格，写在 native 常量里，
     * 不在此下发。
     *
     * 低频调用（配置变更/启动时），不重装 hook，同 [setTcParams] 模式。
     */
    @JvmStatic
    external fun setSlipFeedbackParams(enabled: Boolean, fullZ: Float)

    /**
     * 标定探针开关：开启后 native 每约 3 秒往 `ala_tool_native.log` 落一组
     * 实测数据——逐轮原始 σ/α/峰值、两通道各自的峰值（maxLon/maxLat）、
     * 车速峰值、Fn、**游戏自己算的 `unitSlip`/`unitAngle`**（量纲外部校验
     * 锚点）。用于回归校验三条锚点：锁死/空转 maxLon≈10.5、极限过弯 maxLon≈4~8、
     * 普通过弯 maxLon≤2.4 且 maxLat≤2.7。自限时 10 分钟，到点自动关。
     *
     * 只在游戏进程有效（native 侧）。release 构建由 Java 侧显式调
     * （供需要实测标定的场合使用）。
     */
    @JvmStatic
    external fun setSlipFeedbackProbe(enabled: Boolean)

    /**
     * 查询滑移率反馈电平 0..1（[tools.alamobile.mod.overlay.SlipFeedbackView]
     * 主线程 Handler 轮询，~60Hz）。outLevel = float[1]，native 直写缓冲。
     * native 写侧在物理线程 carController 白名单路径，读侧 volatile 无锁。
     */
    @JvmStatic
    external fun querySlipFeedback(outLevel: FloatArray)

    /**
     * **按时间窗口批量取电平样本**（第九轮流式路径）。
     *
     * 物理帧 50Hz、Java 轮询 60Hz 不同步，只取"最新值"会读重/漏帧。本函数
     * 返回自 `fromSeq` 以来的全部样本（最多 `max` 个），并回传下一个待读序号。
     *
     * 用途是**流式重发振感**：每 20ms 取一次增量，N 个电平各对应一段波形，
     * 段序 = 物理帧序 ⇒ 幅度起伏 = 真实信号起伏，**没有人为调制频率**——
     * 这是唯一能做出"不糊"质感的路（人造调制频率受 LRA 机械带宽与 5ms 段长
     * 双重限制，能渲染出来的必然落在"波动/嗡"感知区，即用户说的"共振感"）。
     *
     * @param out     输出缓冲（调用方分配，长度 ≥ max）
     * @param max     最多取几个（native 侧上限 64）
     * @param fromSeq 上次返回的 outNext[0]（首次传 0 = 从当前开始）
     * @param outNext int[1]，回传下一个待读序号
     * @return 实际写入的样本数
     */
    @JvmStatic
    external fun drainSlipFeedback(
        out: FloatArray, max: Int, fromSeq: Int, outNext: IntArray
    ): Int

    /**
     * 初始化"隐藏游戏原生油门/刹车按钮"功能。
     * 启动 native 后台轮询线程，每 2 秒遍历 IRDSUIMobileControls 布局 GameObject
     * 子物体，按名字匹配 "Throttle"/"Brake" 并 SetActive(false)，跳过 "Clutch"。
     * enabled=false 时启动线程但不执行隐藏。
     * RVA 全部由 OffsetTable 注入（见 CLAUDE.md：native 不得硬编码 RVA）。
     */
    @JvmStatic
    external fun initHidePedals(
        enabled: Boolean,
        getInstanceOffset: Long,
        goSetActiveOffset: Long,
        goGetActiveSelfOffset: Long,
        goGetTransformOffset: Long,
        componentGetGameObjectOffset: Long,
        transformGetChildOffset: Long,
        transformGetChildCountOffset: Long,
        objectGetNameOffset: Long
    )

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
        try { setThrottle(value) } catch (e: Throwable) { Logger.w(TAG, "setThrottle failed", e) }
    }

    @JvmStatic
    fun setBrakeSafe(value: Float) {
        if (!isAvailable) return
        try { setBrake(value) } catch (e: Throwable) { Logger.w(TAG, "setBrake failed", e) }
    }

    @JvmStatic
    fun shiftUpSafe() {
        if (!isAvailable) return
        try { shiftUp() } catch (e: Throwable) { Logger.w(TAG, "shiftUp failed", e) }
    }

    @JvmStatic
    fun shiftDownSafe() {
        if (!isAvailable) return
        try { shiftDown() } catch (e: Throwable) { Logger.w(TAG, "shiftDown failed", e) }
    }

    @JvmStatic
    fun setDRSActiveSafe(active: Boolean) {
        if (!isAvailable) return
        try { setDRSActive(active) } catch (e: Throwable) { Logger.w(TAG, "setDRSActive failed", e) }
    }

    /**
     * 运行时同步「自锁式超车按键」开关（不重装 hook，同 [setDRSActiveSafe] 模式）。
     * hook 恒装上，开关在 native 回调内判定。
     */
    @JvmStatic
    fun setOvertakeLatchSafe(active: Boolean) {
        if (!isAvailable) return
        try { setOvertakeLatch(active) } catch (e: Throwable) { Logger.w(TAG, "setOvertakeLatch failed", e) }
    }

    /**
     * 自锁式超车按键 hooks 安装安全包装。native 不可用 / 加载失败时静默降级 ——
     * 等价于功能不生效（按钮保持原生自复位语义），不影响游戏。
     */
    @JvmStatic
    fun initOvertakeSafe(enableLatchOvertake: Boolean) {
        if (!isAvailable) return
        try {
            initOvertake(
                enableLatchOvertake = enableLatchOvertake,
                touchPressOtk = OffsetTable.ODOMETER_HANDLER_TOUCH_PRESS_OTK,
                touchReleaseOtk = OffsetTable.ODOMETER_HANDLER_TOUCH_RELEASE_OTK,
                disableOtk = OffsetTable.HYBRID_COMPONENT_DISABLE_OTK
            )
        } catch (e: Throwable) {
            Logger.w(TAG, "initOvertake failed", e)
        }
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
            Logger.w(TAG, "Native library not available, skipping initWithOffsets")
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
            OffsetTable.IRDS_WHEEL_ROADFORCE_ABS_WRITE,
            OffsetTable.IRDS_PLAYER_CONTROLS_UPDATE,
            OffsetTable.CAR_MODIFIER_ON_DRS_STATE_CHANGED,
            OffsetTable.CAR_MODIFIER_MANUAL_DRS_USAGE,
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
