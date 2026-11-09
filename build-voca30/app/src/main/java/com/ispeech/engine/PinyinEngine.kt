package com.ispeech.engine

/**
 * 拼音引擎设置。
 *
 * 设置变化后需要重建 [PinyinEngine]（索引在构造时按开关一次建好），
 * 与 AppContainer.invalidateEngine() 的既有做法一致。
 */
data class PinyinSettings(
    /** 模糊音开关：z=zh、c=ch、s=sh、n=l、l=r、h=f、an=ang、en=eng、in=ing、ian=iang、uan=uang。 */
    val fuzzy: Boolean = false,
    /** 简拼（首字母缩写，如 `nh` → 你好）开关。 */
    val initials: Boolean = true,
    /** 候选来源角标，默认拼音。 */
    val kind: LookupKind = LookupKind.PINYIN,
)

/**
 * 拼音引擎：全拼 / 简拼（首字母缩写）/ 前缀匹配 + 可选模糊音。
 *
 * 匹配分层（数值越小越靠前，层内按词频降序）：
 * 1. `TIER_EXACT`：整词全拼完全匹配（`nihao` → 你好；`ni` → 你）
 * 2. `TIER_FUZZY_EXACT`：模糊音归一化后完全匹配（模糊音开启时：`zongguo` → 中国）
 * 3. `TIER_PREFIX`：全拼前缀匹配（`zhong` → 中国 / 中心 / 中文…）
 * 4. `TIER_INITIALS` / `TIER_INITIALS_FUZZY`：简拼匹配（`nh` → 你好）
 *
 * 候选按 display 去重；词典为空（加载失败）时所有查询返回空列表。
 */
