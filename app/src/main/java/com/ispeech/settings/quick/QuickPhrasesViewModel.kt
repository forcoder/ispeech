package com.ispeech.settings.quick

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.di.AppContainer
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 快捷短语页界面状态。 */
data class QuickPhrasesUiState(
    val loading: Boolean = true,
    val entries: List<QuickEntry> = emptyList(),
    val dirty: Boolean = false,
    val tip: String? = null,
)

/**
 * 快捷短语页 ViewModel。
 *
 * 读写 filesDir/quick_phrases_user.txt（见 AppContainer.quickPhrasesUserFile），
 * 文件格式与引擎 QuickPhraseEngine 一致：每行 tab 分隔
 * trigger / label / content / mode / weight / kind。
 */
class QuickPhrasesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(QuickPhrasesUiState())
    val uiState: StateFlow<QuickPhrasesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 读取用户文件；文件不存在时展示空列表。 */
    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = runCatching {
                val file = AppContainer.quickPhrasesUserFile(app)
                if (file.exists()) QuickPhraseCodec.parse(file.readText()) else emptyList()
            }.getOrElse { emptyList() }
            _uiState.update { it.copy(entries = entries, loading = false) }
        }
    }

    /** 新增或按触发词覆盖同一规则。 */
    fun upsert(entry: QuickEntry) {
        _uiState.update { state ->
            val list = state.entries.toMutableList()
            val index = list.indexOfFirst { it.trigger == entry.trigger }
            if (index >= 0) list[index] = entry else list.add(entry)
            state.copy(
                entries = list,
                dirty = true,
                tip = "已更新「${entry.trigger}」，点击右上角「保存」写入文件",
            )
        }
    }

    /** 删除指定触发词的规则。 */
    fun remove(entry: QuickEntry) {
        _uiState.update { state ->
            state.copy(
                entries = state.entries.filterNot { it.trigger == entry.trigger },
                dirty = true,
                tip = "已删除「${entry.trigger}」，点击右上角「保存」写入文件",
            )
        }
    }

    /** 保存当前列表到 filesDir/quick_phrases_user.txt。 */
    fun save() {
        val entries = _uiState.value.entries
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = AppContainer.quickPhrasesUserFile(app)
                file.parentFile?.mkdirs()
                file.writeText(QuickPhraseCodec.format(entries))
            }.onSuccess {
                AppContainer.invalidateEngine()
                _uiState.update {
                    it.copy(
                        dirty = false,
                        tip = "${app.getString(R.string.quick_saved)}：共 ${entries.size} 条规则",
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(tip = "保存失败：${throwable.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 已消费提示信息。 */
    fun consumeTip() {
        _uiState.update { it.copy(tip = null) }
    }
}

/**
 * 快捷规则文本编解码：格式与 assets/quick_phrases.txt、
 * QuickPhraseEngine.fromText 完全兼容。
 */
internal object QuickPhraseCodec {

    private const val SEPARATOR = '\t'

    /** 解析规则文本；重复触发词保留最后一条。 */
    fun parse(text: String): List<QuickEntry> {
        val byTrigger = LinkedHashMap<String, QuickEntry>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val fields = line.split(SEPARATOR)
            if (fields.size < 3) return@forEach
            val trigger = fields[0].trim()
            val content = fields[2].trim()
            if (trigger.isEmpty() || content.isEmpty()) return@forEach
            val label = fields[1].trim().ifEmpty { content }
            val mode = if (fields.size > 3) QuickMatchMode.parse(fields[3]) else QuickMatchMode.PREFIX
            val weight = if (fields.size > 4) fields[4].trim().toIntOrNull() ?: 100 else 100
            val kind = if (fields.size > 5) {
                if (fields[5].trim().lowercase().startsWith("a")) LookupKind.QUICKAPPLY else LookupKind.PHRASE
            } else {
                if (label == content) LookupKind.PHRASE else LookupKind.QUICKAPPLY
            }
            byTrigger[trigger] = QuickEntry(
                trigger = trigger,
                label = label,
                content = content,
                weight = weight,
                mode = mode,
                kind = kind,
            )
        }
        return byTrigger.values.toList()
    }

    /** 序列化：trigger \t label \t content \t mode \t weight \t kind(a/p)。 */
    fun format(entries: List<QuickEntry>): String = buildString {
        append("# 智语 iSpeech 本地快捷规则（由设置界面生成，可手动编辑）\n")
        append("# 字段：trigger\\tlabel\\tcontent\\tmode\\tweight\\tkind\n")
        entries.forEach { entry ->
            append(sanitizeField(entry.trigger)).append(SEPARATOR)
            append(sanitizeField(entry.label)).append(SEPARATOR)
            append(sanitizeContent(entry.content)).append(SEPARATOR)
            append(entry.mode.name.lowercase()).append(SEPARATOR)
            append(entry.weight).append(SEPARATOR)
            append(if (entry.kind == LookupKind.QUICKAPPLY) 'a' else 'p').append('\n')
        }
    }

    /** 触发词 / 名称字段不允许出现分隔符与换行。 */
    private fun sanitizeField(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** 内容字段保留换行语义（写作 \n 字面量，与 SyncRepository 一致）。 */
    private fun sanitizeContent(value: String): String =
        value.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t")
}
