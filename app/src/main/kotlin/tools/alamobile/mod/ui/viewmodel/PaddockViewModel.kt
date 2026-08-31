package tools.alamobile.mod.ui.viewmodel

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient

/**
 * 围场页状态与动作。登录/注册走 PaddockClient（阻塞 IO → Dispatchers.IO）。
 * AndroidViewModel(application) 与 ConfigViewModel 同模式拿 Context。
 */
class PaddockViewModel(application: android.app.Application) : AndroidViewModel(application) {

    data class UiState(
        val loggedIn: Boolean = false,
        val username: String = "",
        val loading: Boolean = false,
        val message: String = "",
        // 登录表单
        val loginName: String = "",
        val loginPass: String = "",
        // 注册表单
        val regName: String = "",
        val regPass: String = "",
        val regCodeIssued: String = "",   // 非空 = 已申请待群内校验（弹窗展示用）
        val regCodeInput: String = "",    // "我已校验" 后回填的码
    )

    private val _uiState = MutableStateFlow(UiState(loggedIn = PaddockClient.hasToken()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setLoginName(v: String) = _uiState.update { it.copy(loginName = v, message = "") }
    fun setLoginPass(v: String) = _uiState.update { it.copy(loginPass = v, message = "") }
    fun setRegName(v: String) = _uiState.update { it.copy(regName = v, message = "") }
    fun setRegPass(v: String) = _uiState.update { it.copy(regPass = v, message = "") }
    fun setRegCodeInput(v: String) = _uiState.update { it.copy(regCodeInput = v, message = "") }

    fun login() {
        val s = _uiState.value
        if (s.loading || s.loginName.isBlank() || s.loginPass.isBlank()) return
        _uiState.update { it.copy(loading = true, message = "") }
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                PaddockClient.login(s.loginName.trim(), s.loginPass)
            }
            _uiState.update {
                if (ok) it.copy(loading = false, loggedIn = true, username = s.loginName.trim())
                else it.copy(loading = false, message = msg)
            }
        }
    }

    /** 注册第一步：申请校验码（服务端生成 pending 会话）。 */
    fun requestRegCode() {
        val s = _uiState.value
        if (s.loading || s.regName.isBlank()) return
        _uiState.update { it.copy(loading = true, message = "") }
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                PaddockClient.registerRequest(s.regName.trim())
            }
            _uiState.update {
                if (ok) it.copy(loading = false, regCodeIssued = msg)
                else it.copy(loading = false, message = msg)
            }
        }
    }

    /** 注册第二步：群内校验成功后提交 verify（成功即自动登录）。 */
    fun verifyRegCode() {
        val s = _uiState.value
        if (s.loading || s.regCodeInput.isBlank() || s.regPass.isBlank()) {
            _uiState.update { it.copy(message = "请填写校验码和密码") }
            return
        }
        _uiState.update { it.copy(loading = true, message = "") }
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                PaddockClient.registerVerify(s.regCodeInput.trim(), s.regName.trim(), s.regPass)
            }
            _uiState.update {
                if (ok) it.copy(loading = false, loggedIn = true, username = s.regName.trim(), regCodeIssued = "")
                else it.copy(loading = false, message = msg)
            }
        }
    }

    fun logout() {
        PaddockClient.clearAuth()
        _uiState.update { UiState() }
    }
}