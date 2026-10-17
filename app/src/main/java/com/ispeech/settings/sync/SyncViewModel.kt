package com.ispeech.settings.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthState
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** csBaby 同步页界面状态。 */
data class SyncUiState(
    val authLoading: Boolean = true,
    val auth: CsBabyAuthState? = null,
    val syncing: Boolean = false,
    val syncedCount: Int? = null,
    val message: String? = null,
)

/** csBaby 同步页 ViewModel：whoami / 立即同步 / 登出。 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        refreshAuth()
    }

    /** 刷新本地登录态。 */
    fun refreshAuth() {
        viewModelScope.launch {
            val auth = runCatching { CsBabyAuthStore.load(app) }.getOrNull()
            _uiState.update { it.copy(auth = auth, authLoading = false) }
        }
    }

    /** 立即同步：拉取远端规则并写入本地远程规则文件。 */
    fun syncNow() {
        val state = _uiState.value
        if (state.syncing) return
        if (state.auth == null) {
            _uiState.update { it.copy(message = app.getString(R.string.sync_need_login)) }
            return
        }
        _uiState.update { it.copy(syncing = true, message = null) }
        viewModelScope.launch {
            runCatching { SyncRepository.pullAndWriteRemoteRules(app) }
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(
                            syncing = false,
                            syncedCount = count,
                            message = app.getString(R.string.sync_success, count),
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            syncing = false,
                            message = app.getString(
                                R.string.sync_failed,
                                throwable.message ?: "未知错误",
                            ),
                        )
                    }
                }
        }
    }

    /** 登出：清空本地登录凭证。 */
    fun logout() {
        viewModelScope.launch {
            runCatching { CsBabyAuthStore.clear(app) }
            _uiState.update { it.copy(auth = null, syncedCount = null, message = "已退出登录") }
        }
    }

    /** 已消费提示信息（SnackBar 展示完毕）。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
