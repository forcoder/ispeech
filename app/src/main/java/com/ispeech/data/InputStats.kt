package com.ispeech.data

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDate

/** 上屏类型：候选 / 英文 / 标点 / 短语 / emoji。 */
enum class CommitType {
    /** 从候选列表上屏（五笔单字、词组候选）。 */
    CANDIDATE,

    /** 英文字母直接上屏。 */
    ENGLISH,

    /** 标点 / 符号上屏。 */
    PUNCTUATION,

    /** 快捷短语、用户词典内容、AI 回复等长文本上屏。 */
    PHRASE,

    /** emoji 上屏。 */
    EMOJI;

    companion object {
        /** 解析持久化文本中的类型名；未知类型返回 null（读取时跳过该行）。 */
        fun parse(raw: String): CommitType? = entries.firstOrNull { it.name == raw }
    }
}

/** 某一类上屏的统计：次数 + 字数。 */
data class TypedCount(val type: CommitType, val commits: Int, val chars: Int)

/** 单日统计（跨天分桶的最小单位，日期为本地日期字符串 yyyy-MM-dd）。 */
data class DayStats(
    val date: String,
    val commits: Int,
    val chars: Int,
    val queries: Int,
    val byType: List<TypedCount>,
) {
    /**
     * 候选命中率 = 上屏次数 / 查询次数。
     *
     * - 查询次数为 0 时返回 0.0（避免除零）；
     * - 上限 1.0：历史数据或异常调用可能让上屏次数多于查询次数，比值不参与展示溢出。
     */
    val hitRate: Double
        get() = if (queries <= 0) 0.0 else minOf(1.0, commits.toDouble() / queries.toDouble())

    /** 某一类上屏次数。 */
    fun commitsOf(type: CommitType): Int = byType.firstOrNull { it.type == type }?.commits ?: 0

    /** 某一类上屏字数。 */
    fun charsOf(type: CommitType): Int = byType.firstOrNull { it.type == type }?.chars ?: 0

    companion object {
        /** 空日（该日期没有任何记录）。 */
        fun empty(date: String): DayStats = DayStats(
            date = date,
            commits = 0,
            chars = 0,
            queries = 0,
            byType = CommitType.entries.map { TypedCount(it, 0, 0) },
        )
    }
}

/** 最常用词条目。 */
data class WordStat(val text: String, val count: Int)

/**
 * 统计快照：只读视图，供设置页展示。
 *
 * @param today 今日分桶（无记录时为零值）
 * @param total 累计（所有分桶求和）
 * @param days 全部日期分桶，日期升序
 * @param topWords 最常用词 Top [InputStats.TOP_WORDS_LIMIT]，频次降序、同频次文本升序
 */
data class StatsSnapshot(
    val today: DayStats,
    val total: DayStats,
    val days: List<DayStats>,
    val topWords: List<WordStat>,
) {
    /** 累计命中率。 */
    val hitRate: Double get() = total.hitRate

    /** 有记录的统计天数。 */
    val trackedDays: Int get() = days.size

    companion object {
        /** 空快照：首次使用 / 读取失败时的兜底值。 */
        val EMPTY: StatsSnapshot = StatsSnapshot(
            today = DayStats.empty(""),
            total = DayStats.empty(""),
            days = emptyList(),
            topWords = emptyList(),
        )
    }
}

