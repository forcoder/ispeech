package com.ispeech.engine

/** 复合引擎：合并五笔、快捷短语等后端，IME 控制器只与它对话。 */
class CompositeEngine(val engines: List<LookupEngine>) : LookupEngine {

    override val displayName: String = "composite"

    /**
     * 汇总各引擎候选（每个引擎取 max(limit * 2, 18) 条），
     * 统一排序后按 display 去重，取前 limit 条。
     * 排序：精确码优先 → 应用 < 短语 < 词库单字 < 词库词组 < 拼音 → 权重降序 → display 升序
     * （档位顺序见 [LookupKind.order]）。
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
     * 的联想结果合并去重，按权重降序返回。
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
        // 联想结果按权重降序；同权重时保持引擎顺序（词库词优先于短语）。
        pool.sortWith(Comparator { a, b -> b.weight.compareTo(a.weight) })

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
