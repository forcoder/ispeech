package com.ispeech.settings.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthState
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.data.SyncRepository
import com.ispeech.di.AppContainer
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
    val pushing: Boolean = false,
    val pushedCount: Int? = null,
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
                    // 同步写盘后让输入法重建引擎，远端短语无需重启输入法即可生效
                    AppContainer.invalidateEngine()
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

    /** 上传本地用户快捷短语到 csBaby 远端（双向同步 push）。 */
    fun pushNow() {
        val state = _uiState.value
        if (state.pushing) return
        if (state.auth == null) {
            _uiState.update { it.copy(message = app.getString(R.string.sync_need_login)) }
            return
        }
        _uiState.update { it.copy(pushing = true, message = null) }
        viewModelScope.launch {
            runCatching { SyncRepository.pushLocalRules(app) }
                .onSuccess { report ->
                    _uiState.update {
                        it.copy(
                            pushing = false,
                            pushedCount = report.pushed,
                            message = uploadMessage(report),
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            pushing = false,
                            message = app.getString(
                                R.string.sync_failed,
                                throwable.message ?: "未知错误",
                            ),
                        )
                    }
                }
        }
    }

    /** 上传结果文案：pending > 0 说明远端没生效（后端不支持修改 / 删除），如实说明。 */
    private fun uploadMessage(report: SyncRepository.RuleSyncReport): String =
        if (report.pending > 0) {
            app.getString(
                R.string.sync_upload_partial,
                report.created,
                report.updatedApplied,
                report.deletedApplied,
                report.pending,
            )
        } else {
            app.getString(R.string.sync_upload_success, report.pushed)
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