/**
 * 输入统计存储：每日输入字数、上屏次数（按类型分）、候选命中率、最常用词 Top 50。
 *
 * 持久化在 filesDir/input_stats.txt，行为对齐 [UserDictStore] / [ClipboardHistory]：
 * 懒加载、读写容错（异常只打日志，绝不抛给输入法）、原子写（临时文件 + rename）。
 * 跨天分桶用**本地日期字符串**（`LocalDate.now().toString()`，yyyy-MM-dd），
 * 因此日期字符串的字典序与时间序一致，裁剪旧分桶时可直接比较。
 *
 * 文件格式（`#` 开头为注释，`\t` 分隔；文本里的换行 / 制表符按 [escapeLineText] 转义）：
 * ```
 * q\t2026-10-17\t120                      查询次数（命中率分母）
 * c\t2026-10-17\tCANDIDATE\t30\t62        某类型：上屏次数 / 上屏字数
 * w\t你好\t12                              最常用词：文本 / 频次
 * ```
 * 累计值不单独落盘，一律由分桶求和得到，避免出现「累计与分桶不一致」的两套真相。
 *
 * 线程模型：所有公开方法都加锁且会在调用线程上做文件 IO，
 * 输入法侧请放到 IO 线程调用（见 docs/stats-and-hanconversion.md 的接线片段）。
 */
class InputStats private constructor(private val file: File) {

    /** 日期 → 分桶。 */
    private val days = LinkedHashMap<String, DayBucket>()

    /** 词 → 频次（只统计中文词类上屏，见 [WORD_TYPES]）。 */
    private val words = HashMap<String, Int>()

    /** 懒加载标记：保证首次访问才读盘。 */
    private var loaded = false

