package tools.alamobile.mod.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.config.ModConfig

/**
 * 全局共享的 [ConfigViewModel]（ConfigActivity 在 Activity 层创建并 provide）。
 *
 * ⚠️ **必须共享，不能让各页面各自 `viewModel<ConfigViewModel>()`**：
 * `ViewModelStoreNavEntryDecorator`（lifecycle-viewmodel-navigation3）给**每个
 * NavEntry** 一个独立的 `ViewModelStoreOwner`（字节码实证：
 * `rememberViewModelStoreOwner(entry.contentKey, storeProvider, savedStateRegistryOwner)`），
 * 所以配置 Hub 页与四个二级页若各自取 VM，会拿到**四份独立实例**：
 * ① 各自持有陈旧快照，二级页 A 的改动会随二级页 B 的 `scheduleSave()` 被整份覆盖；
 * ② 更隐蔽的丢数据路径——`scheduleSave()` 是 `delay(300)` 后写盘，而 VM 被 clear
 * 时 `viewModelScope` 会取消 pending job ⇒ **拖完滑条立刻返回，改动被取消丢失**。
 * 提到 Activity 层后 viewModelScope 随 Activity 存活，导航不再取消在途写入。
 */
val LocalConfigViewModel = staticCompositionLocalOf<ConfigViewModel> {
    error("LocalConfigViewModel not provided")
}

/**
 * 管理 [ModConfig] 业务设置（踏板/DRS/解锁/曲线等）。
 *
 * 照搬 KernelSU 各 page ViewModel 模式：
 * - [uiState] 是不可变快照，UI 只读 + 调 setter 方法
 * - setter 立即更新 [_uiState]，再 300ms debounce 写 [ModConfig.write]
 * - [ModConfig.write] 同步做 JSON 序列化 + Binder IPC + 文件写 + 广播，
 *   全部放 IO 线程，main looper 只负责调度
 *
 * 取代旧 [tools.alamobile.mod.ui.ConfigUiState]（16 个 MutableState 字段）：
 * 旧方案状态全靠 Compose 跟踪，无跨进程配置变更响应能力，且 debounced save
 * 散落在 composable 里。集中到 ViewModel 后 ConfigActivity 只需 collect + 传 actions。
 */
