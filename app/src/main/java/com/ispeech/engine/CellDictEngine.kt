package com.ispeech.engine

import com.ispeech.data.CellDictEntry
import com.ispeech.data.UserDictMode
import com.ispeech.data.entryKindOf

/**
 * 分类词库（细胞词库）引擎：把用户安装的本地词库并入候选排序与联想。
 *
 * 与 [UserDictEngine] 的区别在于「权重来路」：用户词库是学出来的，统一加 10 万权重压过一切；
 * 分类词库是装进来的，权重固定为 [CELL_DICT_WEIGHT]（8 万）——压过系统词库（≤ 65535）
 * 与拼音词表（≤ 9999），但仍让位给用户自己学到的词。
 *
 * [mode] 与用户词库同一套模式隔离规则：[UserDictMode.WUBI] 只收五笔码词条
 * （编码是五笔码），[UserDictMode.PINYIN] 只收拼音串词条——两套编码空间互不污染。
 * 词库内部混排 wb / cn 词条是允许的：安装时按行解析，查询时按 [mode] 挑选。
 */
class CellDictEngine(
    private val entries: List<CellDictEntry>,
    private val mode: UserDictMode = UserDictMode.WUBI,
) : LookupEngine, AssociationSource {

    override val displayName: String = "cell"

    /**
     * 按输入码匹配候选：编码精确匹配整组优先，其次前缀匹配；组内按词条文本升序。
     *
     * 候选档位与用户词库一致：五笔态单字走「词库单字」、多字走「词库词组」，
     * 拼音态一律走「拼音」档，与拼音词表同档竞争（靠权重排到前面）。
     */
    override fun lookup(input: String, limit: Int): List<LookupCandidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        val needle = input.lowercase()
        val exact = ArrayList<LookupCandidate>()
        val prefixed = ArrayList<LookupCandidate>()
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.mode != mode) continue
            val code = entry.code.lowercase()
            when {
                code == needle -> if (seen.add(entry.text)) exact.add(entry.toCandidate())
                code.startsWith(needle) -> if (seen.add(entry.text)) prefixed.add(entry.toCandidate())
            }
        }
        if (exact.isEmpty() && prefixed.isEmpty()) return emptyList()
        exact.sortWith(DISPLAY_RANK)
        prefixed.sortWith(DISPLAY_RANK)

        val out = ArrayList<LookupCandidate>(exact.size + prefixed.size)
        for (group in listOf(exact, prefixed)) {
            for (candidate in group) {
                out.add(candidate)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /**
     * 联想：词库词条包含已上屏文本（或反之）、或与末字同字头时给出候选。
     *
     * 与用户词库的联想口径一致，只是权重低一档，排在学到的词之后。
     */
    override fun related(text: String, limit: Int): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        val tail = needle.last()

        val out = ArrayList<LookupCandidate>()
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.mode != mode) continue
            val word = entry.text
            if (word.isEmpty() || word == needle) continue
            val hit = word.contains(needle) ||
                (word.length >= 2 && needle.contains(word)) ||
                word.first() == tail
            if (!hit) continue
            if (!seen.add(word)) continue
            out.add(entry.toCandidate())
            if (out.size >= limit) break
        }
        return out
    }
}

/** 分类词库词条权重：压过系统词库 / 拼音词表，让位给用户学到的词（+10 万）。 */
internal const val CELL_DICT_WEIGHT = 80_000

/** 同档候选排序：权重相同（分类词库权重固定），按词条文本升序。 */
private val DISPLAY_RANK: Comparator<LookupCandidate> = Comparator { a, b -> a.display.compareTo(b.display) }

/** 词条 → 统一候选：五笔态单字 / 词组分档，拼音态一律拼音档。 */
private fun CellDictEntry.toCandidate(): LookupCandidate = LookupCandidate(
    code = code,
    display = text,
    insertText = text,
    weight = CELL_DICT_WEIGHT,
    kind = when (mode) {
        UserDictMode.WUBI -> entryKindOf(text)
        UserDictMode.PINYIN -> LookupKind.PINYIN
    },
)
