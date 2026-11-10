package com.ispeech.settings.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthState
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** csBaby 登录页界面状态。 */
data class CsBabyLoginUiState(
    val registerMode: Boolean = false,
    val identifier: String = "",
    val password: String = "",
    val name: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val success: CsBabyAuthState? = null,
)

/** csBaby 登录 / 注册 ViewModel：网络请求全部在 viewModelScope 内执行。 */
class CsBabyLoginViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(CsBabyLoginUiState())
    val uiState: StateFlow<CsBabyLoginUiState> = _uiState.asStateFlow()

    /** 切换登录 / 注册模式（保留已输入内容）。 */
    fun switchMode(register: Boolean) {
        _uiState.update { it.copy(registerMode = register, error = null) }
    }

    fun setIdentifier(value: String) {
        _uiState.update { it.copy(identifier = value, error = null) }
    }

    fun setPassword(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun setName(value: String) {
        _uiState.update { it.copy(name = value, error = null) }
    }

    /** 提交登录 / 注册；成功后写入 CsBabyAuthStore 并置 success 事件。 */
    fun submit() {
        val state = _uiState.value
        if (state.submitting) return
        if (state.identifier.isBlank()) {
            _uiState.update { it.copy(error = "请输入账号") }
            return
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(error = "请输入密码") }
            return
        }

        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val client = AppContainer.csBabyClient(app)
            runCatching {
                if (state.registerMode) {
                    client.register(
                        identifier = state.identifier.trim(),
                        password = state.password,
                        name = state.name.trim().ifBlank { null },
                    )
                } else {
                    client.login(
                        identifier = state.identifier.trim(),
                        password = state.password,
                    )
                }
            }.onSuccess { auth ->
                // 登录凭证统一由 CsBabyAuthStore 落盘
                CsBabyAuthStore.save(app, auth)
                _uiState.update { it.copy(submitting = false, success = auth) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        submitting = false,
                        error = app.getString(
                            R.string.csbaby_login_failed,
                            throwable.message ?: "未知错误",
                        ),
                    )
                }
            }
        }
    }

    /** 已消费错误提示（SnackBar 展示完毕）。 */
    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 已消费成功事件（页面已返回）。 */
    fun consumeSuccess() {
        _uiState.update { it.copy(success = null) }
    }
}
