package com.ispeech.data

import android.util.Log
import com.ispeech.engine.LookupCandidate
import com.ispeech.engine.LookupKind
import java.io.File

/**
 * 用户词库的输入模式：对齐搜狗 mac 版的用户词库文件名（`wb_dict_user` / `cn_dict_user`）。
 *
 * 编码空间按模式隔离：五笔码写 [WUBI]、拼音串写 [PINYIN]。混在一个库里会互相污染——
 * 拼音 `jiudian` 会被当成五笔码命中，五笔码也可能被拼音串撞上。
 */
enum class UserDictMode(val id: String, val label: String) {
    WUBI("wb", "五笔"),
    PINYIN("cn", "拼音");

    companion object {
        /** 解析模式列；缺列 / 未知值一律当五笔，兼容只有五笔的历史数据。 */
        fun parse(raw: String?): UserDictMode = when (raw?.trim()?.lowercase()) {
            PINYIN.id -> PINYIN
            else -> WUBI
        }
    }
}

/** 用户词典条目：code 为输入码（五笔码 / 拼音串），text 为上屏文本，count 为使用次数。 */
data class UserDictEntry(
    val code: String,
    val text: String,
    val count: Int,
    val weight: Int,
    val mode: UserDictMode = UserDictMode.WUBI,
)

/** 导入结果：新增 / 更新 / 跳过（解析失败或重复但未覆盖）的条数。 */
data class UserDictImportResult(val added: Int, val updated: Int, val skipped: Int) {
    /** 实际写库的条数（新增 + 更新）。 */
    val total: Int get() = added + updated
}

/** 空白分隔：只在整行没有制表符时用于「编码 词条」这种两段式写法。 */
private val BLANK_SEPARATOR = Regex("\\s+")

/**
 * 解析一行用户词库文本（读盘与导入共用同一套规则，避免两处规则漂移）。
 *
 * 支持三种写法，字段之间用 TAB 分隔：
 *  * `code<TAB>text<TAB>count<TAB>mode` —— 当前磁盘格式
 *  * `code<TAB>text<TAB>count` —— 历史格式，模式按 [defaultMode]
 *  * `code<TAB>text` —— 导入用的简写，count 记 1
 * 另外在整行没有 TAB 时接受 `编码 词条`（空白分隔）两段式。
 *
 * 模式列只在「末列是 wb / cn 且倒数第二列是计数」时才认——否则文本里未转义的 TAB
 * 会被误判成模式列（历史数据里存在这种情况）。
 */
internal fun parseUserDictLine(line: String, defaultMode: UserDictMode = UserDictMode.WUBI): UserDictEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    if (!trimmed.contains('\t')) {
        val parts = trimmed.split(BLANK_SEPARATOR)
        if (parts.size != 2) return null
        return buildUserDictEntry(parts[0], parts[1], 1, defaultMode)
    }
    val fields = trimmed.split('\t')
    if (fields.size < 2) return null
    val modeCell = fields.last().trim().lowercase()
    val hasModeColumn = fields.size >= 4 &&
        (modeCell == UserDictMode.WUBI.id || modeCell == UserDictMode.PINYIN.id) &&
        fields[fields.size - 2].trim().toIntOrNull() != null
    val mode = if (hasModeColumn) UserDictMode.parse(fields.last()) else defaultMode
    val textEnd: Int
    val count: Int
    when {
        hasModeColumn -> {
            textEnd = fields.size - 2
            count = fields[textEnd].trim().toInt()
        }
        fields.size == 2 -> {
            textEnd = 2
            count = 1
        }
        fields.last().trim().toIntOrNull() != null -> {
            textEnd = fields.size - 1
            count = fields.last().trim().toInt()
        }
        else -> return null
    }
    val word = unescapeLineText(fields.subList(1, textEnd).joinToString("\t"))
    return buildUserDictEntry(fields[0], word, count, mode)
}

/** 组装条目：编码统一成小写 ASCII 字母 / 数字（拼音串要去掉音节间的空格才对得上查询）。 */
private fun buildUserDictEntry(code: String, word: String, count: Int, mode: UserDictMode): UserDictEntry? {
    val normalized = unescapeLineText(code).lowercase().filter { ch ->
        ch in 'a'..'z' || ch in '0'..'9'
    }
    val text = unescapeLineText(word)
    if (normalized.isEmpty() || text.isEmpty()) return null
    val safeCount = count.coerceAtLeast(0)
    return UserDictEntry(normalized, text, safeCount, UserDictStore.weightOf(safeCount), mode)
}

/**
 * 用户词频 + 自造词存储：filesDir/user_dict.txt，格式 `code<TAB>text<TAB>count<TAB>mode`，
 * 每行一条，支持 # 注释。历史 3 列（无 mode 列）按 [UserDictMode.WUBI] 读入，不丢词。
 */
class UserDictStore private constructor(private val file: File) {

    /** mode + "\u0001" + code + "\u0001" + text -> 条目；\u0001 保证任意组合都唯一。 */
    private val entries = LinkedHashMap<String, UserDictEntry>()

