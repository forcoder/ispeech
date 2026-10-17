package com.ispeech.data

import android.util.Log
import com.ispeech.engine.LookupCandidate
import com.ispeech.engine.LookupKind
import java.io.File

/** 用户词典条目：code 为五笔编码，text 为上屏文本，count 为使用次数。 */
data class UserDictEntry(val code: String, val text: String, val count: Int, val weight: Int)

/** 用户词频 + 自造词存储：filesDir/user_dict.txt，格式 code<TAB>text<TAB>count，每行一条，支持 # 注释。 */
class UserDictStore private constructor(private val file: File) {

    /** code + "\u0001" + text -> 条目；\u0001 保证 code / text 任意组合都唯一。 */
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

        /** 权重公式：min(400, 120 + count * 10)。 */
        private fun weightOf(count: Int): Int {
            val safe = count.coerceAtLeast(0)
            val raw = BASE_WEIGHT + safe.toLong() * WEIGHT_PER_USE
            return raw.coerceAtMost(MAX_WEIGHT.toLong()).toInt()
        }

        private fun keyOf(code: String, text: String): String = code + KEY_SEP + text
    }

    /** 全部条目（使用次数降序，其次文本升序）。 */
    @Synchronized
    fun all(): List<UserDictEntry> {
        ensureLoaded()
        val list = ArrayList(entries.values)
        list.sortWith(COUNT_RANK)
        return list
    }

    /** 记录一次使用：存在则 count+1，返回最新条目。 */
    @Synchronized
    fun record(code: String, text: String): UserDictEntry? {
        if (code.isEmpty() || text.isEmpty()) return null
        ensureLoaded()
        val key = keyOf(code, text)
        val count = (entries[key]?.count ?: 0) + 1
        val entry = UserDictEntry(code, text, count, weightOf(count))
        entries[key] = entry
        persist()
        return entry
    }

    /** 删除条目，返回是否删除成功。 */
    @Synchronized
    fun remove(code: String, text: String): Boolean {
        ensureLoaded()
        val removed = entries.remove(keyOf(code, text)) != null
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

    /** 查询候选：编码精确匹配优先，其次以输入为前缀的编码；按权重降序。 */
    @Synchronized
    fun lookupCandidates(input: String, limit: Int): List<LookupCandidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        ensureLoaded()
        val needle = input.lowercase()
        val exact = ArrayList<UserDictEntry>()
        val prefixed = ArrayList<UserDictEntry>()
        for (entry in entries.values) {
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

    /** 联想：文本包含 input 的条目，按权重降序。 */
    @Synchronized
    fun related(text: String, limit: Int): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        ensureLoaded()
        val tail = needle.last()

        val pool = ArrayList<UserDictEntry>()
        for (entry in entries.values) {
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
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 3) continue
                val code = unescapeLineText(fields[0])
                val count = fields.last().trim().toIntOrNull() ?: continue
                // 文本里的制表符已转义，取中间整段以兼容历史未转义数据。
                val word = unescapeLineText(fields.subList(1, fields.size - 1).joinToString("\t"))
                if (code.isEmpty() || word.isEmpty()) continue
                val safeCount = count.coerceAtLeast(0)
                entries[keyOf(code, word)] = UserDictEntry(code, word, safeCount, weightOf(safeCount))
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
                    .append(entry.count).append('\n')
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

/** 条目 → 统一候选：单字走五笔候选，多字走短语候选。 */
private fun UserDictEntry.toCandidate(): LookupCandidate = LookupCandidate(
    code = code,
    display = text,
    insertText = text,
    weight = weight,
    kind = if (text.length == 1) LookupKind.WUBI else LookupKind.PHRASE
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
