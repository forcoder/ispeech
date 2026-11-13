package com.ispeech.engine

import kotlin.random.Random

/**
 * 五笔 86 引擎：解析 tab 分隔的词典文本并提供编码查询。
 *
 * 词典行格式：code <TAB> text <TAB> weight [<TAB> type]
 * - type 为 p 表示词组、s 表示单字；缺省时按 text 长度 > 1 判定词组
 * - 空行与以 # 开头的注释行会被跳过
 */
class WubiEngine(text: String) : LookupEngine, AssociationSource {

    /** 五笔码 -> 该编码下的候选（已按单字优先、权重降序排序）。 */
    private val byCode: Map<String, List<DictEntry>>

    /** 全部编码，字典序升序，供前缀查询使用。 */
    private val sortedCodes: List<String>

    /** 首字 -> 以该字开头的词组（权重降序，每字最多 [ASSOCIATION_PER_CHAR] 条），供词语联想使用。 */
    private val phrasesByFirstChar: Map<Char, List<DictEntry>>

    /** 单字 -> 「全码 + 权重」候选（同字多条记录时按 [FULL_CODE_RANK] 淘汰），见 [fullCodeOf]。 */
    private val byText: Map<String, Pair<String, Int>>

    /** 全部词条文本，供「系统词库是否已有该词」的精确查询使用。 */
    private val knownTexts: Set<String>

    override val displayName: String = "wubi86"

    /** 词条总数。 */
    val entryCount: Int

    /** 不同编码数量。 */
    val codeCount: Int

    /** 单字词条数。 */
    val singleCount: Int

    /** 词组词条数。 */
    val phraseCount: Int

