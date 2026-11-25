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
        return sortedPool(input, limit).take(limit)
    }

    /**
     * 带「首屏格数」的查询：除了 [lookup] 的合并排序，还保证快捷短语不会把单字挤出首屏。
     *
     * 只由真正渲染候选条的调用方用（它知道每屏几格）；不知道屏宽的调用方走 [lookup]，
     * 行为与加这条兜底之前完全一致。
     *
     * @param visibleSlots 首屏可见格数（＝设置里的每屏候选数，5 / 7 / 9）。
     */
    fun lookup(input: String, limit: Int, visibleSlots: Int): List<LookupCandidate> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        val pool = sortedPool(input, limit)
        if (visibleSlots <= 0) return pool.take(limit)
        return reserveSingleSlots(pool, input, limit, visibleSlots)
    }

    /**
     * 各引擎候选的合并结果：排序 + 按 display 去重，**不截断到 limit**。
     *
     * 不截断是格位兜底的前提：`hx` 命中 17 条短语时，先截到 limit 就等于把后面的单字全扔了，
     * 兜底再想捞也捞不到（VOCA-651 第一次实现踩的正是这个坑）。
     */
    private fun sortedPool(input: String, limit: Int): List<LookupCandidate> {
        val pool = ArrayList<LookupCandidate>()
        val perEngine = maxOf(limit * 2, 18)
        for (engine in engines) {
            pool.addAll(engine.lookup(input, perEngine))
        }
        if (pool.isEmpty()) return emptyList()
        pool.sortWith(CandidateRanking.comparator(input))

        val seen = HashSet<String>()
        val result = ArrayList<LookupCandidate>(pool.size)
        for (candidate in pool) {
            if (!seen.add(candidate.display)) continue
            result.add(candidate)
        }
        return result
    }

    /**
     * 快捷短语挤满首屏时，把首屏尾部的几个格位让给单字（VOCA-651）。
     *
     * 背景：快捷短语的档位（`TIER_QUICK_PHRASE = 0`）永远在单字（`TIER_SINGLE = 1`）之前，
     * 这是 VOCA-185 已裁决的顺序，不动。但导入大批短语后，`hx` / `yp` / `dt` 这类 2 码
     * 正好压在五笔二级简码上：命中 9–17 条短语就能把首屏填满，同码单字（此 / 这 / 帮）
     * 退到首屏之外，用户「打不出字」。这里只补一条格位兜底：短语命中数达到首屏一半时，
     * 为单字保留 [RESERVED_SINGLE_SLOTS] 格。
     *
     * 精确码命中不动：用户把某个快捷字母打全了，排第一的权利不能被兜底挤掉
     * （`ly` → 鹭影 这类既有行为）。
     */
    private fun reserveSingleSlots(
        pool: List<LookupCandidate>,
        input: String,
        limit: Int,
        visibleSlots: Int,
    ): List<LookupCandidate> {
        // 首屏都装不满时整条候选都看得见，没有「挤出首屏」问题，不动顺序
        if (pool.size <= visibleSlots) return pool.take(limit)

        val windowQuicks = (0 until visibleSlots).count { CandidateRanking.isQuickPhrase(pool[it]) }
        // 短语没到首屏一半：单字仍然看得见，不动顺序
        if (windowQuicks * 2 < visibleSlots) return pool.take(limit)

        val visibleSingles = (0 until visibleSlots).count { CandidateRanking.isSingle(pool[it]) }
        val wantMore = RESERVED_SINGLE_SLOTS - visibleSingles
        if (wantMore <= 0) return pool.take(limit)

        // 首屏之外按原顺序取最靠前的单字补位
        val pulled = (visibleSlots until pool.size)
            .filter { CandidateRanking.isSingle(pool[it]) }
            .take(wantMore)
        if (pulled.isEmpty()) return pool.take(limit)

        // 让位方：首屏里靠后的、且不是精确码命中的快捷短语
        val slots = (0 until visibleSlots)
            .filter {
                CandidateRanking.isQuickPhrase(pool[it]) &&
                    !pool[it].code.equals(input, ignoreCase = true)
            }
            .takeLast(pulled.size)
        if (slots.isEmpty()) return pool.take(limit)

        val out = pool.toMutableList()
        for ((k, slot) in slots.withIndex()) {
            val from = pulled[k]
            val displaced = out[slot]
            out[slot] = out[from]
            out[from] = displaced
        }
        return out.take(limit)
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

    companion object {
        /**
         * 快捷短语挤满首屏时为单字保留的格数（VOCA-651）。
         *
         * 3 格的理由：五笔二级简码的同码单字通常在 1–3 个，留 3 格足以让「此 / 这 / 帮」
         * 这类高频字落在首屏；再多留就会把用户自己设的短语挤走，违背档位顺序的初衷。
         */
        const val RESERVED_SINGLE_SLOTS = 3
    }
}