package com.ispeech.engine

/** 所有查询后端实现该接口：五笔引擎、快捷短语引擎、复合引擎。 */
interface LookupEngine {
    /** 引擎名称，便于调试展示。 */
    val displayName: String

    /** 查询候选，返回数量不超过 limit。 */
    fun lookup(input: String, limit: Int): List<LookupCandidate>
}

/**
 * 联想后端（可选能力）：某段文字上屏后，给出与之相关的后续候选。
 *
 * 五笔词库按「以该字开头」的词语联想，快捷短语按「包含该文字」联想；
 * 未实现该接口的引擎不参与联想。
 */
interface AssociationSource {
    /** 依据已上屏文本 [text] 返回联想候选，数量不超过 limit。 */
    fun related(text: String, limit: Int): List<LookupCandidate>
}

/**
 * 候选统一排序：来源分组 → 精确码优先 → 应用 < 短语 < 词库单字 < 词库词组 < 拼音
 * → 权重降序 → display 升序。
 *
 * 分组（[tier]）是用户能直接感知的顺序，优先级高于其余所有规则：
 * 快捷短语 → 单字 → 词组 / 句子。同组内才看「是否精确命中编码」与档位、权重，
 * 这样用户设的简写短语一定排在最前，而不会因为编码只是前缀就被同码单字挤下去。
 */
internal object CandidateRanking {

    /** 快捷短语 / 快速应用：用户显式设置的简写，永远排第一组。 */
    private const val TIER_QUICK_PHRASE = 0

    /** 单字：候选条第二组，保证「打一个码先出字」的肌肉记忆。 */
    private const val TIER_SINGLE = 1

    /** 词组 / 句子：多字词与长句排在最后，用数字键或滑动选取。 */
    private const val TIER_PHRASE = 2

    /**
     * 候选来源分组：0 快捷短语 → 1 单字 → 2 词组 / 句子。
     *
     * 按「上屏内容是不是一个字」而不是只按 [LookupKind] 判定：拼音词表、词库、分类词库
     * 都可能在同一个档位里同时产出单字与多字词，只有文本长度能稳定区分。
     */
    fun tier(candidate: LookupCandidate): Int = when {
        candidate.kind == LookupKind.PHRASE || candidate.kind == LookupKind.QUICKAPPLY -> TIER_QUICK_PHRASE
        candidate.insertText.codePointCount(0, candidate.insertText.length) <= 1 -> TIER_SINGLE
        else -> TIER_PHRASE
    }

    /** 是否为「快捷短语」分组：格位兜底（[CompositeEngine] VOCA-651）按它判。 */
    fun isQuickPhrase(candidate: LookupCandidate): Boolean = tier(candidate) == TIER_QUICK_PHRASE

    /** 是否为「单字」分组：快捷短语挤满首屏时，要给这一组留格位。 */
    fun isSingle(candidate: LookupCandidate): Boolean = tier(candidate) == TIER_SINGLE

    fun comparator(input: String): Comparator<LookupCandidate> = Comparator { a, b ->
        val byTier = tier(a).compareTo(tier(b))
        if (byTier != 0) return@Comparator byTier
        val aExact = a.code.equals(input, ignoreCase = true)
        val bExact = b.code.equals(input, ignoreCase = true)
        if (aExact != bExact) return@Comparator if (aExact) -1 else 1
        val byKind = a.kind.order.compareTo(b.kind.order)
        if (byKind != 0) return@Comparator byKind
        val byWeight = b.weight.compareTo(a.weight)
        if (byWeight != 0) return@Comparator byWeight
        a.display.compareTo(b.display)
    }
}