    companion object {
        /** 文件名（位于 filesDir 下）。 */
        const val FILE_NAME = "input_stats.txt"

        /** 最常用词保留条数。 */
        const val TOP_WORDS_LIMIT = 50

        private const val TAG = "InputStats"

        /** 文件头注释行。 */
        private const val HEADER = "# 输入统计（自动生成，可在设置页查看 / 重置）"

        /** 记录类型标记：查询 / 上屏 / 词频。 */
        private const val MARK_QUERY = "q"
        private const val MARK_COMMIT = "c"
        private const val MARK_WORD = "w"

        /** 最多保留的日期分桶数，超出按日期倒序保留最新的一批。 */
        private const val MAX_TRACKED_DAYS = 400

        /** 内存中最多保留的词条数（展示只取 Top 50，留出余量避免频繁裁剪）。 */
        private const val MAX_TRACKED_WORDS = 500

        /** 计入「最常用词」的文本上限（码点数），过长说明是长句而不是词。 */
        private const val MAX_WORD_LENGTH = 32

        /** 计入词频统计的上屏类型：候选与短语。 */
        private val WORD_TYPES: Set<CommitType> = setOf(CommitType.CANDIDATE, CommitType.PHRASE)

        /** 词频排序：频次降序，其次文本升序（保证相同频次时顺序稳定）。 */
        private val WORD_RANK: Comparator<Map.Entry<String, Int>> =
            Comparator { a, b ->
                val byCount = b.value.compareTo(a.value)
                if (byCount != 0) byCount else a.key.compareTo(b.key)
            }

        private val cacheLock = Any()

        @Volatile
        private var cached: InputStats? = null

        /** 统计文件：filesDir/input_stats.txt */
        fun fileFor(context: Context): File = File(context.filesDir, FILE_NAME)

        /** 打开（不存在则不建文件，首次写入时再创建；读写均容错）。 */
        fun open(file: File): InputStats = InputStats(file)

        /**
         * 进程内单例：输入法服务与设置页共用同一实例，避免两处各自读盘出现脏数据。
         *
         * 刻意不走 AppContainer，是因为统计属于旁路功能，不希望为了它改动容器接线。
         */
        fun get(context: Context): InputStats {
            cached?.let { return it }
            synchronized(cacheLock) {
                cached?.let { return it }
                val created = open(fileFor(context.applicationContext))
                cached = created
                return created
            }
        }

        /** 丢弃单例（重置 / 测试用），下次 [get] 重新读盘。 */
        fun invalidate() {
            synchronized(cacheLock) { cached = null }
        }

        /** 当前本地日期字符串（yyyy-MM-dd）。 */
        internal fun todayKey(): String = LocalDate.now().toString()

        /** 字数：按**码点**计数并忽略空白（emoji 记 1 个字，空格 / 换行不计）。 */
        internal fun charCountOf(text: String): Int {
            var count = 0
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    count += 1
                    i += 2
                } else {
                    if (!ch.isWhitespace()) count += 1
                    i += 1
                }
            }
            return count
        }
    }

    /** 当日快照；文件缺失 / 损坏时返回零值快照，不抛异常。 */
    @Synchronized
    fun snapshot(dateKey: String = todayKey()): StatsSnapshot {
        ensureLoaded()
        return StatsSnapshot(
            today = days[dateKey]?.toDayStats(dateKey) ?: DayStats.empty(dateKey),
            total = totalStats(),
            days = days.entries.sortedBy { it.key }.map { it.value.toDayStats(it.key) },
            topWords = topWords(),
        )
    }

    /**
     * 记录一次上屏。
     *
     * @param type 上屏类型
     * @param text 上屏文本（空白忽略、超长仍计入字数，但不进词频）
     * @param dateKey 分桶日期，默认今天（测试可注入固定日期）
     */
    @Synchronized
    fun recordCommit(type: CommitType, text: String, dateKey: String = todayKey()) {
        ensureLoaded()
        val chars = charCountOf(text)
        val bucket = days.getOrPut(dateKey) { DayBucket() }
        bucket.commits += 1
        bucket.byType[type] = (bucket.byType[type] ?: 0) + 1
        bucket.byTypeChars[type] = (bucket.byTypeChars[type] ?: 0) + chars
        if (type in WORD_TYPES) recordWord(text)
        prune()
        persist()
    }

    /**
     * 记录一次候选查询（命中率的分母）。
     *
     * 查询内容本身不落盘（只需计数），未来若要做「查了什么 / 没命中什么」再加字段。
     *
     * @param dateKey 分桶日期，默认今天
     */
    @Synchronized
    fun recordQuery(dateKey: String = todayKey()) {
        ensureLoaded()
        val bucket = days.getOrPut(dateKey) { DayBucket() }
        bucket.queries += 1
        prune()
        persist()
    }

    /** 清空全部统计（今日 + 累计 + 词频）。 */
    @Synchronized
    fun reset() {
        ensureLoaded()
        days.clear()
        words.clear()
        persist()
    }

    // ──────────────────────────────────────────────
    //  内部
    // ──────────────────────────────────────────────

    /** 单日可变分桶。 */
    private class DayBucket {
        var commits = 0
        var queries = 0
        val byType = LinkedHashMap<CommitType, Int>()
        val byTypeChars = LinkedHashMap<CommitType, Int>()

        /** 该日总字数 = 各类型字数之和（口径与落盘字段一致）。 */
        fun chars(): Int = byTypeChars.values.sum()

        fun toDayStats(date: String): DayStats = DayStats(
            date = date,
            commits = commits,
            chars = chars(),
            queries = queries,
            byType = CommitType.entries.map { TypedCount(it, byType[it] ?: 0, byTypeChars[it] ?: 0) },
        )
    }

    /** 词频累计（只保留有意义的词，超长文本丢弃）。 */
    private fun recordWord(text: String) {
        val word = text.trim()
        if (word.isEmpty() || charCountOf(word) > MAX_WORD_LENGTH) return
        words[word] = (words[word] ?: 0) + 1
    }

    /** 累计统计：所有分桶求和。 */
    private fun totalStats(): DayStats {
        var commits = 0
        var chars = 0
        var queries = 0
        val byType = LinkedHashMap<CommitType, Int>()
        val byTypeChars = LinkedHashMap<CommitType, Int>()
        for (bucket in days.values) {
            commits += bucket.commits
            chars += bucket.chars()
            queries += bucket.queries
            for ((type, value) in bucket.byType) byType[type] = (byType[type] ?: 0) + value
            for ((type, value) in bucket.byTypeChars) byTypeChars[type] = (byTypeChars[type] ?: 0) + value
        }
        return DayStats(
            date = "",
            commits = commits,
            chars = chars,
            queries = queries,
            byType = CommitType.entries.map {
                TypedCount(it, byType[it] ?: 0, byTypeChars[it] ?: 0)
            },
        )
    }

    /** 最常用词 Top [TOP_WORDS_LIMIT]。 */
    private fun topWords(): List<WordStat> =
        words.entries.sortedWith(WORD_RANK).take(TOP_WORDS_LIMIT).map { WordStat(it.key, it.value) }

    /** 裁剪：分桶只留最新 [MAX_TRACKED_DAYS] 天，词条只留 Top [MAX_TRACKED_WORDS]。 */
    private fun prune() {
        if (days.size > MAX_TRACKED_DAYS) {
            val keep = days.keys.sortedDescending().take(MAX_TRACKED_DAYS).toHashSet()
            days.keys.retainAll(keep)
        }
        if (words.size > MAX_TRACKED_WORDS) {
            val keep = words.entries.sortedWith(WORD_RANK).take(MAX_TRACKED_WORDS)
            words.clear()
            for (entry in keep) words[entry.key] = entry.value
        }
    }

    /** 懒加载：文件缺失 / 损坏一律当空，不抛异常。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            for (rawLine in file.readText(Charsets.UTF_8).split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                when (fields.firstOrNull()) {
                    MARK_QUERY -> {
                        val date = fields.getOrNull(1) ?: continue
                        val queries = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: continue
                        if (queries <= 0) continue
                        days.getOrPut(date) { DayBucket() }.queries = queries
                    }

                    MARK_COMMIT -> {
                        val date = fields.getOrNull(1) ?: continue
                        val type = CommitType.parse(fields.getOrNull(2) ?: "") ?: continue
                        val commits = fields.getOrNull(3)?.trim()?.toIntOrNull() ?: continue
                        val chars = fields.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
                        val bucket = days.getOrPut(date) { DayBucket() }
                        bucket.byType[type] = (bucket.byType[type] ?: 0) + commits.coerceAtLeast(0)
                        bucket.byTypeChars[type] =
                            (bucket.byTypeChars[type] ?: 0) + chars.coerceAtLeast(0)
                        bucket.commits += commits.coerceAtLeast(0)
                    }

                    MARK_WORD -> {
                        val word = unescapeLineText(fields.getOrNull(1) ?: continue)
                        val count = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: continue
                        if (word.isEmpty() || count <= 0) continue
                        words[word] = (words[word] ?: 0) + count
                    }

                    else -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "读取输入统计失败: ${file.path}", it) }
        prune()
    }

    /**
     * 原子写：先写同目录临时文件再 rename，避免掉电 / 进程被杀留下半截文件。
     * renameTo 在个别文件系统上不支持覆盖已存在文件，此时退回直接写入。
     */
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val payload = serialize()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(payload, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "写入输入统计失败: ${file.path}", it) }
    }

    /** 序列化：日期升序 + 类型按枚举顺序 + 词按频次，保证同样数据写出同样字节。 */
    private fun serialize(): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for ((date, bucket) in days.entries.sortedBy { it.key }) {
            sb.append(MARK_QUERY).append('\t').append(date).append('\t')
                .append(bucket.queries).append('\n')
            for (type in CommitType.entries) {
                val commits = bucket.byType[type] ?: continue
                sb.append(MARK_COMMIT).append('\t').append(date).append('\t').append(type.name)
                    .append('\t').append(commits)
                    .append('\t').append(bucket.byTypeChars[type] ?: 0).append('\n')
            }
        }
        for (entry in words.entries.sortedWith(WORD_RANK)) {
            sb.append(MARK_WORD).append('\t').append(escapeLineText(entry.key))
                .append('\t').append(entry.value).append('\n')
        }
        return sb.toString()
    }
}

/** Char 是否为高 / 低代理项（不依赖 java.lang.Character，便于纯逻辑测试）。 */
private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'

private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
