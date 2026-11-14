package com.ispeech.engine

/** 复合引擎：合并五笔、快捷短语等后端，IME 控制器只与它对话。 */
class CompositeEngine(val engines: List<LookupEngine>) : LookupEngine {

    override val displayName: String = "composite"

    /**
     * 汇总各引擎候选（每个引擎取 max(limit * 2, 18) 条），
     * 统一排序后按 display 去重，取前 limit 条。
     * 排序：快捷短语 → 单字 → 词组 / 句子 → 精确码优先 → 应用 < 短语 < 词库单字
     * < 词库词组 < 拼音 → 权重降序 → display 升序（见 [CandidateRanking]）。
     */
    override fun lookup(input: String, limit: Int): List<LookupCandidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()

        val pool = ArrayList<LookupCandidate>()
        val perEngine = maxOf(limit * 2, 18)
        for (engine in engines) {
            pool.addAll(engine.lookup(input, perEngine))
        }
        if (pool.isEmpty()) return emptyList()
        pool.sortWith(CandidateRanking.comparator(input))

        val seen = HashSet<String>()
        val result = ArrayList<LookupCandidate>()
        for (candidate in pool) {
            if (!seen.add(candidate.display)) continue
            result.add(candidate)
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * 词语联想：把实现了 [AssociationSource] 的引擎（五笔词库、快捷短语、用户词库）
     * 的联想结果合并去重，按与候选条相同的来源分组（快捷短语 → 单字 → 词组 / 句子）
     * 排序，组内再看权重降序、display 升序。
     */
    fun related(text: String, limit: Int): List<LookupCandidate> {
        if (text.isBlank() || limit <= 0) return emptyList()

        val pool = ArrayList<LookupCandidate>()
        for (engine in engines) {
            if (engine is AssociationSource) {
                pool.addAll(engine.related(text, maxOf(limit, 12)))
            }
        }
        if (pool.isEmpty()) return emptyList()
        pool.sortWith(
            compareBy<LookupCandidate> { CandidateRanking.tier(it) }
                .thenByDescending { it.weight }
                .thenBy { it.display },
        )

        val seen = HashSet<String>()
        val result = ArrayList<LookupCandidate>()
        for (candidate in pool) {
            if (candidate.insertText.isBlank()) continue
            if (!seen.add(candidate.display)) continue
            result.add(candidate)
            if (result.size >= limit) break
        }
        return result
    }
}