class PinyinEngine(
    private val dict: PinyinDict,
    private val settings: PinyinSettings = PinyinSettings(),
) : LookupEngine {

    override val displayName: String = "pinyin86"

    /** 全拼索引：键为全拼（模糊音开启时为归一化后的全拼），值为按词频降序的词条。 */
    private val byFull: Map<String, List<PinyinEntry>>

    /** [byFull] 的键，字典序升序，供前缀二分查找。 */
    private val sortedFull: List<String>

    /** 简拼索引：键为简拼（模糊音开启时 n→l），值为按词频降序的词条。 */
    private val byInitials: Map<String, List<PinyinEntry>>

    /** 音节表：由单字读音派生，模糊音切分用。 */
    private val syllables: Set<String>

    /** 词条总数；0 表示词典不可用。 */
    val entryCount: Int get() = dict.size

    /** 词典是否可用（用于界面提示 / 是否接入复合引擎）。 */
    val available: Boolean get() = dict.size > 0

    init {
        val fuzzy = settings.fuzzy
        val fullTable = HashMap<String, MutableList<PinyinEntry>>(dict.size)
        val initialTable = HashMap<String, MutableList<PinyinEntry>>(if (settings.initials) dict.size else 0)
        val syllableTable = HashSet<String>(512)

        for (entry in dict.entries) {
            if (!entry.isWord) {
                // 单字词条的 code 本身就是一个完整音节
                syllableTable.add(entry.code)
            }
            val fullKey = if (fuzzy) fuzzySyllables(entry.code) else entry.full
            fullTable.getOrPut(fullKey) { ArrayList(2) }.add(entry)
            if (settings.initials) {
                val initialKey = if (fuzzy) fuzzyInitials(entry.initials) else entry.initials
                initialTable.getOrPut(initialKey) { ArrayList(2) }.add(entry)
            }
        }

        byFull = sortBuckets(fullTable)
        sortedFull = byFull.keys.sorted()
        byInitials = if (settings.initials) sortBuckets(initialTable) else emptyMap()
        syllables = syllableTable
    }

    /**
     * 查询候选。
     *
     * @param code 用户输入的拼音（只取其中的 a-z，大小写不敏感；`ü` 请用 `v`）
     * @param limit 最多返回的候选数
     */
    override fun lookup(input: String, limit: Int): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val code = sanitize(input)
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH || dict.isEmpty) return emptyList()

        val pool = ArrayList<Match>(64)
        val seen = HashSet<String>(128)

        collectExact(code, pool, seen)
        collectPrefix(code, pool, seen)
        collectInitials(code, pool, seen)
        if (pool.isEmpty()) return emptyList()

        pool.sortWith(MATCH_RANK)
        val result = ArrayList<LookupCandidate>(minOf(limit, pool.size))
        var tier = Int.MIN_VALUE
        var singlesInTier = 0
        for (match in pool) {
            if (result.size >= limit) break
            if (match.tier != tier) {
                tier = match.tier
                singlesInTier = 0
            }
            // 同一层里的单字要限量：ni / zhong 这类音节的同音生僻字能塞满整个候选条，
            // 把「你好 / 中国」这种高频词（在下一层）挤出去。
            if (!match.entry.isWord) {
                if (singlesInTier >= SINGLE_PER_TIER_LIMIT) continue
                singlesInTier++
            }
            result.add(
                LookupCandidate(
                    code = match.entry.code,
                    display = match.entry.text,
                    insertText = match.entry.text,
                    weight = match.entry.weight,
                    kind = settings.kind,
                )
            )
        }
        return result
    }

    /** 人类可读的统计信息，多行文本。 */
    fun summary(): String {
        if (dict.isEmpty) return "拼音码表未加载"
        return buildString {
            append("词条总数: ${dict.size}\n")
            append("单字: ${dict.charCount}  词组: ${dict.wordCount}\n")
            append("全拼键: ${sortedFull.size}  简拼键: ${byInitials.size}  音节: ${syllables.size}\n")
            append("模糊音: ${if (settings.fuzzy) "开" else "关"}  简拼: ${if (settings.initials) "开" else "关"}")
        }
    }

    /** 完全匹配：命中 [byFull] 里的同一个键，再区分「原样完全匹配」与「模糊音归一化后匹配」。 */
    private fun collectExact(code: String, pool: MutableList<Match>, seen: MutableSet<String>) {
        val bucket = byFull[indexKey(code)] ?: return
        for (entry in bucket) {
            if (!seen.add(entry.text)) continue
            val tier = if (entry.full == code) TIER_EXACT else TIER_FUZZY_EXACT
            pool.add(Match(entry, tier))
        }
    }

    /**
     * 全拼前缀匹配：在 [sortedFull] 上二分后顺序扫描。
     *
     * 单字母输入（`n`）的前缀噪音过大，只做完全匹配与简拼。
     *
     * 前缀层是「先全量收集、再统一排序」，不做先到先得截断：字典序靠前的词条未必
     * 权重高（`ni` 的字典序早期是 拟/娘/鸟…，你好 排在很后面；`zhong` 里 中国 同理），
     * 所以这里**不按词条数截断**，只按「前缀键个数」限流，并且每个前缀键只取权重最高的
     * 前 [PREFIX_PER_KEY_LIMIT] 条（桶已按词频降序）。收集过程中候选池超过
     * [PREFIX_POOL_LIMIT] 时按分层排序裁掉尾部，保证内存与耗时都有界。
     */
    private fun collectPrefix(code: String, pool: MutableList<Match>, seen: MutableSet<String>) {
        if (code.length < MIN_PREFIX_LENGTH) return
        val key = indexKey(code)
        var index = lowerBound(key)
        var keys = 0
        while (index < sortedFull.size && keys < PREFIX_SCAN_LIMIT) {
            val candidateKey = sortedFull[index]
            if (!candidateKey.startsWith(key)) return
            val bucket = byFull[candidateKey]
            if (bucket != null) {
                var perKey = 0
                for (entry in bucket) {
                    if (perKey >= PREFIX_PER_KEY_LIMIT) break
                    perKey++
                    if (seen.add(entry.text)) pool.add(Match(entry, TIER_PREFIX))
                }
                if (pool.size > PREFIX_POOL_LIMIT) trimPool(pool)
            }
            index++
            keys++
        }
    }

    /** 候选池超限时按分层排序裁掉尾部（保留前一半，仍远大于候选条容量）。 */
    private fun trimPool(pool: MutableList<Match>) {
        pool.sortWith(MATCH_RANK)
        if (pool.size > PREFIX_POOL_LIMIT / 2) {
            pool.subList(PREFIX_POOL_LIMIT / 2, pool.size).clear()
        }
    }

    /** 简拼匹配：整串等于某个词条的简拼（单字母如 `n` 也会命中「你」等单字）。 */
    private fun collectInitials(code: String, pool: MutableList<Match>, seen: MutableSet<String>) {
        if (!settings.initials) return
        val bucket = byInitials[if (settings.fuzzy) fuzzyInitials(code) else code] ?: return
        for (entry in bucket) {
            if (!seen.add(entry.text)) continue
            val tier = if (entry.initials == code) TIER_INITIALS else TIER_INITIALS_FUZZY
            pool.add(Match(entry, tier))
        }
    }

    /** 查询串 → 索引键：模糊音开启时按音节归一化，否则原样。 */
    private fun indexKey(code: String): String =
        if (settings.fuzzy) fuzzyQuery(code) else code

    /**
     * 模糊音下的查询归一化。
     *
     * 索引侧的归一化是「逐音节」做的，所以查询侧必须先把连续字母切成音节：
     * 否则 `xiange`（先 + 给）这类跨音节串会被误当成含 `ang` 而错误丢一个 g。
     * 切不出音节时（多半是最后一个音节没输完）整段按「残缺音节」处理。
     */
    private fun fuzzyQuery(code: String): String {
        val segments = segmentSyllables(code, syllables)
        if (segments.isEmpty()) return code
        val out = StringBuilder(code.length)
        for (segment in segments) out.append(fuzzySyllable(segment))
        return out.toString()
    }

    /** 二分查找第一个 >= key 的下标。 */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = sortedFull.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedFull[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    /** 候选分层 + 层内排序。 */
    private class Match(val entry: PinyinEntry, val tier: Int)

    private companion object {
        /** 整词全拼完全匹配。 */
        const val TIER_EXACT = 0

        /** 模糊音归一化后完全匹配。 */
        const val TIER_FUZZY_EXACT = 1

        /** 全拼前缀匹配。 */
        const val TIER_PREFIX = 2

        /** 简拼精确匹配。 */
        const val TIER_INITIALS = 3

        /** 简拼经模糊音（n/r→l、h→f）归一化后匹配。 */
        const val TIER_INITIALS_FUZZY = 4

        /** 短于该长度的输入不做全拼前缀扫描。 */
        const val MIN_PREFIX_LENGTH = 2

        /**
         * 单次前缀扫描最多访问多少个「前缀键」。
         *
         * 注意这里是键数而不是词条数：按词条数截断会漏掉字典序靠后的高频词
         * （`zhong` 扫不到 中国、`ni` 扫不到 你好）。
         */
        const val PREFIX_SCAN_LIMIT = 4000

        /** 每个前缀键最多取前几条候选（桶已按词频降序，取头部即取高频词）。 */
        const val PREFIX_PER_KEY_LIMIT = 4

        /** 前缀候选池上限，超出即按分层裁剪，避免逐键收集时内存无界。 */
        const val PREFIX_POOL_LIMIT = 256

        /** 同一分层里最多放出几个单字，避免同音生僻字把高频词挤出候选条。 */
        const val SINGLE_PER_TIER_LIMIT = 6

        /** 输入码长度上限，超长输入直接返回空，避免无谓扫描。 */
        const val MAX_CODE_LENGTH = 32

        /** 层内排序：分层 → 词频降序 → 字数升序 → 汉字字典序，与码表行序一致。 */
        val MATCH_RANK: Comparator<Match> = Comparator { a, b ->
            if (a.tier != b.tier) return@Comparator a.tier - b.tier
            if (a.entry.weight != b.entry.weight) return@Comparator b.entry.weight - a.entry.weight
            if (a.entry.text.length != b.entry.text.length) {
                return@Comparator a.entry.text.length - b.entry.text.length
            }
            a.entry.text.compareTo(b.entry.text)
        }
    }
}

