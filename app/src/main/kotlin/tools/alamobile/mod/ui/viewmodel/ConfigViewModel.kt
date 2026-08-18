package tools.alamobile.mod.ui.viewmodel

import android.app.Application
import android.content.Context
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
                disableAutoGear = s.disableAutoGear,
                enableManualShift = s.enableManualShift,
                enableUnlock = s.enableUnlock,
                enableTc = s.enableTc,
                enableAbs = s.enableAbs,
                enableMusicReplace = s.enableMusicReplace,
                enableV10Sound = s.enableV10Sound,
                pedalDeadzone = s.pedalDeadzone,
                pedalTransition = s.pedalTransition,
                brakeTransition = s.brakeTransition,
                brakeInvert = s.brakeInvert,
                throttleCurve = s.throttleCurve,
                brakeCurve = s.brakeCurve,
                throttleCurvePoints = s.throttleCurvePoints,
                brakeCurvePoints = s.brakeCurvePoints,
                logEnabled = s.logEnabled,
            )
        }
    }

    private fun defaultUiState(): ConfigUiState {
        val s = ModConfig.defaultSettingsPublic()
        return ConfigUiState(
            pedalMode = s.pedalMode,
            enableAutoDrs = s.enableAutoDrs,
            disableAutoGear = s.disableAutoGear,
            enableManualShift = s.enableManualShift,
            enableUnlock = s.enableUnlock,
            enableTc = s.enableTc,
            enableAbs = s.enableAbs,
            enableMusicReplace = s.enableMusicReplace,
            enableV10Sound = s.enableV10Sound,
            pedalDeadzone = s.pedalDeadzone,
            pedalTransition = s.pedalTransition,
            brakeTransition = s.brakeTransition,
            brakeInvert = s.brakeInvert,
            throttleCurve = s.throttleCurve,
            brakeCurve = s.brakeCurve,
            throttleCurvePoints = s.throttleCurvePoints,
            brakeCurvePoints = s.brakeCurvePoints,
            logEnabled = s.logEnabled,
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
    fun setDisableAutoGear(v: Boolean) { _uiState.value = _uiState.value.copy(disableAutoGear = v); scheduleSave() }
    fun setEnableManualShift(v: Boolean) { _uiState.value = _uiState.value.copy(enableManualShift = v); scheduleSave() }
    fun setEnableUnlock(v: Boolean) { _uiState.value = _uiState.value.copy(enableUnlock = v); scheduleSave() }
    fun setEnableTc(v: Boolean) { _uiState.value = _uiState.value.copy(enableTc = v); scheduleSave() }
    fun setEnableAbs(v: Boolean) { _uiState.value = _uiState.value.copy(enableAbs = v); scheduleSave() }
    fun setEnableMusicReplace(v: Boolean) { _uiState.value = _uiState.value.copy(enableMusicReplace = v); scheduleSave() }
    fun setEnableV10Sound(v: Boolean) { _uiState.value = _uiState.value.copy(enableV10Sound = v); scheduleSave() }
    fun setPedalDeadzone(v: Float) { _uiState.value = _uiState.value.copy(pedalDeadzone = v); scheduleSave() }
    fun setPedalTransition(v: Float) { _uiState.value = _uiState.value.copy(pedalTransition = v); scheduleSave() }
    fun setBrakeTransition(v: Float) { _uiState.value = _uiState.value.copy(brakeTransition = v); scheduleSave() }
    fun setBrakeInvert(v: Boolean) { _uiState.value = _uiState.value.copy(brakeInvert = v); scheduleSave() }
    fun setThrottleCurve(v: ModConfig.PedalCurve) { _uiState.value = _uiState.value.copy(throttleCurve = v); scheduleSave() }
    fun setBrakeCurve(v: ModConfig.PedalCurve) { _uiState.value = _uiState.value.copy(brakeCurve = v); scheduleSave() }
    fun setThrottleCurvePoints(v: List<ModConfig.CurvePoint>) { _uiState.value = _uiState.value.copy(throttleCurvePoints = v); scheduleSave() }
    fun setBrakeCurvePoints(v: List<ModConfig.CurvePoint>) { _uiState.value = _uiState.value.copy(brakeCurvePoints = v); scheduleSave() }
    fun setLogEnabled(v: Boolean) { _uiState.value = _uiState.value.copy(logEnabled = v); scheduleSave() }

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
data class ConfigUiState(
    val pedalMode: ModConfig.PedalMode,
    val enableAutoDrs: Boolean,
    val disableAutoGear: Boolean,
    val enableManualShift: Boolean,
    val enableUnlock: Boolean,
    val enableTc: Boolean,
    val enableAbs: Boolean,
    val enableMusicReplace: Boolean,
    val enableV10Sound: Boolean,
    val pedalDeadzone: Float,
    val pedalTransition: Float,
    val brakeTransition: Float,
    val brakeInvert: Boolean,
    val throttleCurve: ModConfig.PedalCurve,
    val brakeCurve: ModConfig.PedalCurve,
    val throttleCurvePoints: List<ModConfig.CurvePoint>,
    val brakeCurvePoints: List<ModConfig.CurvePoint>,
    val logEnabled: Boolean,
) {
    fun toSettings(): ModConfig.Settings = ModConfig.Settings(
        pedalMode = pedalMode,
        enableAutoDrs = enableAutoDrs,
        disableAutoGear = disableAutoGear,
        enableManualShift = enableManualShift,
        enableUnlock = enableUnlock,
        enableTc = enableTc,
        enableAbs = enableAbs,
        enableMusicReplace = enableMusicReplace,
        enableV10Sound = enableV10Sound,
        pedalDeadzone = pedalDeadzone,
        pedalTransition = pedalTransition,
        brakeTransition = brakeTransition,
        brakeInvert = brakeInvert,
        throttleCurve = throttleCurve,
        brakeCurve = brakeCurve,
        throttleCurvePoints = throttleCurvePoints,
        brakeCurvePoints = brakeCurvePoints,
        logEnabled = logEnabled,
    )
}