    /** 懒加载标记：保证首次访问才读盘。 */
    private var loaded = false

    companion object {
        private const val TAG = "UserDictStore"

        /** 文件头注释行。 */
        private const val HEADER = "# 用户词频 / 自造词（自动生成，可在设置页管理）"

        /** 键分隔符（不可见于正常编码 / 文本）。 */
        private const val KEY_SEP = "\u0001"

        /** 权重基数：用户选择过的词默认比系统词库更靠前。 */
        private const val BASE_WEIGHT = 120

        /** 每次使用递增的权重。 */
        private const val WEIGHT_PER_USE = 10

        /** 权重上限，避免长期使用后压死所有系统词。 */
        private const val MAX_WEIGHT = 400

        /** 联想扫描上限，避免条目很多时每次都全量排序。 */
        private const val ASSOCIATION_SCAN_LIMIT = 64

        /** 打开（不存在则新建，读写均容错，绝不抛异常）。 */
        fun open(file: File): UserDictStore {
            val store = UserDictStore(file)
            runCatching {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.writeText(HEADER + "\n", Charsets.UTF_8)
                }
            }.onFailure { Log.w(TAG, "创建用户词典失败: ${file.path}", it) }
            return store
        }

        /** 权重公式：min(400, 120 + count * 10)；[parseUserDictLine] 导入时也要用。 */
        internal fun weightOf(count: Int): Int {
            val safe = count.coerceAtLeast(0)
            val raw = BASE_WEIGHT + safe.toLong() * WEIGHT_PER_USE
            return raw.coerceAtMost(MAX_WEIGHT.toLong()).toInt()
        }

        private fun keyOf(mode: UserDictMode, code: String, text: String): String =
            mode.id + KEY_SEP + code + KEY_SEP + text
    }

    /** 全部条目（使用次数降序，其次文本升序）。 */
    @Synchronized
    fun all(): List<UserDictEntry> {
        ensureLoaded()
        val list = ArrayList(entries.values)
        list.sortWith(COUNT_RANK)
        return list
    }

    /** 记录一次使用（自造词与词频学习共用）：存在则 count+1，返回最新条目。 */
    @Synchronized
    fun record(code: String, text: String, mode: UserDictMode = UserDictMode.WUBI): UserDictEntry? {
        if (code.isEmpty() || text.isEmpty()) return null
        ensureLoaded()
        val key = keyOf(mode, code, text)
        val count = (entries[key]?.count ?: 0) + 1
        val entry = UserDictEntry(code, text, count, weightOf(count), mode)
        entries[key] = entry
        persist()
        return entry
    }

    /** 删除条目，返回是否删除成功。 */
    @Synchronized
    fun remove(code: String, text: String, mode: UserDictMode = UserDictMode.WUBI): Boolean {
        ensureLoaded()
        val removed = entries.remove(keyOf(mode, code, text)) != null
        if (removed) persist()
        return removed
    }

    /** 清空用户词典。 */
    @Synchronized
    fun clear() {
        ensureLoaded()
        entries.clear()
        persist()
    }

    /** 导出为文本：与磁盘格式一致（`code<TAB>text<TAB>count<TAB>mode`），可再次导入或手工编辑。 */
    @Synchronized
    fun exportText(): String {
        ensureLoaded()
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (entry in entries.values) {
            sb.append(escapeLineText(entry.code)).append('\t')
                .append(escapeLineText(entry.text)).append('\t')
                .append(entry.count).append('\t')
                .append(entry.mode.id).append('\n')
        }
        return sb.toString()
    }

    /**
     * 导入文本：逐行按 [parseUserDictLine] 解析，按 `mode + code + text` 合并——
     * 新条目直接写入，已存在的取较大 count（不会把学到的词频降下去）。行内自带模式列时
     * 以行为准，否则用 [defaultMode]（界面上选「导入到五笔 / 拼音」）。
     */
    @Synchronized
    fun importText(text: String, defaultMode: UserDictMode = UserDictMode.WUBI): UserDictImportResult {
        ensureLoaded()
        var added = 0
        var updated = 0
        var skipped = 0
        for (rawLine in text.split('\n')) {
            val parsed = parseUserDictLine(rawLine, defaultMode)
            if (parsed == null) {
                if (rawLine.isNotBlank() && !rawLine.trim().startsWith("#")) skipped++
                continue
            }
            val key = keyOf(parsed.mode, parsed.code, parsed.text)
            val existing = entries[key]
            when {
                existing == null -> {
                    entries[key] = parsed
                    added++
                }
                parsed.count > existing.count -> {
                    entries[key] = parsed
                    updated++
                }
                else -> skipped++
            }
        }
        if (added > 0 || updated > 0) persist()
        return UserDictImportResult(added, updated, skipped)
    }

    /** 查询候选：只看 [mode] 对应模式的条目；编码精确匹配优先，其次前缀；按权重降序。 */
    @Synchronized
    fun lookupCandidates(input: String, limit: Int, mode: UserDictMode = UserDictMode.WUBI): List<LookupCandidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        ensureLoaded()
        val needle = input.lowercase()
        val exact = ArrayList<UserDictEntry>()
        val prefixed = ArrayList<UserDictEntry>()
        for (entry in entries.values) {
            if (entry.mode != mode) continue
            val code = entry.code.lowercase()
            when {
                code == needle -> exact.add(entry)
                code.startsWith(needle) -> prefixed.add(entry)
            }
        }
        if (exact.isEmpty() && prefixed.isEmpty()) return emptyList()
        // 精确码整组优先于前缀码组，组内按权重降序。
        exact.sortWith(WEIGHT_RANK)
        prefixed.sortWith(WEIGHT_RANK)

        val out = ArrayList<LookupCandidate>()
        val seen = HashSet<String>()
        for (entry in exact) {
            if (seen.add(entry.text)) out.add(entry.toCandidate())
            if (out.size >= limit) return out
        }
        for (entry in prefixed) {
            if (seen.add(entry.text)) out.add(entry.toCandidate())
            if (out.size >= limit) return out
        }
        return out
    }

    /** 联想：只看 [mode] 对应模式的条目，文本包含 input 的按权重降序。 */
    @Synchronized
    fun related(text: String, limit: Int, mode: UserDictMode = UserDictMode.WUBI): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        ensureLoaded()
        val tail = needle.last()

        val pool = ArrayList<UserDictEntry>()
        for (entry in entries.values) {
            if (entry.mode != mode) continue
            val word = entry.text
            if (word.isEmpty() || word == needle) continue
            val hit = word.contains(needle) ||
                (word.length >= 2 && needle.contains(word)) ||
                word.first() == tail
            if (!hit) continue
            pool.add(entry)
            if (pool.size >= ASSOCIATION_SCAN_LIMIT) break
        }
        if (pool.isEmpty()) return emptyList()
        pool.sortWith(WEIGHT_RANK)
        return pool.take(limit).map { it.toCandidate() }
    }

    /** 懒加载：文件缺失 / 损坏一律当空，不抛异常。 */
    private fun ensureLoaded(): Unit {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            for (rawLine in file.readText(Charsets.UTF_8).split('\n')) {
                val entry = parseUserDictLine(rawLine, UserDictMode.WUBI) ?: continue
                entries[keyOf(entry.mode, entry.code, entry.text)] = entry
            }
        }.onFailure { Log.w(TAG, "读取用户词典失败: ${file.path}", it) }
    }

    /** 写回文件：IO 异常吞掉并打日志。 */
    private fun persist(): Unit {
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append(HEADER).append('\n')
            for (entry in entries.values) {
                sb.append(escapeLineText(entry.code)).append('\t')
                    .append(escapeLineText(entry.text)).append('\t')
                    .append(entry.count).append('\t')
                    .append(entry.mode.id).append('\n')
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写入用户词典失败: ${file.path}", it) }
    }

}

