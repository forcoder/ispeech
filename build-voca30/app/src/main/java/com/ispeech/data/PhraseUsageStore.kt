package com.ispeech.data

import android.util.Log
import com.ispeech.engine.QuickEntry
import java.io.File

/**
 * 单条快捷短语的使用记录：收藏、使用次数、最后一次使用时间。
 *
 * [id] 为短语稳定标识（见 [PhraseUsageStore.idOf]），[code] / [text] 只是「便于阅读与迁移」
 * 的冗余快照，上屏内容始终以 QuickEntry.content 为准，因此本记录不参与渲染。
 */
data class PhraseUsage(
    val id: String,
    val code: String,
    val text: String,
    val starred: Boolean,
    val count: Int,
    val lastUsedMs: Long,
    /** 单调序号：同一毫秒内的多次使用也能保持确定的先后顺序。 */
    val tick: Long,
)

/** [PhraseUsageStore.parse] 的结果：已按 id 去重合并 + 损坏行统计。 */
data class PhraseUsageParse(
    val records: List<PhraseUsage>,
    /** 无法解析的行数（字段不足 / 数字非法 / id 为空）。 */
    val skippedLines: Int,
    /** 与前面行 id 重复、已按合并策略并入的行数。 */
    val duplicateLines: Int,
    /** 因 code+text 重算出的 id 与文件里的 id 不一致而迁移的行数。 */
    val migratedIds: Int,
)

/**
 * 快捷短语使用记录：filesDir/phrase_usage.txt。
 *
 * 职责：收藏（starred）、最近使用（按时间倒序，上限 [RECENT_LIMIT]）、使用计数、最后一次使用时间。
 * 设计要点（与 UserDictStore / ClipboardHistory 保持一致的存储风格，无第三方依赖）：
 * - 懒加载 + `@Synchronized`：首次访问才读盘，读写在对象锁内串行，避免并发写坏文件；
 * - 文本格式（TAB 分隔、`#` 注释），复用 [escapeLineText] / [unescapeLineText] 转义换行与制表符；
 * - 原子写入：先写 `*.tmp` 再 rename，避免掉电 / 进程被杀时留下半截文件；
 * - 容错读取：文件缺失、内容损坏、字段非法一律跳过（不抛异常），并把可疑原文备份到 `*.corrupt`；
 * - 淘汰策略：收藏记录永不淘汰，未收藏记录只保留最近 [RECENT_LIMIT] 条，文件体积有上限。
 *
 * 线程模型：所有公开方法都加锁，文件 IO 在调用线程内完成；请在 IO 线程调用
 * （输入法服务里用 `withContext(Dispatchers.IO)`），不要在渲染主线程做批量写。
 */
class PhraseUsageStore private constructor(private val file: File) {

    /** id -> 记录；顺序为「收藏优先 + 最近使用靠前」，与 [format] 的落盘顺序一致。 */
    private val records = LinkedHashMap<String, PhraseUsage>()

    /** 懒加载标记：保证首次访问才读盘。 */
    private var loaded = false

    /** 单调序号计数器，保证同毫秒多次使用也有确定顺序。 */
    private var tickCounter = 0L

    /** 测试用时钟注入；为 null 时用 System.currentTimeMillis()。 */
    @Volatile
    internal var clockForTest: (() -> Long)? = null

