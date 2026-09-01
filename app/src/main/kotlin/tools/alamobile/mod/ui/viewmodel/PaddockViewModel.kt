package tools.alamobile.mod.ui.viewmodel

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient

/**
 * 围场页状态与动作。登录/注册走 PaddockClient（阻塞 IO → Dispatchers.IO）。
 * AndroidViewModel(application) 与 ConfigViewModel 同模式拿 Context。
 *
 * 提示出口：一次性事件走 [toast] SharedFlow（UI 层 collect 后 Toast 展示），
 * 不再在页面上常驻 message 卡片。
 */
class PaddockViewModel(application: android.app.Application) : AndroidViewModel(application) {

    data class UiState(
        val loggedIn: Boolean = false,
        val username: String = "",
        val userId: String = "",          // 服务端 user_id（头像 URL 用）
        val regSeq: Long = 0,            // 车手 ID（服务端 login 响应 reg_seq）
        val needsAvatar: Boolean = false, // 注册后首次登录 → 引导上传头像
        val loading: Boolean = false,
        // 登录表单（注册共用：新流程注册=用户名+密码+弹窗复制指令，登录=同名同密直登）
        val loginName: String = "",
        val loginPass: String = "",
        // 注册弹窗（OverlayDialog 常驻组合树）：非空 = 展示申请指令
        val regDialogCommand: String = "",
        // 忘记密码表单（S4：群里找 bot 要重置码 → 回填码+新密码）
        val showReset: Boolean = false,
        val resetCode: String = "",
        val resetPass: String = "",
    )

    private val _uiState = MutableStateFlow(UiState(loggedIn = PaddockClient.hasToken()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 一次性提示事件（错误/成功文案），UI collect 后 Toast。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    fun setLoginName(v: String) = _uiState.update { it.copy(loginName = v) }
    fun setLoginPass(v: String) = _uiState.update { it.copy(loginPass = v) }
    fun setResetCode(v: String) = _uiState.update { it.copy(resetCode = v) }
    fun setResetPass(v: String) = _uiState.update { it.copy(resetPass = v) }

    fun login() {
        val s = _uiState.value
        if (s.loading) return
        if (s.loginName.isBlank() || s.loginPass.isBlank()) {
            _toast.tryEmit("请输入用户名和密码")
            return
        }
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                PaddockClient.login(s.loginName.trim(), s.loginPass)
            }
            _uiState.update {
                when {
                    result.ok -> it.copy(
                        loading = false, loggedIn = true,
                        username = s.loginName.trim(),
                        userId = result.userId,
                        regSeq = result.regSeq,
                        needsAvatar = result.needsAvatar,
                    )
                    else -> it.copy(loading = false)
                }
            }
            _toast.tryEmit(if (result.ok) "登录成功" else result.message)
        }
    }

    /**
     * 注册：用户名+密码 → 服务端生成 pending 会话（哈希密码+发号）→ 弹窗展示申请指令。
     * 群内 bot 校验成功即建号；之后用户回模块用同一账号密码直接登录。
     */
    fun register() {
        val s = _uiState.value
        if (s.loading) return
        if (s.loginName.isBlank() || s.loginPass.isBlank()) {
            _toast.tryEmit("请输入用户名和密码")
            return
        }
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                PaddockClient.registerRequest(s.loginName.trim(), s.loginPass)
            }
            _uiState.update {
                if (ok) it.copy(loading = false, regDialogCommand = msg)
                else it.copy(loading = false)
            }
            if (!ok) _toast.tryEmit(msg)
        }
    }

    /** 注册弹窗关闭（onDismissFinished 里清理；复制指令在 UI 层完成）。 */
    fun dismissRegDialog() = _uiState.update { it.copy(regDialogCommand = "") }

    fun logout() {
        PaddockClient.clearAuth()
        _uiState.update { UiState() }
    }

    fun markAvatarDone() = _uiState.update { it.copy(needsAvatar = false) }

    // ── 忘记密码（S4）──────────────────────────────────────

    fun setShowReset(v: Boolean) = _uiState.update { it.copy(showReset = v) }

    fun submitReset() {
        val s = _uiState.value
        if (s.loading) return
        if (s.resetCode.isBlank() || s.resetPass.isBlank()) {
            _toast.tryEmit("请填写重置码和新密码")
            return
        }
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                PaddockClient.resetByCode(s.resetCode, s.resetPass)
            }
            _uiState.update {
                if (err == null) it.copy(loading = false, showReset = false, resetCode = "", resetPass = "")
                else it.copy(loading = false)
            }
            _toast.tryEmit(err ?: "密码已重置，请用新密码登录")
        }
    }
}