/** 条目排序：权重降序，其次文本升序。 */
private val WEIGHT_RANK: Comparator<UserDictEntry> = Comparator { a, b ->
    val byWeight = b.weight.compareTo(a.weight)
    if (byWeight != 0) byWeight else a.text.compareTo(b.text)
}

/** 词频列表排序：使用次数降序，其次文本升序。 */
private val COUNT_RANK: Comparator<UserDictEntry> = Comparator { a, b ->
    val byCount = b.count.compareTo(a.count)
    if (byCount != 0) byCount else a.text.compareTo(b.text)
}

/**
 * 条目 → 统一候选：五笔模式单选字走五笔、多字走词组档；拼音模式一律走拼音档，
 * 与拼音词表的候选同档竞争，靠 +10 万权重排到前面。
 */
private fun UserDictEntry.toCandidate(): LookupCandidate = LookupCandidate(
    code = code,
    display = text,
    insertText = text,
    weight = weight,
    kind = when (mode) {
        UserDictMode.WUBI -> if (text.length == 1) LookupKind.WUBI else LookupKind.PHRASE
        UserDictMode.PINYIN -> LookupKind.PINYIN
    },
)

/** 单行文本转义：换行 / 回车 / 制表符 / 反斜杠写成字面量，保证一条记录占一行。 */
internal fun escapeLineText(text: String): String {
    if (text.none { it == '\n' || it == '\r' || it == '\t' || it == '\\' }) return text
    val sb = StringBuilder(text.length + 8)
    for (ch in text) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** 转义反转义：\n / \r / \t / \\ 还原；未知转义原样保留。 */
internal fun unescapeLineText(text: String): String {
    if (!text.contains('\\')) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val next = if (i + 1 < text.length) text[i + 1] else null
        if (ch == '\\' && next != null) {
            when (next) {
                'n' -> {
                    sb.append('\n')
                    i += 2
                }

                'r' -> {
                    sb.append('\r')
                    i += 2
                }

                't' -> {
                    sb.append('\t')
                    i += 2
                }

                '\\' -> {
                    sb.append('\\')
                    i += 2
                }

                else -> {
                    sb.append(ch)
                    i += 1
                }
            }
        } else {
            sb.append(ch)
            i += 1
        }
    }
    return sb.toString()
}
