package com.ispeech.settings.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.data.AiLearningStore
import com.ispeech.settings.store.AiProvider
import com.ispeech.settings.store.AiSettings
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 建议设置页界面状态。 */
data class AiSettingsUiState(
    val loading: Boolean = true,
    val draft: AiSettings = AiSettings(),
    val saved: AiSettings = AiSettings(),
    /** 采纳率统计（生成次数 / 采纳次数）。 */
    val generated: Int = 0,
    val accepted: Int = 0,
    val message: String? = null,
) {
    /** 草稿与已保存值不一致（用于「保存」按钮可用性）。 */
    val dirty: Boolean get() = draft != saved

    /** 采纳率百分比文本，没有生成过时显示「—」。 */
    val adoptionText: String
        get() = if (generated <= 0) "—" else "${accepted * 100 / generated}%"
}

/** AI 建议设置页 ViewModel：读取 / 保存大模型配置，展示学习采纳率。 */
class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val learning = AiLearningStore(app)

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 读取当前配置与采纳率统计。 */
    fun load() {
        viewModelScope.launch {
            val ai = runCatching { SettingsStore.aiSettingsFlow(app).first() }
                .getOrNull() ?: AiSettings()
            // 直连来源要把预设值落到输入框里：用户要能看见默认 Base URL / 模型名并直接改，
            // 而不是对着空白框猜该填什么。saved 同样归一化，避免一进页面就判定为「有改动」。
            val normalized = normalize(ai)
            val stats = withContext(Dispatchers.IO) { learning.stats() }
            _uiState.update {
                it.copy(
                    loading = false,
                    draft = normalized,
                    saved = normalized,
                    generated = stats.generated,
                    accepted = stats.accepted,
                )
            }
        }
    }

    /** 空值回落到来源预设，让输入框始终有可见的默认值。 */
    private fun normalize(settings: AiSettings): AiSettings {
        if (!settings.provider.isDirect) return settings
        return settings.copy(
            baseUrl = settings.baseUrl.ifBlank { settings.provider.baseUrl },
            model = settings.model.ifBlank { settings.provider.defaultModel },
        )
    }

    /**
     * 切换模型来源。
     *
     * Base URL 与模型名换成新来源的预设值（用户之前自填的值丢弃），
     * 否则从 OpenAI 切到 DeepSeek 会带着 openai.com 的地址请求，直接 401。
     */
    fun updateProvider(provider: AiProvider) = _uiState.update {
        it.copy(
            draft = it.draft.copy(
                provider = provider,
                baseUrl = if (provider.isDirect) provider.baseUrl else "",
                model = if (provider.isDirect) provider.defaultModel else "",
            )
        )
    }

    fun updateBaseUrl(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(baseUrl = value)) }

    fun updateModel(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(model = value)) }

    fun updateApiKey(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(apiKey = value)) }

    fun updateLearning(enabled: Boolean) =
        _uiState.update { it.copy(draft = it.draft.copy(learning = enabled)) }

    /** 保存草稿。 */
    fun save() {
        val draft = _uiState.value.draft
        viewModelScope.launch {
            runCatching { SettingsStore.setAiSettings(app, draft) }
                .onSuccess {
                    _uiState.update { it.copy(saved = draft, message = "已保存") }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(message = "保存失败：${throwable.message ?: "未知错误"}") }
                }
        }
    }

    /** 清空本地学习数据与采纳率统计。 */
    fun clearLearning() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { learning.clear() }
            _uiState.update { it.copy(generated = 0, accepted = 0, message = "学习数据已清空") }
        }
    }

    /** 已消费提示信息。 */
    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}