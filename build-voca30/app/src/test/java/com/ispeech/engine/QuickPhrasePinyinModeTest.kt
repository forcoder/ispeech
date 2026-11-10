package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：用户设置的 4 位快捷字母必须在拼音态、五笔态都能命中对应短语。
 *
 * 真实缺陷：拼音态查询走 AppContainer.pinyinLookupEngine（用户词库 + 拼音词表），
 * 里面没有挂 QuickPhraseEngine，默认拼音态下输入 `ytyc` 因此**一个候选都没有**。
 * 这里用 CompositeEngine 复刻两种输入态的候选合并方式（含真实 PinyinEngine 的模糊音配置），
 * 保证快捷短语在两种状态下都参与排序，且不会被同码词库候选挤到后面。
 */
class QuickPhrasePinyinModeTest {

    private val ytyc = "ytyc\t雨天泳池\t雨天泳池\tprefix\t100\ta"
    private val addr = "addr\t家庭住址\t广东省广州市天河区五山路483号\tprefix\t100\ta"

    private fun quickEngine(vararg rows: String, minTriggerLength: Int = 2): QuickPhraseEngine =
        requireNotNull(QuickPhraseEngine.fromText(rows.joinToString("\n"), minTriggerLength = minTriggerLength))

    /** 拼音态候选源：拼音词表 + 快捷规则（与 AppContainer.pinyinLookupEngine 的结构一致）。 */
    private fun pinyinComposite(quick: QuickPhraseEngine, fuzzy: Boolean = false): CompositeEngine {
        val dict = PinyinDict(listOf(PinyinEntry("zhong guo", "中国", 9000)))
        val pinyin = PinyinEngine(dict, PinyinSettings(fuzzy = fuzzy, kind = LookupKind.PINYIN))
        return CompositeEngine(listOf(pinyin, quick))
    }

    /** 五笔态候选源：五笔词库 + 快捷规则（与 QuickLocator.buildComposite 的结构一致）。 */
    private fun wubiComposite(quick: QuickPhraseEngine): CompositeEngine =
        CompositeEngine(listOf(FixedEngine(LookupKind.WUBI, "ytyc" to "同码五笔候选"), quick))

    @Test
    fun `拼音态输入 ytyc 命中雨天泳池并排在首位`() {
        val result = pinyinComposite(quickEngine(ytyc)).lookup("ytyc", 10)

        assertTrue("拼音态应能查到候选（修复前为空）", result.isNotEmpty())
        assertEquals("雨天泳池", result.first().display)
        assertEquals("雨天泳池", result.first().insertText)
        assertEquals(LookupKind.QUICKAPPLY, result.first().kind)
    }

    @Test
    fun `五笔态输入 ytyc 命中雨天泳池并排在首位`() {
        val result = wubiComposite(quickEngine(ytyc)).lookup("ytyc", 10)

        assertTrue("五笔态应能查到候选", result.isNotEmpty())
        // 同码五笔候选权重 99999，但精确命中 + QUICKAPPLY 档位必须压过它
        assertEquals("雨天泳池", result.first().display)
        assertEquals("雨天泳池", result.first().insertText)
    }

    @Test
    fun `模糊音开启时含 zh ch sh 与 n l 的触发码仍精确命中`() {
        val quick = quickEngine(
            "zhrm\t中国人民\t中国人民\tprefix\t100\ta",
            "chrm\t城市人们\t城市人们\tprefix\t100\ta",
            "shrm\t上海人民\t上海人民\tprefix\t100\ta",
            "nlrm\t能力如何\t能力如何\tprefix\t100\ta",
        )
        val engine = pinyinComposite(quick, fuzzy = true)

        assertEquals("中国人民", engine.lookup("zhrm", 10).firstOrNull()?.display)
        assertEquals("城市人们", engine.lookup("chrm", 10).firstOrNull()?.display)
        assertEquals("上海人民", engine.lookup("shrm", 10).firstOrNull()?.display)
        assertEquals("能力如何", engine.lookup("nlrm", 10).firstOrNull()?.display)
    }

    @Test
    fun `多个快捷短语互不干扰`() {
        val engine = pinyinComposite(quickEngine(ytyc, addr))

        assertEquals("雨天泳池", engine.lookup("ytyc", 10).first().display)
        assertEquals("家庭住址", engine.lookup("addr", 10).first().display)
        assertTrue("无关编码不应误命中", engine.lookup("zzzz", 10).none { it.kind == LookupKind.QUICKAPPLY })
    }

    @Test
    fun `既有 exact prefix contains regex 模式行为不变`() {
        val quick = quickEngine(
            "hi\t你好\t你好，请问有什么可以帮您？\texact\t100\ta",
            "thx\t感谢\t感谢您的支持\texact\t100\ta",
            "th\t前缀\t前缀命中\tprefix\t100\ta",
            "addr\t地址\t广东省广州市\tcontains\t100\ta",
            "y[a-z]yc\t正则\t正则命中\tregex\t100\ta",
        )

        assertTrue("exact：完整触发码命中", quick.lookup("hi", 10).any { it.display == "你好" })
        assertTrue("exact：缺字符不应命中", quick.lookup("h", 10).none { it.display == "你好" })
        assertTrue("prefix：前缀命中", quick.lookup("th", 10).any { it.display == "前缀" })
        assertTrue("contains：包含命中", quick.lookup("ddr", 10).any { it.display == "地址" })
        assertEquals("regex：正则命中", "正则", quick.lookup("ytyc", 10).firstOrNull()?.display)
    }

    /** 固定返回同码候选的最小引擎，用来验证快捷短语的排序优先级。 */
    private class FixedEngine(
        private val kind: LookupKind,
        private val hit: Pair<String, String>,
    ) : LookupEngine {
        override val displayName: String = "fixed-${kind.label}"

        override fun lookup(input: String, limit: Int): List<LookupCandidate> {
            if (limit <= 0 || !hit.first.equals(input, ignoreCase = true)) return emptyList()
            return listOf(LookupCandidate(hit.first, hit.second, hit.second, 99_999, kind))
        }
    }
}