class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // 照搬 KernelSU SettingsViewModel 模式：初始值用 defaultSettings（纯内存，不阻塞），
    // 真实配置在 init block 里异步加载。ModConfig.read 做文件 IO + JSON 解析，
    // 在构造函数同步调用会阻塞主线程 10-50ms，导致冷启动首帧卡顿。
    private val _uiState = MutableStateFlow(defaultUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val s = ModConfig.read(context)
            _uiState.value = ConfigUiState(
                pedalMode = s.pedalMode,
                enableAutoDrs = s.enableAutoDrs,
                enableOvertakeLatch = s.enableOvertakeLatch,
                enableProtectNextLap = s.enableProtectNextLap,
                disableAutoGear = s.disableAutoGear,
                enableManualShift = s.enableManualShift,
                enableUnlock = s.enableUnlock,
                tcMode = s.tcMode,
                tcStrength = s.tcStrength,
                tcTiming = s.tcTiming,
                absMode = s.absMode,
                absStrength = s.absStrength,
                absPressure = s.absPressure,
                enableMusicReplace = s.enableMusicReplace,
                enableV10Sound = s.enableV10Sound,
                hideGamePedals = s.hideGamePedals,
                enableTcAbsIndicator = s.enableTcAbsIndicator,
                slipFeedbackMode = s.slipFeedbackMode,
                slipHapticIntensity = s.slipHapticIntensity,
                slipVisualOpacity = s.slipVisualOpacity,
                slipVisualStyle = s.slipVisualStyle,
                kerbHapticEnabled = s.kerbHapticEnabled,
                pedalDeadzone = s.pedalDeadzone,
                pedalTransition = s.pedalTransition,
                brakeTransition = s.brakeTransition,
                throttleTransition = s.throttleTransition,
                pedalPriority = s.pedalPriority,
                pedalInvert = s.pedalInvert,
                overlayAlpha = s.overlayAlpha,
                overlayBorderWidth = s.overlayBorderWidth,
                overlayCornerRadius = s.overlayCornerRadius,
                throttleCurve = s.throttleCurve,
                brakeCurve = s.brakeCurve,
                throttleCurvePoints = s.throttleCurvePoints,
                brakeCurvePoints = s.brakeCurvePoints,
                paddockServer = s.paddockServer,
            )
        }
    }

    private fun defaultUiState(): ConfigUiState {
        val s = ModConfig.defaultSettingsPublic()
        return ConfigUiState(
            pedalMode = s.pedalMode,
            enableAutoDrs = s.enableAutoDrs,
            enableOvertakeLatch = s.enableOvertakeLatch,
            enableProtectNextLap = s.enableProtectNextLap,
            disableAutoGear = s.disableAutoGear,
            enableManualShift = s.enableManualShift,
            enableUnlock = s.enableUnlock,
            tcMode = s.tcMode,
            tcStrength = s.tcStrength,
            tcTiming = s.tcTiming,
            absMode = s.absMode,
            absStrength = s.absStrength,
            absPressure = s.absPressure,
            enableMusicReplace = s.enableMusicReplace,
            enableV10Sound = s.enableV10Sound,
            hideGamePedals = s.hideGamePedals,
            enableTcAbsIndicator = s.enableTcAbsIndicator,
            slipFeedbackMode = s.slipFeedbackMode,
            slipHapticIntensity = s.slipHapticIntensity,
            kerbHapticEnabled = s.kerbHapticEnabled,
            pedalDeadzone = s.pedalDeadzone,
            pedalTransition = s.pedalTransition,
            brakeTransition = s.brakeTransition,
            throttleTransition = s.throttleTransition,
            pedalPriority = s.pedalPriority,
            pedalInvert = s.pedalInvert,
            overlayAlpha = s.overlayAlpha,
            overlayBorderWidth = s.overlayBorderWidth,
            overlayCornerRadius = s.overlayCornerRadius,
            throttleCurve = s.throttleCurve,
            brakeCurve = s.brakeCurve,
            throttleCurvePoints = s.throttleCurvePoints,
            brakeCurvePoints = s.brakeCurvePoints,
            slipVisualOpacity = s.slipVisualOpacity,
            slipVisualStyle = s.slipVisualStyle,
            paddockServer = s.paddockServer,
        )
    }

    /** 把当前 uiState 快照写回 ModConfig（300ms debounce）。 */
    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = _uiState.value
        saveJob = viewModelScope.launch {
            delay(300)
            withContext(Dispatchers.IO) {
                ModConfig.write(context, snapshot.toSettings())
            }
        }
    }

    // ── setters ── 照搬 KernelSU SettingsViewModel 的 onSetXxx 模式 ──

    fun setPedalMode(v: ModConfig.PedalMode) { _uiState.value = _uiState.value.copy(pedalMode = v); scheduleSave() }
    fun setEnableAutoDrs(v: Boolean) { _uiState.value = _uiState.value.copy(enableAutoDrs = v); scheduleSave() }
    fun setEnableOvertakeLatch(v: Boolean) { _uiState.value = _uiState.value.copy(enableOvertakeLatch = v); scheduleSave() }
    fun setEnableProtectNextLap(v: Boolean) { _uiState.value = _uiState.value.copy(enableProtectNextLap = v); scheduleSave() }
    fun setDisableAutoGear(v: Boolean) { _uiState.value = _uiState.value.copy(disableAutoGear = v); scheduleSave() }
    fun setEnableManualShift(v: Boolean) { _uiState.value = _uiState.value.copy(enableManualShift = v); scheduleSave() }
    fun setEnableUnlock(v: Boolean) { _uiState.value = _uiState.value.copy(enableUnlock = v); scheduleSave() }
    // TC 档位三 setter。enableTc 不再直接暴露——它是派生值
    //（DEFAULT 恒 true；CUSTOM 时 strength≠OFF），在 toSettings 里派生。
    fun setTcMode(v: ModConfig.TcMode) { _uiState.value = _uiState.value.copy(tcMode = v); scheduleSave() }
    fun setTcStrength(v: ModConfig.TcStrength) { _uiState.value = _uiState.value.copy(tcStrength = v); scheduleSave() }
    fun setTcTiming(v: ModConfig.TcTiming) { _uiState.value = _uiState.value.copy(tcTiming = v); scheduleSave() }
    // ABS 档位三 setter。enableAbs 不再直接暴露——它是派生值
    //（DEFAULT 恒 true；CUSTOM 时 strength≠OFF），在 toSettings 里派生。
    fun setAbsMode(v: ModConfig.AbsMode) { _uiState.value = _uiState.value.copy(absMode = v); scheduleSave() }
    fun setAbsStrength(v: ModConfig.AbsStrength) { _uiState.value = _uiState.value.copy(absStrength = v); scheduleSave() }
    fun setAbsPressure(v: Float) { _uiState.value = _uiState.value.copy(absPressure = v); scheduleSave() }
    fun setEnableMusicReplace(v: Boolean) { _uiState.value = _uiState.value.copy(enableMusicReplace = v); scheduleSave() }
    fun setEnableV10Sound(v: Boolean) { _uiState.value = _uiState.value.copy(enableV10Sound = v); scheduleSave() }
    fun setHideGamePedals(v: Boolean) { _uiState.value = _uiState.value.copy(hideGamePedals = v); scheduleSave() }
    fun setEnableTcAbsIndicator(v: Boolean) { _uiState.value = _uiState.value.copy(enableTcAbsIndicator = v); scheduleSave() }
    // 滑移率反馈三 setter。起振点与强度都是"只在对应模式下才展示"的
    // 从属项——切模式不清它们，用户来回切回来保留上次调整值。
    fun setSlipFeedbackMode(v: ModConfig.SlipFeedbackMode) { _uiState.value = _uiState.value.copy(slipFeedbackMode = v); scheduleSave() }
    fun setSlipHapticIntensity(v: Int) { _uiState.value = _uiState.value.copy(slipHapticIntensity = v.coerceIn(20, 100)); scheduleSave() }
    fun setSlipVisualOpacity(v: Int) { _uiState.value = _uiState.value.copy(slipVisualOpacity = v.coerceIn(20, 100)); scheduleSave() }
    // 路肩振感反馈：开关 + 强度。与抓地力反馈同构——切开关不清强度，
    // 用户关掉再开回来保留上次调整值。
    fun setKerbHapticEnabled(v: Boolean) { _uiState.value = _uiState.value.copy(kerbHapticEnabled = v); scheduleSave() }
    fun setPedalDeadzone(v: Float) { _uiState.value = _uiState.value.copy(pedalDeadzone = v); scheduleSave() }
    fun setPedalTransition(v: Float) { _uiState.value = _uiState.value.copy(pedalTransition = v); scheduleSave() }
    fun setBrakeTransition(v: Float) { _uiState.value = _uiState.value.copy(brakeTransition = v); scheduleSave() }
    fun setThrottleTransition(v: Float) { _uiState.value = _uiState.value.copy(throttleTransition = v); scheduleSave() }
    fun setPedalPriority(v: ModConfig.PedalPriority) { _uiState.value = _uiState.value.copy(pedalPriority = v); scheduleSave() }
    fun setPedalInvert(v: ModConfig.PedalInvert) { _uiState.value = _uiState.value.copy(pedalInvert = v); scheduleSave() }
    fun setOverlayAlpha(v: Float) { _uiState.value = _uiState.value.copy(overlayAlpha = v); scheduleSave() }
    fun setOverlayBorderWidth(v: Float) { _uiState.value = _uiState.value.copy(overlayBorderWidth = v); scheduleSave() }
    fun setOverlayCornerRadius(v: Float) { _uiState.value = _uiState.value.copy(overlayCornerRadius = v); scheduleSave() }
    fun setThrottleCurve(v: ModConfig.PedalCurve) { _uiState.value = _uiState.value.copy(throttleCurve = v); scheduleSave() }
    fun setBrakeCurve(v: ModConfig.PedalCurve) { _uiState.value = _uiState.value.copy(brakeCurve = v); scheduleSave() }
    fun setThrottleCurvePoints(v: List<ModConfig.CurvePoint>) { _uiState.value = _uiState.value.copy(throttleCurvePoints = v); scheduleSave() }
    fun setBrakeCurvePoints(v: List<ModConfig.CurvePoint>) { _uiState.value = _uiState.value.copy(brakeCurvePoints = v); scheduleSave() }
    /** 围场服务器地址覆盖（S4）。空串 = 恢复内置默认。下次游戏进程启动生效。 */
    fun setPaddockServer(v: String) {
        _uiState.value = _uiState.value.copy(paddockServer = v.trim())
        scheduleSave()
    }

    /** 立即 flush（供 onServiceBind 等需要立刻落盘的场景）。 */
    fun flushNow() {
        saveJob?.cancel()
        val snapshot = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            ModConfig.write(context, snapshot.toSettings())
        }
    }
}

