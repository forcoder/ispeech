package com.ispeech.engine

import com.ispeech.data.codePointLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 候选条分组契约（VOCA-185：「快捷短语 → 单字 → 词组 / 句子」）。
 *
 * 需求方口径：候选按来源分组显示，快捷短语必须排最前，单字其次，多字词 / 句子最后。
 * 这条顺序是用户**直接感知**的，所以用真实 `assets/wubi86.dict` 加真实快捷短语规则做端到端
 * 断言，而不是只测 [CandidateRanking.tier] 的返回值——分组对但复合引擎没接上，候选条上一样看不到。
 *
 * 覆盖码位的选取依据（均取自真实词库）：
 * - `trnt`：单字「我」35620 与词组「特性」51514、「选择性」57028 同码且词组权重更高。
 *   若按权重全局混排，「我」会被挤到后面；分组后它必须排在两个词组之前。
 * - `wqiy`：单字「你」22976 与词组「仰泳」57816 同码，同构用它做第二组交叉验证。
 * - `em`：单字「肌」45 与用户设的 `em` 快捷短语同码，验证 tier 0 压过 tier 1。
 */
class CandidateTierOrderTest {

    private val dict = File("src/main/assets/wubi86.dict")

    private fun wubi(): WubiEngine {
        assertTrue("找不到真实词库：${dict.absolutePath}", dict.exists())
        return WubiEngine(dict.readText(Charsets.UTF_8))
    }

    /** 最小快捷短语规则：`em` 精确命中，权重刻意低于词库单字，验证分组优先于权重。 */
    private fun quick(): QuickPhraseEngine =
        QuickPhraseEngine.fromText("em\t邮箱地址\tsupport@example.com\texact\t1\tp", minTriggerLength = 2)!!

    private fun tierOf(candidate: LookupCandidate): Int = CandidateRanking.tier(candidate)

    @Test
    fun `同一分组内单字排在词组之前，即使词组权重更高`() {
        val candidates = wubi().lookup("trnt", 10)
        val single = candidates.first { codePointLength(it.insertText) == 1 }
        val phrases = candidates.filter { codePointLength(it.insertText) > 1 }
        assertTrue("样本码 trnt 应同时含单字与词组：$candidates", phrases.isNotEmpty())
        assertEquals("分组必须是 单字 < 词组", 1, tierOf(single))
        assertTrue("词组组必须全部落在 tier 2：$phrases", phrases.all { tierOf(it) == 2 })
        assertTrue(
            "单字「${single.display}」应排在全部词组之前，实际：${candidates.map { it.display }}",
            candidates.indexOf(single) < candidates.indexOfFirst { codePointLength(it.insertText) > 1 },
        )
    }

    @Test
    fun `快捷短语组压过同码单字，且分组优先于权重`() {
        val composite = CompositeEngine(listOf(wubi(), quick()))
        val candidates = composite.lookup("em", 10)
        assertEquals("快捷短语必须是候选第 1 条：${candidates.map { it.display }}", "邮箱地址", candidates.first().display)
        assertEquals(0, tierOf(candidates.first()))
        assertTrue("同码单字「肌」仍须保留在候选里：$candidates", candidates.any { it.insertText == "肌" })
    }

    @Test
    fun `联想候选复用同一分组顺序`() {
        val composite = CompositeEngine(listOf(wubi(), quick()))
        val related = composite.related("仰泳", 10)
        // 正向断言，而不是 `if (isEmpty()) return`：词库一换就静默空转的用例等于没有看守。
        assertTrue("样本「仰泳」必须能产出联想候选：$related", related.isNotEmpty())
        val tiers = related.map { tierOf(it) }
        assertEquals("联想候选必须按分组升序：${related.map { it.display }}", tiers.sorted(), tiers)
    }

    /**
     * 分组必须**压过**权重：同一 kind（`WUBIPHRASE`）下，单字权重极低、多字词权重极高。
     * 若分组被删掉、退回「档位 + 权重」排序，这条会先失败——这是本用例存在的意义，
     * 而不是再用真实词库碰巧同向的样本（`trnt` 的档位顺序恰好也把单字排在前面）。
     */
    @Test
    fun `分组压过权重：低权重单字仍排在权重更高的多字词之前`() {
        val single = LookupCandidate("ab", "丁", "丁", weight = 1, kind = LookupKind.WUBIPHRASE)
        val phrase = LookupCandidate("ab", "丁丁", "丁丁", weight = 99_999, kind = LookupKind.WUBIPHRASE)
        val sorted = listOf(phrase, single).sortedWith(CandidateRanking.comparator("ab"))

        assertEquals("单字必须在前：${sorted.map { it.display }}", listOf("丁", "丁丁"), sorted.map { it.display })
        // 反证：去掉分组后按「权重降序」排，结论会翻转——说明上面的断言确实依赖分组。
        assertEquals(
            "分组若失效，权重会把多字词排到前面",
            listOf("丁丁", "丁"),
            listOf(phrase, single).sortedByDescending { it.weight }.map { it.display },
        )
    }

    @Test
    fun `分组不改变同组内的既有排序条件`() {
        // 同码同组（都是词组）时仍按权重降序，「仰泳」57816 > 词库其它同码词组
        val phrases = wubi().lookup("wqiy", 10).filter { codePointLength(it.insertText) > 1 }
        assertTrue("wqiy 应有词组候选：$phrases", phrases.isNotEmpty())
        assertEquals(phrases.sortedByDescending { it.weight }.map { it.display }, phrases.map { it.display })
    }
}