    init {
        val table = HashMap<String, MutableList<DictEntry>>()
        val phraseIndex = HashMap<Char, MutableList<DictEntry>>()
        // 词组编码推导与「系统已有不学」共用的两张表，与主表在同一次遍历里填好：
        // 单字全码（字 -> 最长码）与全部词条文本。分两遍解析等于把 2.9 MB 词库读两遍，没必要。
        val singleCodes = HashMap<String, Pair<String, Int>>(70_000)
        val texts = HashSet<String>(165_000)
        var total = 0
        var singles = 0
        var phrases = 0

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size < 3) continue
            val code = fields[0]
            val word = fields[1]
            if (code.isEmpty() || word.isEmpty()) continue
            val weight = fields[2].toIntOrNull() ?: 0
            val isPhrase = if (fields.size >= 4) fields[3] == "p" else word.length > 1
            val entry = DictEntry(code, word, weight, isPhrase)
            table.getOrPut(code) { ArrayList() }.add(entry)
            texts.add(word)
            if (isPhrase) {
                if (word.length in 2..MAX_ASSOCIATION_WORD_LENGTH) {
                    phraseIndex.getOrPut(word[0]) { ArrayList() }.add(entry)
                }
            } else {
                // 单字全码：最长码优先，同长取权重最高，再同取字典序最小（ADR-0001 §2 步骤 1）
                val current = singleCodes[word]
                if (current == null || FULL_CODE_RANK.compare(code to weight, current) < 0) {
                    singleCodes[word] = code to weight
                }
            }
            total++
            if (isPhrase) phrases++ else singles++
        }

        val normalized = HashMap<String, List<DictEntry>>(table.size)
        for ((code, list) in table) {
            list.sortWith(WEIGHT_RANK)
            normalized[code] = list
        }
        val normalizedPhrases = HashMap<Char, List<DictEntry>>(phraseIndex.size)
        for ((first, list) in phraseIndex) {
            list.sortWith(WEIGHT_RANK)
            normalizedPhrases[first] = list.take(ASSOCIATION_PER_CHAR)
        }
        byCode = normalized
        sortedCodes = normalized.keys.sorted()
        phrasesByFirstChar = normalizedPhrases
        byText = singleCodes
        knownTexts = texts
        entryCount = total
        codeCount = normalized.size
        singleCount = singles
        phraseCount = phrases
    }

    /**
     * 词语联想：以 [text] 的末字为线索，返回词库中以该字开头的词组（按权重降序）。
     * 已上屏文本本身会被排除，避免联想出重复内容。
     */
    override fun related(text: String, limit: Int): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val key = text.lastOrNull() ?: return emptyList()
        val list = phrasesByFirstChar[key] ?: return emptyList()
        val result = ArrayList<LookupCandidate>()
        for (entry in list) {
            if (entry.text == text) continue
            result.add(
                LookupCandidate(
                    code = entry.code,
                    display = entry.text,
                    insertText = entry.text,
                    weight = entry.weight,
                    kind = LookupKind.WUBIPHRASE,
                )
            )
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * 查询候选：
     * - 精确码候选优先，输入不足 4 码时追加以该码为前缀的单字候选
     * - 排序：单字优先于词组、匹配到的编码更短者优先、权重降序、文本升序
     * - input 中的 z 作为通配符，匹配任意单个 a-z 字符
     * - input 为空或超过 4 码时返回空列表
     */
    fun candidates(input: String, limit: Int = 9): List<DictEntry> {
        val pattern = input.lowercase()
        if (pattern.isEmpty() || pattern.length > 4 || limit <= 0) return emptyList()

        val pool = ArrayList<DictEntry>()

        if (pattern.contains('z')) {
            // 把 z 替换为 [a-z]，整体用 ^...$ 锚定
            val wildcard = wildcardRegex(pattern)
            for (code in sortedCodes) {
                if (code.length == pattern.length && wildcard.matches(code)) {
                    byCode[code]?.let { pool.addAll(it) }
                }
            }
            if (pool.isEmpty()) {
                // 同长度编码无命中时，用通配前缀补充更长编码下的单字
                for (code in sortedCodes) {
                    if (code.length <= pattern.length) continue
                    if (wildcard.matches(code.substring(0, pattern.length))) {
                        byCode[code]?.let { list ->
                            for (entry in list) if (!entry.isPhrase) pool.add(entry)
                        }
                        if (pool.size >= 300) break
                    }
                }
            }
        } else {
            byCode[pattern]?.let { pool.addAll(it) }
            if (pattern.length < 4) {
                var index = lowerBound(pattern)
                while (index < sortedCodes.size && sortedCodes[index].startsWith(pattern)) {
                    val code = sortedCodes[index]
                    if (code != pattern) {
                        byCode[code]?.let { list ->
                            for (entry in list) if (!entry.isPhrase) pool.add(entry)
                        }
                    }
                    index++
                }
            }
        }

        if (pool.isEmpty()) return emptyList()
        pool.sortWith(DISPLAY_RANK)

        val seen = HashSet<String>()
        val result = ArrayList<DictEntry>()
        for (entry in pool) {
            if (seen.add(entry.text)) {
                result.add(entry)
                if (result.size >= limit) break
            }
        }
        return result
    }

    /**
     * 单字全码：同字多条记录时取最长码，同长取权重最高，再同则取字典序最小；查不到返回 null。
     *
     * 词组编码推导（[PhraseCodeDeriver]）依赖这里的取值口径：必须取**最长码**而不是最高权重码，
     * 否则 `产`（最高权重码是 1 位的 `u`）这类字会让整类词静默算错，见 ADR-0001 §0。
     *
     * [char] 是单个码点的 String 而不是 [Char]：词库里有 42,870 个非 BMP 单字（`𧝣` 等），
     * 用 Char 会被拆成代理对的一半，必然查不到。
     */
    fun fullCodeOf(char: String): String? = byText[char]?.first

    /** 系统词库是否已有该词条文本（精确匹配）；用于「系统已有则不学」。 */
    fun hasWord(word: String): Boolean = knownTexts.contains(word)

    /** 随机取一条指定类型的词条（用于预览 / 调试展示）。 */
    fun sampleEntry(isPhrase: Boolean): DictEntry? {
        val matching = ArrayList<DictEntry>()
        for (list in byCode.values) {
            for (entry in list) {
                if (entry.isPhrase == isPhrase) matching.add(entry)
            }
        }
        if (matching.isEmpty()) return null
        return matching[Random.nextInt(matching.size)]
    }

    /** 人类可读的统计信息，多行文本。 */
    fun summary(): String {
        val lines = ArrayList<String>()
        lines.add("词条总数: $entryCount")
        lines.add("单字: $singleCount  词组: $phraseCount")
        lines.add("不同代码: $codeCount")
        if (sortedCodes.isNotEmpty()) {
            lines.add("代码范围: ${sortedCodes.first()} ... ${sortedCodes.last()}")
        }
        return lines.joinToString("\n")
    }

    /** 作为通用查询后端：单字映射为五笔候选，词组映射为短语候选。 */
    override fun lookup(input: String, limit: Int): List<LookupCandidate> =
        candidates(input, limit).map { entry ->
            LookupCandidate(
                code = entry.code,
                display = entry.text,
                insertText = entry.text,
                weight = entry.weight,
                kind = if (entry.isPhrase) LookupKind.WUBIPHRASE else LookupKind.WUBI
            )
        }

    /** 把 z 通配编码转换为 ^...$ 正则：z 匹配任意单个 a-z 字符。 */
    private fun wildcardRegex(pattern: String): Regex {
        val body = buildString {
            for (ch in pattern) {
                if (ch == 'z') append("[a-z]") else append(Regex.escape(ch.toString()))
            }
        }
        return Regex("^" + body + "$")
    }

    /** 二分查找第一个 >= key 的编码下标（等价于参考实现的 lowerBound）。 */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = sortedCodes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedCodes[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    private companion object {
        /** 参与联想的词组最大长度（超过该长度的长句联想价值低，且占用内存）。 */
        const val MAX_ASSOCIATION_WORD_LENGTH = 8

        /** 每个首字最多缓存的联想词组数量。 */
        const val ASSOCIATION_PER_CHAR = 24

        /** 同一编码内排序：单字优先、权重降序、文本升序。 */
        val WEIGHT_RANK: Comparator<DictEntry> = Comparator { a, b ->
            if (a.isPhrase != b.isPhrase) return@Comparator if (a.isPhrase) 1 else -1
            if (a.weight != b.weight) return@Comparator b.weight.compareTo(a.weight)
            a.text.compareTo(b.text)
        }

        /** 全池排序：单字优先、编码更短者优先、权重降序、文本升序。 */
        val DISPLAY_RANK: Comparator<DictEntry> = Comparator { a, b ->
            if (a.isPhrase != b.isPhrase) return@Comparator if (a.isPhrase) 1 else -1
            if (a.code.length != b.code.length) return@Comparator a.code.length - b.code.length
            if (a.weight != b.weight) return@Comparator b.weight.compareTo(a.weight)
            a.text.compareTo(b.text)
        }

        /**
         * 单字取码口径（code to weight）：码更长者优先，同长权重更高者优先，
         * 再同则码字典序更小者优先。与 ADR-0001 §2「最长码优先」一致。
         */
        val FULL_CODE_RANK: Comparator<Pair<String, Int>> = Comparator { a, b ->
            if (a.first.length != b.first.length) return@Comparator b.first.length - a.first.length
            if (a.second != b.second) return@Comparator b.second.compareTo(a.second)
            a.first.compareTo(b.first)
        }
    }
}
