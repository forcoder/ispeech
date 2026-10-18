package com.ispeech.settings.asr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.settings.store.AsrEngine
import com.ispeech.settings.store.AsrLanguage
import com.ispeech.settings.store.AsrSettings
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ASR 模型设置页界面状态。 */
data class AsrSettingsUiState(
    val loading: Boolean = true,
    val draft: AsrSettings = AsrSettings(),
    val saved: AsrSettings = AsrSettings(),
    val message: String? = null,
) {
    /** 草稿与已保存值不一致（用于「保存」按钮可用性）。 */
    val dirty: Boolean get() = draft != saved
}

/** ASR 模型设置页 ViewModel：读取、编辑并保存语音识别配置。 */
class AsrSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(AsrSettingsUiState())
    val uiState: StateFlow<AsrSettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 读取当前语音识别配置作为草稿与基准。 */
    fun load() {
        viewModelScope.launch {
            val asr = runCatching { SettingsStore.asrSettingsFlow(app).first() }
                .getOrNull()
                ?: AsrSettings()
            _uiState.update { it.copy(loading = false, draft = asr, saved = asr) }
        }
    }

    fun updateEngine(engine: AsrEngine) =
        _uiState.update { it.copy(draft = it.draft.copy(engine = engine)) }

    fun updateServiceComponent(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(serviceComponent = value)) }

    fun updateModel(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(model = value)) }

    fun updateLanguage(language: AsrLanguage) =
        _uiState.update { it.copy(draft = it.draft.copy(language = language)) }

    fun updatePartialResults(enabled: Boolean) =
        _uiState.update { it.copy(draft = it.draft.copy(partialResults = enabled)) }

    fun updatePreferOffline(enabled: Boolean) =
        _uiState.update { it.copy(draft = it.draft.copy(preferOffline = enabled)) }

    /** 保存草稿到 DataStore。 */
    fun save() {
        val draft = _uiState.value.draft
        viewModelScope.launch {
            runCatching { SettingsStore.setAsrSettings(app, draft) }
                .onSuccess {
                    _uiState.update {
                        it.copy(saved = draft, message = app.getString(R.string.asr_saved))
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            message = app.getString(
                                R.string.asr_save_failed,
                                throwable.message ?: "未知错误",
                            ),
                        )
                    }
                }
        }
    }

    /** 已消费提示信息（SnackBar 展示完毕）。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