    companion object {
        private const val TAG = "PhraseUsageStore"

        /** 落盘文件名（位于 filesDir 下），由调用方拼路径后交给 [open]。 */
        const val FILE_NAME = "phrase_usage.txt"

        /** 「最近使用」保留条数上限；未收藏记录超出后按时间淘汰。 */
        const val RECENT_LIMIT = 50

        /** 面板「收藏 / 最近」胶囊默认取多少条。 */
        const val DEFAULT_TOP_LIMIT = 12

        /** id 版本前缀：哈希算法或归一化规则变化时递增，触发迁移。 */
        const val ID_PREFIX = "p1"

        /** id 归一化用的键分隔符（不可见于正常编码 / 文本）。 */
        const val ID_SEP = "\u0001"

        /** 文件头注释行。 */
        private const val HEADER = "# 快捷短语使用记录（自动生成：收藏 / 最近使用 / 使用次数）"

        /** 每行字段数：id \t code \t text \t star \t count \t lastUsedMs \t tick */
        private const val FIELD_COUNT = 7

        /** 原子写入的临时文件后缀。 */
        private const val TEMP_SUFFIX = ".tmp"

        /** 损坏文件备份后缀（同目录，便于人工找回）。 */
        private const val CORRUPT_SUFFIX = ".corrupt"

        /** 单次读取上限：512 KiB，超过视为损坏，避免被超大文件拖死。 */
        private const val MAX_FILE_BYTES = 524_288L

        /** code / text 快照截断长度，保证 id 与落盘内容完全一致。 */
        private const val MAX_CODE_LENGTH = 64
        private const val MAX_TEXT_LENGTH = 2000

        /** 使用计数上限，避免长期使用后出现无意义的巨大数字。 */
        private const val MAX_COUNT = 999_999

        /** FNV-1a 64 位常量（与 tools/verify/check_phrase_usage.py 必须一致）。 */
        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // = 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L

        /** 打开存储：不存在则视为空，读写均容错、绝不抛异常。 */
        fun open(file: File): PhraseUsageStore {
            val store = PhraseUsageStore(file)
            // 清理上一次写入残留的临时文件，避免误读半截数据。
            runCatching {
                val tmp = File(file.absoluteFile.parentFile, file.name + TEMP_SUFFIX)
                if (tmp.exists()) tmp.delete()
            }.onFailure { Log.w(TAG, "清理临时文件失败: ${file.path}", it) }
            return store
        }

        /**
         * 短语稳定标识 = `p1-` + FNV-1a 64(归一化 code + \u0001 + 归一化 text) 的 16 位十六进制。
         *
         * 归一化规则（**去重策略**，两条短语只有归一化后完全一致才算同一条）：
         * - code：去首尾空白 + 转小写（触发码大小写不敏感）、截断到 64 字符；
         * - text：`\r\n` / `\r` 统一成 `\n`、去首尾空白、截断到 2000 字符。
         * 因此「TRIGGER + 相同正文」与「trigger + 相同正文（含不同换行风格 / 首尾空格）」算同一条；
         * 「同正文 + 不同触发码」算两条（多入口短语各自独立计数）。
         *
         * 这是**纯函数**：不依赖 Context / 文件，面板与服务可以在任意线程直接调用。
         */
        fun idOf(code: String, text: String): String {
            val normalized = normalizeCode(code) + ID_SEP + normalizeText(text)
            return ID_PREFIX + "-" + hex16(fnv1a64(normalized.toByteArray(Charsets.UTF_8)))
        }

        /** 短语条目 -> 稳定标识（用 trigger + content，与 [recordUse] 的入参一致）。 */
        fun idOf(entry: QuickEntry): String = idOf(entry.trigger, entry.content)

        /** 解析文本为记录列表：逐行容错，坏行只统计不抛异常。 */
        fun parse(raw: String): PhraseUsageParse {
            val merged = LinkedHashMap<String, PhraseUsage>()
            var skipped = 0
            var duplicates = 0
            var migrated = 0
            for (rawLine in raw.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < FIELD_COUNT) {
                    skipped++
                    continue
                }
                val storedId = unescapeLineText(fields[0]).trim()
                val code = normalizeCode(unescapeLineText(fields[1]))
                val text = normalizeText(unescapeLineText(fields[2]))
                val starred = fields[3].trim() == "1"
                val count = fields[4].trim().toIntOrNull()
                val lastUsed = fields[5].trim().toLongOrNull()
                val tick = fields[6].trim().toLongOrNull()
                if (storedId.isEmpty() || count == null || lastUsed == null || tick == null) {
                    skipped++
                    continue
                }
                // 迁移：能用 code+text 重算 id 时以重算值为准（哈希版本变更 / 手工编辑过 code+text）。
                val recomputed = if (code.isNotEmpty() && text.isNotEmpty()) idOf(code, text) else storedId
                if (recomputed != storedId) migrated++
                val record = PhraseUsage(
                    id = recomputed,
                    code = code,
                    text = text,
                    starred = starred,
                    count = count.coerceIn(0, MAX_COUNT),
                    lastUsedMs = lastUsed.coerceAtLeast(0L),
                    tick = tick.coerceAtLeast(0L),
                )
                val previous = merged[record.id]
                if (previous == null) {
                    merged[record.id] = record
                } else {
                    duplicates++
                    merged[record.id] = mergeUsage(previous, record)
                }
            }
            return PhraseUsageParse(merged.values.toList(), skipped, duplicates, migrated)
        }

