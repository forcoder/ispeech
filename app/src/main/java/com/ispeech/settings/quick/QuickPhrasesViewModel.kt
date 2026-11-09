package com.ispeech.settings.quick

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.data.PhraseCsvImport
import com.ispeech.data.PhraseCsvImportResult
import com.ispeech.data.PhraseMatch
import com.ispeech.data.PhraseSearch
import com.ispeech.data.PhraseUsageStore
import com.ispeech.data.RemoteKeywordRule
import com.ispeech.data.SyncRepository
import com.ispeech.data.unescapeLineText
import com.ispeech.di.AppContainer
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickOverride
import com.ispeech.engine.QuickSource
import com.ispeech.data.remote.RuleUpsertRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 远端规则的展示信息：原始远端条目 + 用户覆盖的快捷字母（未覆盖为 null）。 */
data class RemoteRuleInfo(
    val original: QuickEntry,
    val overrideTrigger: String?,
)

/** 快捷短语页界面状态。 */
data class QuickPhrasesUiState(
    val loading: Boolean = true,
    /** 用户规则：唯一会被保存 / 同步的列表。 */
    val entries: List<QuickEntry> = emptyList(),
    /** 远端拉取规则：只读展示，绝不会写回用户文件。 */
    val remoteEntries: List<QuickEntry> = emptyList(),
    /** 远端展示 id -> 原始条目 / 覆盖快捷字母，用于来源角标与「恢复默认」。 */
    val remoteInfo: Map<String, RemoteRuleInfo> = emptyMap(),
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

    private companion object {
        /** 覆盖条目相对远端规则的权重加成，保证候选排序时压过原远端条目。 */
        const val REMOTE_OVERRIDE_WEIGHT_BONUS = 1000
    }

    init {
        load()
    }

    /** 读取用户文件 / 远端文件与使用记录；文件不存在时对应来源为空。 */
    fun load() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val user = parseFile(AppContainer.quickPhrasesUserFile(app))
                    .map { it.copy(source = QuickSource.USER) }
                val remote = parseFile(AppContainer.quickPhrasesRemoteFile(app))
                    .map { it.copy(source = QuickSource.REMOTE) }
                // 使用记录只在这里读一次：之后每次输入关键词都只做纯计算
                val store = AppContainer.phraseUsage(app)
                val merged = mergeForDisplay(user, remote)
                val ids = HashSet<String>()
                user.forEach { ids.add(PhraseUsageStore.idOf(it)) }
                remote.forEach { ids.add(PhraseUsageStore.idOf(it)) }
                merged.entries.forEach { ids.add(PhraseUsageStore.idOf(it)) }
                val counts = HashMap<String, Int>()
                for (id in ids) {
                    val count = store.countOf(id)
                    if (count > 0) counts[id] = count
                }
                val starred = ids.filterTo(HashSet()) { store.isStarred(it) }
                // 覆盖后 trigger 变了、id 也随之变化：把原远端规则的次数 / 收藏并到新 id
                for ((displayId, originalId) in merged.originalIdById) {
                    val count = maxOf(counts[displayId] ?: 0, counts[originalId] ?: 0)
                    if (count > 0) counts[displayId] = count
                    if (originalId in starred) starred.add(displayId)
                }
                LoadedPhrases(user, remote, counts, starred)
            }
            usageCounts = loaded.usage
            starredIds = loaded.starred
            _uiState.update {
                it.copy(
                    entries = loaded.user,
                    remoteEntries = loaded.remote,
                    loading = false,
                ).withMatches()
            }
        }
    }

    /** 读取规则文件；不存在或解析失败返回空列表（单个来源损坏不影响另一个来源）。 */
    private fun parseFile(file: File): List<QuickEntry> = runCatching {
        if (file.exists()) QuickPhraseCodec.parse(file.readText()) else emptyList()
    }.getOrElse { emptyList() }

    /**
     * 合并用户 / 远端规则用于展示。
     *
     * 用户文件里 label + content 与某条远端规则相同的条目视为该规则的「快捷字母覆盖」：
     * 用覆盖条目的 trigger / mode / kind 替换远端条目展示，并从用户卡片中移除，避免同一条短语
     * 出现两次；返回的 [DisplayMerge.infoById] 以展示 id 为键，供 UI 判断来源与「恢复默认」。
     * 没匹配到远端的用户条目（普通规则 / 远端已删除后遗留的覆盖）照常展示。
     */
    private fun mergeForDisplay(
        user: List<QuickEntry>,
        remote: List<QuickEntry>,
    ): DisplayMerge {
        val overrides = HashMap<Pair<String, String>, ArrayDeque<QuickEntry>>()
        user.forEach { entry ->
            overrides.getOrPut(entry.label to entry.content) { ArrayDeque() }.addLast(entry)
        }
        val display = ArrayList<QuickEntry>(user.size + remote.size)
        val infoById = LinkedHashMap<String, RemoteRuleInfo>()
        val originalIdById = LinkedHashMap<String, String>()
        val consumed = HashSet<String>()
        remote.forEach { rule ->
            val override = overrides[rule.label to rule.content]?.removeFirstOrNull()
            val effective = if (override == null) {
                rule
            } else {
                consumed.add(override.trigger)
                rule.copy(
                    trigger = override.trigger,
                    mode = override.mode,
                    kind = override.kind,
                )
            }
            val displayId = PhraseUsageStore.idOf(effective)
            infoById[displayId] = RemoteRuleInfo(rule, override?.trigger)
            originalIdById[displayId] = PhraseUsageStore.idOf(rule)
            display.add(effective)
        }
        display.addAll(user.filterNot { it.trigger in consumed })
        return DisplayMerge(display, infoById, originalIdById)
    }

    /** 搜索栏输入：实时过滤 + 联想。 */
    fun setKeyword(keyword: String) {
        _uiState.update { it.copy(keyword = keyword).withMatches() }
    }

    /** 清空搜索栏。 */
    fun clearKeyword() {
        setKeyword("")
    }

    /**
     * 导入「通用短语」CSV（GBK / UTF-8 自动识别）到本地快捷短语。
     *
     * 只改内存列表 + 标记 dirty，由用户点「保存并同步」统一落盘 / 上传（与手动新增同一套契约）；
     * 同 trigger 的已有用户规则被导入条目覆盖，其余保留。导入条数 / 跳过原因如实提示，
     * 因为源 CSV 里确实存在无快捷字母、同码重复、超 4 位的行。
     */
    fun importPhraseCsv(uri: Uri) {
        viewModelScope.launch {
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                    PhraseCsvImport.parse(PhraseCsvImport.decode(bytes))
                }
            }.getOrElse { throwable ->
                _uiState.update {
                    it.copy(
                        tip = app.getString(
                            R.string.quick_import_failed,
                            throwable.message ?: "未知错误",
                        )
                    )
                }
                return@launch
            }

            val formatError = parsed.formatError
            if (formatError != null) {
                _uiState.update { it.copy(tip = app.getString(R.string.quick_import_failed, formatError)) }
                return@launch
            }
            if (parsed.entries.isEmpty()) {
                _uiState.update { it.copy(tip = app.getString(R.string.quick_import_empty)) }
                return@launch
            }

            val imported = parsed.entries.map { it.copy(source = QuickSource.USER) }
            val incomingTriggers = imported.mapTo(HashSet()) { it.trigger }
            _uiState.update { state ->
                val replaced = state.entries.count { it.trigger in incomingTriggers }
                val kept = state.entries.filterNot { it.trigger in incomingTriggers }
                state.copy(
                    entries = kept + imported,
                    dirty = true,
                    tip = importTip(parsed, replaced),
                ).withMatches()
            }
        }
    }

    /** 导入提示：条数 + 跳过 / 需知项（无跳过时用更短的文案）。 */
    private fun importTip(result: PhraseCsvImportResult, replaced: Int): String {
        val details = ArrayList<String>()
        if (result.skippedDuplicateCode > 0) {
            details.add(app.getString(R.string.quick_import_detail_duplicate, result.skippedDuplicateCode))
        }
        if (result.skippedNoCode > 0) {
            details.add(app.getString(R.string.quick_import_detail_no_code, result.skippedNoCode))
        }
        if (result.skippedBadCode > 0) {
            details.add(app.getString(R.string.quick_import_detail_bad_code, result.skippedBadCode))
        }
        if (result.skippedDeleted > 0) {
            details.add(app.getString(R.string.quick_import_detail_deleted, result.skippedDeleted))
        }
        if (result.skippedEmptyContent > 0) {
            details.add(app.getString(R.string.quick_import_detail_empty, result.skippedEmptyContent))
        }
        if (result.longCodeCount > 0) {
            details.add(app.getString(R.string.quick_import_detail_long, result.longCodeCount))
        }
        if (result.singleLetterCount > 0) {
            details.add(app.getString(R.string.quick_import_detail_single, result.singleLetterCount))
        }
        if (replaced > 0) {
            details.add(app.getString(R.string.quick_import_detail_replaced, replaced))
        }
        return if (details.isEmpty()) {
            app.getString(R.string.quick_import_done_clean, result.entries.size)
        } else {
            app.getString(R.string.quick_import_done, result.entries.size, details.joinToString("；"))
        }
    }

    /** 新增或按触发词覆盖同一规则。 */
    fun upsert(entry: QuickEntry) {
        val userEntry = entry.copy(source = QuickSource.USER)
        _uiState.update { state ->
            val list = state.entries.toMutableList()
            val index = list.indexOfFirst { it.trigger == userEntry.trigger }
            if (index >= 0) list[index] = userEntry else list.add(userEntry)
            state.copy(
                entries = list,
                dirty = true,
                tip = "已更新「${userEntry.trigger}」，点击右上角「保存并同步」写入并上传",
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

    /**
     * 给远端规则设置快捷字母：不改远端文件，而是在用户规则里写一条覆盖条目。
     *
     * 覆盖条目复制远端的 label / content / kind，trigger 换成用户选的快捷字母，
     * weight 比远端高 [REMOTE_OVERRIDE_WEIGHT_BONUS]，确保 [CompositeEngine] 全局排序后
     * 先于原远端条目被按 display 去重。mode 固定 prefix：快捷字母按前缀输入即可命中，
     * 不能沿用远端的 regex / contains 语义（trigger 是字母，不是正则 / 中文关键词）。
     */
    fun setRemoteShortcut(original: QuickEntry, trigger: String) {
        val shortcut = trigger.trim().lowercase()
        // 防御性校验：正常入口已在弹窗里禁用非法输入
        if (shortcut.length !in 2..4 || shortcut.any { it !in 'a'..'z' }) return
        val override = QuickEntry(
            trigger = shortcut,
            label = original.label,
            content = original.content,
            weight = original.weight + REMOTE_OVERRIDE_WEIGHT_BONUS,
            mode = QuickMatchMode.PREFIX,
            kind = original.kind,
            source = QuickSource.USER,
        )
        _uiState.update { state ->
            // 同一条远端规则只保留一条覆盖；重新设置时直接替换。
            // 同时按 trigger 覆盖已有用户规则，避免用户文件出现同 trigger 两行（解析时后者会吞掉前者）
            val cleaned = state.entries.filterNot {
                QuickOverride.matches(it, original) || it.trigger == shortcut
            }
            state.copy(
                entries = cleaned + override,
                dirty = true,
                tip = app.getString(R.string.quick_remote_override_set, shortcut),
            ).withMatches()
        }
    }

    /** 删除远端规则的本地覆盖条目，恢复远端默认快捷字母。 */
    fun removeRemoteOverride(original: QuickEntry) {
        _uiState.update { state ->
            state.copy(
                entries = state.entries.filterNot { QuickOverride.matches(it, original) },
                dirty = true,
                tip = app.getString(R.string.quick_remote_override_restored),
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
        // 用户条目（含覆盖）与远端条目一起搜索：搜索 / 智能联想对两种来源都生效
        val merged = mergeForDisplay(entries, remoteEntries)
        val search = PhraseSearch(
            usageCount = { id -> usageCounts[id] ?: 0 },
            starredFlag = { id -> id in starredIds },
        )
        return copy(
            matches = search.search(
                entries = merged.entries,
                keyword = keyword,
                limit = PhraseSearch.DEFAULT_LIMIT,
            ),
            remoteInfo = merged.infoById,
        )
    }

    /** [load] 的 IO 结果：用户 / 远端规则与使用数据快照。 */
    private data class LoadedPhrases(
        val user: List<QuickEntry>,
        val remote: List<QuickEntry>,
        val usage: Map<String, Int>,
        val starred: Set<String>,
    )

    /** 展示合并结果：展示条目 + 远端展示信息 + 覆盖前的原始 id（用于沿用使用记录）。 */
    private data class DisplayMerge(
        val entries: List<QuickEntry>,
        val infoById: Map<String, RemoteRuleInfo>,
        val originalIdById: Map<String, String>,
    )
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