/**
 * 不可变 UI 状态快照。@Stable 不需要——data class 全部是 val + 基本类型/枚举，
 * Compose 编译器自动判定为 stable。
 */
data class ConfigUiState(    val pedalMode: ModConfig.PedalMode,
    val enableAutoDrs: Boolean,
    /** 自锁式超车按键：OTK 按钮由「按住」改为「点一下切换」。 */
    val enableOvertakeLatch: Boolean,
    /** 禁止删除下一圈成绩：赛道限制作废圈速时只删本圈。 */
    val enableProtectNextLap: Boolean,
    val disableAutoGear: Boolean,
    val enableManualShift: Boolean,
    val enableUnlock: Boolean,
    val tcMode: ModConfig.TcMode,
    val tcStrength: ModConfig.TcStrength,
    val tcTiming: ModConfig.TcTiming,
    val absMode: ModConfig.AbsMode,
    val absStrength: ModConfig.AbsStrength,
    // 制动压力（v6：踏板行程重映射标尺，1.0 = 原生），与 ABS 设置无关。
    val absPressure: Float,
    val enableMusicReplace: Boolean,
    val enableV10Sound: Boolean,
    val hideGamePedals: Boolean,
    // TC/ABS 介入指示灯开关。
    val enableTcAbsIndicator: Boolean,
    // 滑移率反馈：模式 / 最大振动强度百分比。
    val slipFeedbackMode: ModConfig.SlipFeedbackMode,
    val slipHapticIntensity: Int,
    val slipVisualOpacity: Int,
    val slipVisualStyle: ModConfig.SlipVisualStyle,
    // 路肩振感反馈：**只有开关**（默认关，2026-10-05 定案）。
    val kerbHapticEnabled: Boolean,
    val pedalDeadzone: Float,
    val pedalTransition: Float,
    val brakeTransition: Float,
    val throttleTransition: Float,
    val pedalPriority: ModConfig.PedalPriority,
    val pedalInvert: ModConfig.PedalInvert,
    val overlayAlpha: Float,
    val overlayBorderWidth: Float,
    val overlayCornerRadius: Float,
    val throttleCurve: ModConfig.PedalCurve,
    val brakeCurve: ModConfig.PedalCurve,
    val throttleCurvePoints: List<ModConfig.CurvePoint>,
    val brakeCurvePoints: List<ModConfig.CurvePoint>,
    // 围场服务器地址覆盖（空 = PaddockClient 内置默认）。
    val paddockServer: String,
) {
    fun toSettings(): ModConfig.Settings = ModConfig.Settings(
        pedalMode = pedalMode,
        enableAutoDrs = enableAutoDrs,
        enableOvertakeLatch = enableOvertakeLatch,
        enableProtectNextLap = enableProtectNextLap,
        disableAutoGear = disableAutoGear,
        enableManualShift = enableManualShift,
        enableUnlock = enableUnlock,
        // enableTc 派生：游戏默认恒开；自定义时强度=关闭才关（旧"TC 开关关闭"
        // 语义）。native 侧 mix<=0 分支与 enableTc=false 双保险，行为一致。
        enableTc = tcMode == ModConfig.TcMode.DEFAULT || tcStrength != ModConfig.TcStrength.OFF,
        // enableAbs 派生：与 enableTc 同构。旧"ABS 开关关闭"语义 = 自定义+关闭档。
        enableAbs = absMode == ModConfig.AbsMode.DEFAULT || absStrength != ModConfig.AbsStrength.OFF,
        tcMode = tcMode,
        tcStrength = tcStrength,
        tcTiming = tcTiming,
        absMode = absMode,
        absStrength = absStrength,
        absPressure = absPressure,
        enableMusicReplace = enableMusicReplace,
        enableV10Sound = enableV10Sound,
        hideGamePedals = hideGamePedals,
        enableTcAbsIndicator = enableTcAbsIndicator,
        slipFeedbackMode = slipFeedbackMode,
        slipHapticIntensity = slipHapticIntensity,
        slipVisualOpacity = slipVisualOpacity,
        slipVisualStyle = slipVisualStyle,
        kerbHapticEnabled = kerbHapticEnabled,
        pedalDeadzone = pedalDeadzone,
        pedalTransition = pedalTransition,
        brakeTransition = brakeTransition,
        throttleTransition = throttleTransition,
        pedalPriority = pedalPriority,
        pedalInvert = pedalInvert,
        overlayAlpha = overlayAlpha,
        overlayBorderWidth = overlayBorderWidth,
        overlayCornerRadius = overlayCornerRadius,
        throttleCurve = throttleCurve,
        brakeCurve = brakeCurve,
        throttleCurvePoints = throttleCurvePoints,
        brakeCurvePoints = brakeCurvePoints,
        paddockServer = paddockServer,
    )
}