        /** 序列化为文本：`#` 注释头 + 每条一行，字段与 [parse] 对称。 */
        fun format(records: Collection<PhraseUsage>): String {
            val sb = StringBuilder()
            sb.append(HEADER).append('\n')
            sb.append("# 字段：id\\tcode\\ttext\\tstar(0/1)\\tcount\\tlastUsedMs\\ttick\n")
            for (record in records) {
                sb.append(escapeLineText(record.id)).append('\t')
                    .append(escapeLineText(record.code)).append('\t')
                    .append(escapeLineText(record.text)).append('\t')
                    .append(if (record.starred) '1' else '0').append('\t')
                    .append(record.count).append('\t')
                    .append(record.lastUsedMs).append('\t')
                    .append(record.tick).append('\n')
            }
            return sb.toString()
        }

        /**
         * 重复记录合并策略（同 id 出现多行时，例如崩溃残留 / 手工拼接 / 迁移重叠）：
         * - starred：取或（收藏状态最不容易恢复，优先保留）；
         * - count：取最大而不是相加（重复行不代表真实使用次数）；
         * - lastUsedMs / tick：取最新；
         * - code / text：优先取较新的一条，为空时回退到另一条。
         */
        internal fun mergeUsage(a: PhraseUsage, b: PhraseUsage): PhraseUsage {
            val newer = if (b.lastUsedMs >= a.lastUsedMs) b else a
            val older = if (newer === a) b else a
            return PhraseUsage(
                id = a.id,
                code = newer.code.ifEmpty { older.code },
                text = newer.text.ifEmpty { older.text },
                starred = a.starred || b.starred,
                count = maxOf(a.count, b.count),
                lastUsedMs = maxOf(a.lastUsedMs, b.lastUsedMs),
                tick = maxOf(a.tick, b.tick),
            )
        }

        /** code 归一化：去首尾空白 + 小写 + 截断。 */
        internal fun normalizeCode(code: String): String =
            code.trim().lowercase().take(MAX_CODE_LENGTH)

        /** 正文归一化：统一换行 + 去首尾空白 + 截断。 */
        internal fun normalizeText(text: String): String =
            text.replace("\r\n", "\n").replace('\r', '\n').trim().take(MAX_TEXT_LENGTH)

        /** FNV-1a 64 位哈希（对 UTF-8 字节流），溢出按 64 位回绕。 */
        internal fun fnv1a64(bytes: ByteArray): Long {
            var hash = FNV_OFFSET_BASIS
            for (b in bytes) {
                hash = hash xor (b.toLong() and 0xFFL)
                hash *= FNV_PRIME
            }
            return hash
        }

