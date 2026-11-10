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
 * 候选统一排序：精确码优先 → 应用 < 短语 < 词库单字 < 词库词组 < 拼音 → 权重降序 → display 升序
 * （档位顺序见 [LookupKind.order]）。
 */
internal object CandidateRanking {
    fun comparator(input: String): Comparator<LookupCandidate> = Comparator { a, b ->
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