/** 按词频降序统一整理索引桶（词频相同按字数、汉字序稳定排列）。 */
private fun sortBuckets(table: Map<String, MutableList<PinyinEntry>>): Map<String, List<PinyinEntry>> {
    val out = HashMap<String, List<PinyinEntry>>(table.size)
    for ((key, list) in table) {
        list.sortWith(ENTRY_RANK)
        out[key] = list
    }
    return out
}

/** 词条排序：词频降序 → 字数升序 → 汉字字典序。 */
private val ENTRY_RANK: Comparator<PinyinEntry> = Comparator { a, b ->
    if (a.weight != b.weight) return@Comparator b.weight - a.weight
    if (a.text.length != b.text.length) return@Comparator a.text.length - b.text.length
    a.text.compareTo(b.text)
}

/**
 * 模糊音归一化单音节（对齐搜狗拼音 `Fuzzy.dat` 的 `[Gray]` 预置）：
 * 声母 z/zh、c/ch、s/sh 合并（去掉 h），n/l/r 三向合并（n、r → l），h/f 合并（h → f），
 * 韵母 an/ang、en/eng、in/ing、iang/ian、uang/uan 合并（去掉词尾 g）。
 */
private fun fuzzySyllable(syllable: String): String {
    val normalized = when {
        syllable.startsWith("zh") -> "z" + syllable.substring(2)
        syllable.startsWith("ch") -> "c" + syllable.substring(2)
        syllable.startsWith("sh") -> "s" + syllable.substring(2)
        // Fuzzy.dat: n=l 与 l=r 同时开启 ⇒ n/l/r 等价类，统一归一到 l
        syllable.startsWith("n") -> "l" + syllable.substring(1)
        syllable.startsWith("r") -> "l" + syllable.substring(1)
        // Fuzzy.dat: h=f
        syllable.startsWith("h") -> "f" + syllable.substring(1)
        else -> syllable
    }
    return when {
        normalized.endsWith("ang") || normalized.endsWith("eng") || normalized.endsWith("ing") ->
            normalized.dropLast(1)

        else -> normalized
    }
}