        /** 64 位哈希 -> 固定 16 位小写十六进制（不依赖 Locale / 格式化器）。 */
        internal fun hex16(value: Long): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder(16)
            for (shift in 60 downTo 0 step 4) {
                sb.append(digits[((value ushr shift) and 0xFL).toInt()])
            }
            return sb.toString()
        }
    }

    /** 全部记录：收藏优先，其次最近使用靠前。 */
    @Synchronized
    fun all(): List<PhraseUsage> {
        ensureLoaded()
        return ranked()
    }

    /** 收起的使用记录；没有记录时返回 null（计数为 0 也返回记录，只有从未记录过才是 null）。 */
    @Synchronized
    fun usage(id: String): PhraseUsage? {
        val key = id.trim()
        if (key.isEmpty()) return null
        ensureLoaded()
        return records[key]
    }

    /** 是否已收藏。 */
    @Synchronized
    fun isStarred(id: String): Boolean = usage(id)?.starred == true

    /** 收藏数量（面板胶囊 / 设置页统计用）。 */
    @Synchronized
    fun starCount(): Int {
        ensureLoaded()
        return records.values.count { it.starred }
    }

    /** 使用次数；从未记录过返回 0。 */
    @Synchronized
    fun countOf(id: String): Int = usage(id)?.count ?: 0

    /** 最近使用：按时间倒序（收藏与否都包含），最多 [limit] 条。 */
    @Synchronized
    fun recent(limit: Int = RECENT_LIMIT): List<PhraseUsage> {
        if (limit <= 0) return emptyList()
        ensureLoaded()
        return records.values.sortedWith(USAGE_RANK).take(limit)
    }

    /** [recent] 的 id 列表（面板只需要 key 时用，避免多余对象）。 */
    @Synchronized
    fun recentIds(limit: Int = RECENT_LIMIT): List<String> =
        recent(limit).map { it.id }

    /**
     * 面板「收藏 / 最近」区数据源：**收藏优先**（组内按最近使用倒序），
     * 其后是未收藏的最近使用，两组不会出现重复 id。
     *
     * [knownIds] 非空时只返回其中的 id（用于过滤已被删除 / 未加载的规则，
     * 例如远端规则关闭时不要把远端短语的收藏显示出来）。
     */
    @Synchronized
    fun topOrRecent(limit: Int = DEFAULT_TOP_LIMIT, knownIds: Set<String>? = null): List<PhraseUsage> {
        if (limit <= 0) return emptyList()
        ensureLoaded()
        val starred = ArrayList<PhraseUsage>()
        val rest = ArrayList<PhraseUsage>()
        for (record in records.values) {
            if (knownIds != null && record.id !in knownIds) continue
            if (record.starred) starred.add(record) else rest.add(record)
        }
        starred.sortWith(USAGE_RANK)
        rest.sortWith(USAGE_RANK)
        val out = ArrayList<PhraseUsage>(starred.size + rest.size)
        out.addAll(starred)
        out.addAll(rest)
        return out.take(limit)
    }

    /** [topOrRecent] 的 id 列表。 */
    @Synchronized
    fun topOrRecentIds(limit: Int = DEFAULT_TOP_LIMIT, knownIds: Set<String>? = null): List<String> =
        topOrRecent(limit, knownIds).map { it.id }

    /**
     * 切换收藏；返回切换后的状态。
     *
     * 收藏动作本身也算一次「使用信号」：新收藏会把 lastUsedMs / tick 顶到最新，
     * 这样刚收藏的短语立刻出现在「收藏」组首位。取消收藏且从未使用过的记录会被直接删除。
     */
    @Synchronized
    fun toggleStar(id: String, code: String, text: String): Boolean {
        val key = id.trim()
        if (key.isEmpty()) return false
        ensureLoaded()
        val previous = records[key]
        val starred = !(previous?.starred ?: false)
        if (!starred && (previous?.count ?: 0) == 0) {
            // 取消收藏 + 从未使用 => 没有任何可保留的信息，直接删除。
            records.remove(key)
            persist()
            return false
        }
        val safeCode = normalizeCode(code)
        val safeText = normalizeText(text)
        val record = PhraseUsage(
            id = key,
            code = if (safeCode.isEmpty()) previous?.code.orEmpty() else safeCode,
            text = if (safeText.isEmpty()) previous?.text.orEmpty() else safeText,
            starred = starred,
            count = previous?.count ?: 0,
            lastUsedMs = if (starred) nowMs() else previous?.lastUsedMs ?: 0L,
            tick = if (starred) ++tickCounter else previous?.tick ?: 0L,
        )
        records[key] = record
        trim()
        persist()
        return starred
    }

    /** 切换收藏（只有 id 时用；后续 [recordUse] 可以用 code+text 补齐快照）。 */
    @Synchronized
    fun toggleStar(id: String): Boolean = toggleStar(id, "", "")

    /** 切换收藏（面板行点击星标时用）。 */
    @Synchronized
    fun toggleStar(entry: QuickEntry): Boolean = toggleStar(idOf(entry), entry.trigger, entry.content)

    /**
     * 记录一次使用：计数 +1、刷新最后使用时间、加入最近使用首位。
     *
     * [code] / [text] 用于补齐记录快照（允许为空：面板只传 id 时先记计数，之后再补齐）。
     */
    @Synchronized
    fun recordUse(id: String, code: String, text: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        ensureLoaded()
        val previous = records[key]
        val safeCode = normalizeCode(code)
        val safeText = normalizeText(text)
        val record = PhraseUsage(
            id = key,
            code = if (safeCode.isEmpty()) previous?.code.orEmpty() else safeCode,
            text = if (safeText.isEmpty()) previous?.text.orEmpty() else safeText,
            starred = previous?.starred ?: false,
            count = ((previous?.count ?: 0) + 1).coerceAtMost(MAX_COUNT),
            lastUsedMs = nowMs(),
            tick = ++tickCounter,
        )
        records[key] = record
        trim()
        persist()
    }

    /** 记录一次使用（只有 id + 计数）。 */
    @Synchronized
    fun recordUse(id: String) {
        recordUse(id, "", "")
    }

    /** 记录一次使用（面板点选条目时首选：一次调用写全 id + 快照）。 */
    @Synchronized
    fun recordUse(entry: QuickEntry) {
        recordUse(idOf(entry), entry.trigger, entry.content)
    }

    /** 删除某条记录（无论是否收藏），返回是否删除成功。 */
    @Synchronized
    fun forget(id: String): Boolean {
        val key = id.trim()
        if (key.isEmpty()) return false
        ensureLoaded()
        val removed = records.remove(key) != null
        if (removed) persist()
        return removed
    }

    /**
     * 清理孤儿记录：删除 id 不在 [knownIds] 中的所有记录（含收藏），返回删除条数。
     *
     * 传参必须是「内置 + 远端 + 用户」全量规则的 id 集合
     * （`entries.map { PhraseUsageStore.idOf(it) }.toSet()`）；只传子集会把暂时未加载的规则
     * （例如关闭了远程规则）的收藏一并清掉。
     */
    @Synchronized
    fun prune(knownIds: Set<String>): Int {
        ensureLoaded()
        var removed = 0
        for (key in ArrayList(records.keys)) {
            if (key !in knownIds) {
                records.remove(key)
                removed++
            }
        }
        if (removed > 0) persist()
        return removed
    }

    /** 清空全部使用记录（设置页「清空使用记录」）。 */
    @Synchronized
    fun clear() {
        ensureLoaded()
        records.clear()
        tickCounter = 0L
        persist()
    }

    /** 懒加载：文件缺失 / 损坏一律当空，不抛异常。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            if (file.length() > MAX_FILE_BYTES) {
                backupCorrupt("文件超过 ${MAX_FILE_BYTES / 1024} KiB")
                return
            }
            val raw = file.readText(Charsets.UTF_8)
            val parsed = parse(raw)
            if (parsed.skippedLines > 0) {
                backupCorrupt("存在 ${parsed.skippedLines} 行无法解析")
            }
            for (record in parsed.records) {
                val previous = records[record.id]
                records[record.id] = if (previous == null) record else mergeUsage(previous, record)
            }
            tickCounter = records.values.maxOfOrNull { it.tick } ?: 0L
            if (parsed.migratedIds > 0) {
                Log.w(TAG, "短语使用记录已迁移 ${parsed.migratedIds} 条 id，重写 ${file.path}")
                tryPersist()
            }
        }.onFailure { Log.w(TAG, "读取短语使用记录失败: ${file.path}", it) }
    }

    /** 淘汰：收藏永不淘汰；未收藏只保留最近 [RECENT_LIMIT] 条。 */
    private fun trim() {
        val unstarred = records.values.filter { !it.starred }.sortedWith(USAGE_RANK)
        if (unstarred.size <= RECENT_LIMIT) return
        for (record in unstarred.subList(RECENT_LIMIT, unstarred.size)) {
            records.remove(record.id)
        }
    }

    /** 收藏优先 + 最近使用靠前（落盘顺序与 [all] 一致，便于人工查看）。 */
    private fun ranked(): List<PhraseUsage> {
        val starred = records.values.filter { it.starred }.sortedWith(USAGE_RANK)
        val rest = records.values.filter { !it.starred }.sortedWith(USAGE_RANK)
        return starred + rest
    }

    private fun nowMs(): Long = clockForTest?.invoke() ?: System.currentTimeMillis()

    /** 写回文件：IO 异常吞掉并打日志。 */
    private fun persist() {
        tryPersist()
    }

    private fun tryPersist() {
        val text = format(ranked())
        runCatching {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val tmp = if (parent == null) file else File(parent, file.name + TEMP_SUFFIX)
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 某些文件系统不允许 rename 覆盖已存在文件：退化为直接覆盖写。
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "写入短语使用记录失败: ${file.path}", it) }
    }

    /** 把可疑原文另存一份，避免容错读取时静默丢数据。 */
    private fun backupCorrupt(reason: String) {
        runCatching {
            val parent = file.absoluteFile.parentFile
            val backup = if (parent == null) {
                File(file.path + CORRUPT_SUFFIX)
            } else {
                File(parent, file.name + CORRUPT_SUFFIX)
            }
            file.copyTo(backup, overwrite = true)
            Log.w(TAG, "短语使用记录疑似损坏（$reason），原文已备份到 ${backup.path}")
        }.onFailure { Log.w(TAG, "备份损坏文件失败: ${file.path}", it) }
    }
}

/** 使用记录排序：最近使用倒序 -> tick 倒序 -> 次数倒序 -> id 升序（保证完全确定）。 */
private val USAGE_RANK: Comparator<PhraseUsage> = Comparator { a, b ->
    val byTime = b.lastUsedMs.compareTo(a.lastUsedMs)
    when {
        byTime != 0 -> byTime
        b.tick != a.tick -> b.tick.compareTo(a.tick)
        b.count != a.count -> b.count.compareTo(a.count)
        else -> a.id.compareTo(b.id)
    }
}
