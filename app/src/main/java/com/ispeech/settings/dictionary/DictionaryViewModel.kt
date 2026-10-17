package com.ispeech.settings.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.di.AppContainer
import com.ispeech.engine.WubiEngine
import com.ispeech.engine.WubiLocator
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 引擎与词典页界面状态。 */
data class DictionaryUiState(
    val statsLoading: Boolean = true,
    val entryCount: Int = 0,
    val codeCount: Int = 0,
    val singleCount: Int = 0,
    val phraseCount: Int = 0,
    val summary: String = "",
    val statsError: String? = null,
    val enableQuickPhrases: Boolean = true,
    val enableRemoteRules: Boolean = true,
    val singleLetterNoTrigger: Boolean = true,
)

/** 引擎与词典页 ViewModel：加载引擎统计 + 读写三个规则开关。 */
class DictionaryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    init {
        loadEngineStats()
        observeSwitches()
    }

    /** 解析内置五笔词典（assets/wubi86.dict），读取统计信息。 */
    private fun loadEngineStats() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = WubiLocator.openDict(app)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("未找到内置词典 assets/wubi86.dict")
                WubiEngine(text)
            }.onSuccess { engine ->
                _uiState.update {
                    it.copy(
                        statsLoading = false,
                        entryCount = engine.entryCount,
                        codeCount = engine.codeCount,
                        singleCount = engine.singleCount,
                        phraseCount = engine.phraseCount,
                        summary = engine.summary(),
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(statsLoading = false, statsError = throwable.message ?: "未知错误")
                }
            }
        }
    }

    /** 订阅三个规则开关（DataStore）。 */
    private fun observeSwitches() {
        viewModelScope.launch {
            combine(
                SettingsStore.enableQuickPhrasesFlow(app),
                SettingsStore.enableRemoteRulesFlow(app),
                SettingsStore.singleLetterNoTriggerFlow(app),
            ) { quickPhrases, remoteRules, singleLetterNoTrigger ->
                Triple(quickPhrases, remoteRules, singleLetterNoTrigger)
            }.collect { (quickPhrases, remoteRules, singleLetterNoTrigger) ->
                _uiState.update {
                    it.copy(
                        enableQuickPhrases = quickPhrases,
                        enableRemoteRules = remoteRules,
                        singleLetterNoTrigger = singleLetterNoTrigger,
                    )
                }
            }
        }
    }

    fun setEnableQuickPhrases(enabled: Boolean) {
        viewModelScope.launch {
            SettingsStore.setEnableQuickPhrases(app, enabled)
            AppContainer.invalidateEngine()
        }
    }

    fun setEnableRemoteRules(enabled: Boolean) {
        viewModelScope.launch {
            SettingsStore.setEnableRemoteRules(app, enabled)
            AppContainer.invalidateEngine()
        }
    }

    fun setSingleLetterNoTrigger(enabled: Boolean) {
        viewModelScope.launch {
            SettingsStore.setSingleLetterNoTrigger(app, enabled)
            AppContainer.invalidateEngine()
        }
    }
}
