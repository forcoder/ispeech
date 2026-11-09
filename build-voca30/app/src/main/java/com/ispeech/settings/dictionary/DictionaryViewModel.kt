package com.ispeech.settings.dictionary

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.di.AppContainer
import com.ispeech.data.CellDictImportResult
import com.ispeech.data.CellDictInfo
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
    val cellDicts: List<CellDictInfo> = emptyList(),
    val lastCellImport: CellDictImportResult? = null,
    val cellDictError: String? = null,
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
        refreshCellDicts()
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

    // —— 分类词库（细胞词库）安装 / 启停 / 删除（S3） ——

    /** 读取已安装分类词库列表（初始化与每次增删 / 启停后刷新）。 */
    private fun refreshCellDicts() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { AppContainer.cellDict(app).list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(cellDicts = list) }
        }
    }

    /**
     * 安装分类词库文本文件（每行「编码 + 词条」，与用户词库同一格式）。
     *
     * 显示名取文件选择器给的文件名（去扩展名）；安装后词库默认启用，
     * 并立刻让查询引擎重建，输入法下一次查询就能用上新词。
     */
    fun addCellDict(uri: Uri) {
        _uiState.update { it.copy(lastCellImport = null, cellDictError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = app.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
                AppContainer.cellDict(app).add(cellDictName(uri), text)
            }.onSuccess { result ->
                AppContainer.invalidateCellDict()
                val list = runCatching { AppContainer.cellDict(app).list() }.getOrDefault(emptyList())
                _uiState.update { it.copy(cellDicts = list, lastCellImport = result) }
            }.onFailure { error ->
                _uiState.update { it.copy(cellDictError = error.message ?: "词库安装失败") }
            }
        }
    }

    /** 删除分类词库（正文 + 索引项），并让查询引擎重建。 */
    fun removeCellDict(id: String) {
        _uiState.update { it.copy(lastCellImport = null, cellDictError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AppContainer.cellDict(app).remove(id) }
                .onSuccess { AppContainer.invalidateCellDict() }
                .onFailure { error ->
                    _uiState.update { it.copy(cellDictError = error.message ?: "词库删除失败") }
                }
            val list = runCatching { AppContainer.cellDict(app).list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(cellDicts = list) }
        }
    }

    /** 启用 / 停用分类词库；引擎缓存即时失效，无需重启输入法。 */
    fun setCellDictEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AppContainer.cellDict(app).setEnabled(id, enabled) }
                .onSuccess { AppContainer.invalidateEngine() }
                .onFailure { error ->
                    _uiState.update { it.copy(cellDictError = error.message ?: "词库状态保存失败") }
                }
            val list = runCatching { AppContainer.cellDict(app).list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(cellDicts = list) }
        }
    }

    /** 词库显示名：优先取文件选择器给的文件名（去扩展名），取不到再用 URI 末段。 */
    private fun cellDictName(uri: Uri): String {
        val fromProvider = runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        return raw.substringBeforeLast('.', raw).trim()
    }
}
