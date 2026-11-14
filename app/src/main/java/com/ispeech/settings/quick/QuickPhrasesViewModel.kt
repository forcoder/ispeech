package com.ispeech.settings.quick

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.data.PhraseMatch
import com.ispeech.data.PhraseSearch
import com.ispeech.data.PhraseUsageStore
import com.ispeech.data.SyncRepository
import com.ispeech.data.unescapeLineText
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
import kotlinx.coroutines.withContext

/** 快捷短语页界面状态。 */
data class QuickPhrasesUiState(
    val loading: Boolean = true,
    val entries: List<QuickEntry> = emptyList(),
    /** 搜索栏关键词；空串表示不过滤（列表按使用频次 / 收藏 / 权重排序）。 */
    val keyword: String = "",
    /** 搜索 + 智能联想后的结果（与 [entries] 同源，仅排序 / 过滤不同）。 */
    val matches: List<PhraseMatch> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val tip: String? = null,
)

/**
 * 快捷短语页 ViewModel。
 *
 * 读写 filesDir/quick_phrases_user.txt（见 AppContainer.quickPhrasesUserFile），
 * 文件格式与引擎 QuickPhraseEngine 一致：每行 tab 分隔
 * trigger / label / content / mode / weight / kind。
 *
 * 保存时除了写盘，还会把本地增 / 改 / 删同步到 csBaby 远端（未登录则只落本地并提示）；
 * 搜索栏的关键词过滤与智能联想（拼音首字母 / 使用频次 / 收藏加权）由 PhraseSearch 负责，
 * 使用数据在 [load] 里一次性读入内存，输入时只做纯计算，不在主线程碰磁盘。
 */
class QuickPhrasesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(QuickPhrasesUiState())
    val uiState: StateFlow<QuickPhrasesUiState> = _uiState.asStateFlow()

    /** 使用次数（id -> 次数），[load] 时读盘，供联想排序使用。 */
    private var usageCounts: Map<String, Int> = emptyMap()

    /** 已收藏 id 集合，[load] 时读盘，供联想排序使用。 */
    private var starredIds: Set<String> = emptySet()

    init {
        load()
    }

    /** 读取用户文件与使用记录；文件不存在时展示空列表。 */
    fun load() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val entries = runCatching {
                    val file = AppContainer.quickPhrasesUserFile(app)
                    if (file.exists()) QuickPhraseCodec.parse(file.readText()) else emptyList()
                }.getOrElse { emptyList() }
                // 使用记录只在这里读一次：之后每次输入关键词都只做纯计算
                val store = AppContainer.phraseUsage(app)
                val ids = entries.map { PhraseUsageStore.idOf(it) }
                val counts = ids.associateWith { store.countOf(it) }.filterValues { it > 0 }
                val starred = ids.filterTo(HashSet()) { store.isStarred(it) }
                Triple(entries, counts, starred as Set<String>)
            }
            usageCounts = loaded.second
            starredIds = loaded.third
            _uiState.update {
                it.copy(entries = loaded.first, loading = false).withMatches()
            }
        }
    }

    /** 搜索栏输入：实时过滤 + 联想。 */
    fun setKeyword(keyword: String) {
        _uiState.update { it.copy(keyword = keyword).withMatches() }
    }

    /** 清空搜索栏。 */
    fun clearKeyword() {
        setKeyword("")
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
                tip = "已更新「${entry.trigger}」，点击右上角「保存并同步」写入并上传",
            ).withMatches()
        }
    }

    /** 删除指定触发词的规则（保存并同步时远端也会删除）。 */
    fun remove(entry: QuickEntry) {
        _uiState.update { state ->
            state.copy(
                entries = state.entries.filterNot { it.trigger == entry.trigger },
                dirty = true,
                tip = "已删除「${entry.trigger}」，点击右上角「保存并同步」写入并上传",
            ).withMatches()
        }
    }

    /** 保存：先写本地文件，再按登录态把增 / 改 / 删同步到 csBaby 远端。 */
    fun save() {
        if (_uiState.value.saving) return
        val entries = _uiState.value.entries
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    val file = AppContainer.quickPhrasesUserFile(app)
                    file.parentFile?.mkdirs()
                    file.writeText(QuickPhraseCodec.format(entries))
                }
            }
            val failure = written.exceptionOrNull()
            if (failure != null) {
                _uiState.update {
                    it.copy(saving = false, tip = "保存失败：${failure.message ?: "未知错误"}")
                }
                return@launch
            }
            // 写盘后让输入法重建引擎，本地短语无需重启输入法即可生效
            AppContainer.invalidateEngine()
            _uiState.update { it.copy(dirty = false) }
            syncToCloud()
        }
    }

    /** 把本地变更同步到云端；未登录时只提示本地已保存。 */
    private suspend fun syncToCloud() {
        val count = _uiState.value.entries.size
        val auth = runCatching { CsBabyAuthStore.load(app) }.getOrNull()
        if (auth == null) {
            _uiState.update {
                it.copy(saving = false, tip = app.getString(R.string.quick_saved_local_only, count))
            }
            return
        }
        runCatching { SyncRepository.pushLocalRules(app) }
            .onSuccess { report ->
                _uiState.update { it.copy(saving = false, tip = syncTip(count, report)) }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        saving = false,
                        tip = app.getString(
                            R.string.quick_sync_failed,
                            throwable.message ?: "未知错误",
                        ),
                    )
                }
            }
    }

    /** 同步结果文案：pending 表示远端没生效（后端不支持修改 / 删除），如实说明而不是报成功。 */
    private fun syncTip(count: Int, report: SyncRepository.RuleSyncReport): String = when {
        report.pushed == 0 -> app.getString(R.string.quick_synced_none, count)
        report.pending > 0 -> app.getString(
            R.string.quick_synced_partial,
            count,
            report.created,
            report.updatedApplied,
            report.deletedApplied,
            report.pending,
        )
        else -> app.getString(
            R.string.quick_synced,
            count,
            report.created,
            report.updatedApplied,
            report.deletedApplied,
        )
    }

    /** 已消费提示信息。 */
    fun consumeTip() {
        _uiState.update { it.copy(tip = null) }
    }

    /**
     * 通过 csBaby 后端添加一条云端快捷短语。
     * @param keyword 简写（触发词）
     * @param replyTemplate 上屏内容
     */
    fun addCloudShortcut(keyword: String, replyTemplate: String) {
        viewModelScope.launch {
            val auth = runCatching { CsBabyAuthStore.load(app) }.getOrNull()
            if (auth == null) {
                _uiState.update {
                    it.copy(
                        tip = app.getString(R.string.quick_cloud_login_required)
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(saving = true) }
            runCatching {
                val client = AppContainer.csBabyClient(app)
                val request = RuleUpsertRequest(
                    keyword = keyword,
                    replyTemplate = replyTemplate
                )
                client.createRule(auth, request)
            }
            .onSuccess { rule ->
                _uiState.update {
                    it.copy(
                        saving = false,
                        tip = app.getString(R.string.quick_cloud_added_success, keyword)
                    )
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        saving = false,
                        tip = app.getString(
                            R.string.quick_cloud_added_failed,
                            keyword,
                            throwable.message ?: "未知错误"
                        )
                    )
                }
            }
        }
    }

    /** 按当前关键词重算过滤 + 联想结果（纯计算，不碰磁盘）。 */
    private fun QuickPhrasesUiState.withMatches(): QuickPhrasesUiState {
        val search = PhraseSearch(
            usageCount = { id -> usageCounts[id] ?: 0 },
            starredFlag = { id -> id in starredIds },
        )
        return copy(
            matches = search.search(
                entries = entries,
                keyword = keyword,
                limit = PhraseSearch.DEFAULT_LIMIT,
            )
        )
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
            val content = unescapeLineText(fields[2].trim())
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