/**
 * 词条 code（空格分隔音节）→ 模糊音归一化后的全拼。
 */
private fun fuzzySyllables(code: String): String = buildString(code.length) {
    for (syllable in code.split(' ')) {
        if (syllable.isNotEmpty()) append(fuzzySyllable(syllable))
    }
}

/**
 * 简拼的模糊音归一化：逐字母做与 [fuzzySyllable] 一致的声母合并
 * （n/r → l，h → f）。
 *
 * 简拼是「每个音节的首字母」拼接，而声母归一化只影响首字母（z/zh、c/ch、s/sh 的首字母
 * 本来就是 z/c/s，韵母规则也不影响首字母），所以逐字母替换与原音节归一化等价，
 * 同时避免了把 `ang`（爱-那-国 的简拼）误当成韵母去尾。
 */
private fun fuzzyInitials(initials: String): String = buildString(initials.length) {
    for (ch in initials) {
        append(
            when (ch) {
                'n', 'r' -> 'l'
                'h' -> 'f'
                else -> ch
            }
        )
    }
}

/**
 * 把连续小写字母串切成音节（模糊音归一化用）：每次取最长匹配的已知音节；
 * 遇到切不动的剩余部分（用户还没输完整的尾音节）整体作为最后一段返回。
 */
private fun segmentSyllables(code: String, syllables: Set<String>): List<String> {
    if (code.isEmpty()) return emptyList()
    val out = ArrayList<String>(4)
    var index = 0
    while (index < code.length) {
        var matched = 0
        var length = minOf(MAX_SEGMENT_LENGTH, code.length - index)
        while (length >= 1) {
            if (code.substring(index, index + length) in syllables) {
                matched = length
                break
            }
            length--
        }
        if (matched == 0) {
            out.add(code.substring(index))
            break
        }
        out.add(code.substring(index, index + matched))
        index += matched
    }
    return out
}

/** 最长音节长度（`zhuang` / `chuang` / `shuang`），用于切分连续字母串。 */
private const val MAX_SEGMENT_LENGTH = 6

/** 只保留输入里的 a-z（大小写不敏感），其余字符（数字 / 标点 / 空格）忽略。 */
private fun sanitize(input: String): String {
    var needsFilter = false
    for (ch in input) {
        if (ch !in 'a'..'z' && ch !in 'A'..'Z') {
            needsFilter = true
            break
        }
    }
    if (!needsFilter) return input.lowercase()
    val out = StringBuilder(input.length)
    for (ch in input) {
        when {
            ch in 'a'..'z' -> out.append(ch)
            ch in 'A'..'Z' -> out.append(ch.lowercaseChar())
        }
    }
    return out.toString()
}
