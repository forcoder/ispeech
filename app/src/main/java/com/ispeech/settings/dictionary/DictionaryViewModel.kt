package com.ispeech.settings.dictionary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.di.AppContainer
import com.ispeech.data.UserDictImportResult
import com.ispeech.data.UserDictMode
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
    val userWordCount: Int = 0,
    val lastImport: UserDictImportResult? = null,
    val importError: String? = null,
    val exportError: String? = null,
)

/** 引擎与词典页 ViewModel：加载引擎统计 + 读写三个规则开关。 */
class DictionaryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    init {
        loadEngineStats()
        observeSwitches()
        refreshUserWordCount()
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

    // —— 用户词库导入 / 导出（对齐搜狗词库导入导出；S2） ——

    /** 读取用户词库条数（初始化与导入 / 导出后刷新）。 */
    private fun refreshUserWordCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { AppContainer.userDict(app).all().size }.getOrDefault(0)
            _uiState.update { it.copy(userWordCount = count) }
        }
    }

    /**
     * 导入用户词库文本文件。
     *
     * [mode] 是「没有模式列的行」的归属（界面上分「导入五笔词库 / 导入拼音词库」两个入口）；
     * 行内自带 `wb` / `cn` 时以行为准。合并规则见 [com.ispeech.data.UserDictStore.importText]。
     */
    fun importUserWords(uri: Uri, mode: UserDictMode) {
        _uiState.update { it.copy(lastImport = null, importError = null, exportError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = app.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
                AppContainer.userDict(app).importText(text, mode)
            }.onSuccess { imported ->
                val count = runCatching { AppContainer.userDict(app).all().size }.getOrDefault(0)
                _uiState.update { it.copy(lastImport = imported, userWordCount = count) }
            }.onFailure { error ->
                _uiState.update { it.copy(importError = error.message ?: "导入失败") }
            }
        }
    }

    /** 导出用户词库为文本文件（磁盘格式，可再次导入）。 */
    fun exportUserWords(uri: Uri) {
        _uiState.update { it.copy(lastImport = null, importError = null, exportError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = AppContainer.userDict(app).exportText()
                app.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
            }.onFailure { error ->
                _uiState.update { it.copy(exportError = error.message ?: "导出失败") }
            }
        }
    }
}
