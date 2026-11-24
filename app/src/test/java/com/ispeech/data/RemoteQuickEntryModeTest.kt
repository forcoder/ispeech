package com.ispeech.data

import com.ispeech.engine.CompositeEngine
import com.ispeech.engine.LookupCandidate
import com.ispeech.engine.LookupEngine
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试（VOCA-391 / DEF-1）：云端来源的 >4 位简写不得在五笔态只敲 4 位就命中。
 *
 * 缺陷来源：`CsBabyClient.toQuickEntry` 把云端 `match_type` 原样映射，
 * CONTAINS / FUZZY → CONTAINS、缺省 → PREFIX，两者都在 `dtws` 上命中 5 位码 `dtwsl`，
 * 245 字长正文被排到候选首位、空格直接上屏。修复口径 = C1-6：拉取侧 >4 位统一降级 EXACT。
 *
 * 探针同款最小数据：一条 `trigger=dtwsl` + 245 字正文的远端规则，
 * 配一个只返回「泉 / 氺」的假五笔引擎。
 */
class RemoteQuickEntryModeTest {

    /** 与线上那条云端 `dtwsl` 等长的正文（5 字标题 + 240 字说明 = 245）。 */
    private val longBody = "泡池须知：" + "须".repeat(240)

    private fun rule(
        keyword: String,
        matchType: String,
        content: String = longBody,
    ) = RemoteKeywordRule(
        id = "remote-$keyword-$matchType",
        keyword = keyword,
        matchType = matchType,
        replyTemplate = content,
        priority = 110,
        enabled = true,
        deleted = false,
    )

    /** 转 QuickEntry → 按 SyncRepository.writeEntries 的格式落盘 → 引擎从文本读回。 */
    private fun engineFromRemoteRules(vararg rules: RemoteKeywordRule): QuickPhraseEngine {
        val entries = rules.map { requireNotNull(CsBabyClient.toQuickEntry(it)) }
        return requireNotNull(
            QuickPhraseEngine.fromText(entries.joinToString("\n") { lineOf(it) }, minTriggerLength = MIN_TRIGGER_LENGTH)
        )
    }

    /** 与 `SyncRepository.writeEntries` 一致的一行格式：trigger\tlabel\tcontent\tmode\tweight\ta。 */
    private fun lineOf(entry: QuickEntry): String =
        listOf(entry.trigger, entry.label, entry.content, entry.mode.name, entry.weight.toString(), "a")
            .joinToString("\t")

    private fun modeOf(keyword: String, matchType: String): QuickMatchMode =
        requireNotNull(CsBabyClient.toQuickEntry(rule(keyword, matchType))).mode

    /** 五笔态候选源：词库只给「泉 / 氺」两个单字，剩下看快捷短语会不会顶到首位。 */
    private fun wubiComposite(quick: QuickPhraseEngine): CompositeEngine =
        CompositeEngine(listOf(WubiFakeEngine(), quick))

    @Test
    fun `超过4位的云端简写 CONTAINS PREFIX 缺省三种来源都降级为 EXACT`() {
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "CONTAINS"))
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "FUZZY"))
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "PREFIX"))
        // 缺省 / 未标注 / 无法识别的 match_type 原本都落到 PREFIX，同样必须降级
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", ""))
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "contains"))
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "未知口径"))
        assertEquals(QuickMatchMode.EXACT, modeOf("dtwsl", "EXACT"))
        assertEquals(QuickMatchMode.EXACT, modeOf("abcdefghijk", "CONTAINS"))
    }

    @Test
    fun `五笔态敲4位不命中超过4位的云端简写长正文`() {
        val composite = wubiComposite(engineFromRemoteRules(rule("dtwsl", "CONTAINS")))

        val hit = composite.lookup("dtws", 10)
        assertTrue("4 位输入不应带出任何快捷短语候选", hit.none { it.kind == LookupKind.QUICKAPPLY })
        assertEquals("泉", hit.firstOrNull()?.display)
        assertTrue("候选里不得出现长正文", hit.none { it.insertText.length > 10 })
    }

    @Test
    fun `缺省映射与PREFIX来源在五笔态同样不被4位敲中`() {
        listOf("", "PREFIX").forEach { matchType ->
            val composite = wubiComposite(engineFromRemoteRules(rule("dtwsl", matchType)))
            assertTrue(
                "match_type='$matchType' 时 4 位输入不应命中 5 位简写",
                composite.lookup("dtws", 10).none { it.kind == LookupKind.QUICKAPPLY },
            )
        }
    }

    @Test
    fun `敲满5位仍然命中长正文`() {
        listOf("CONTAINS", "PREFIX", "").forEach { matchType ->
            val quick = engineFromRemoteRules(rule("dtwsl", matchType))
            val hit = quick.lookup("dtwsl", 10).firstOrNull()

            assertNotNull("match_type='$matchType' 敲满应命中", hit)
            assertEquals("dtwsl", hit!!.display)
            assertEquals(longBody, hit.insertText)
            assertEquals(245, hit.insertText.length)
        }
    }

    @Test
    fun `4位及以内的云端简写保持声明的匹配方式`() {
        assertEquals(QuickMatchMode.CONTAINS, modeOf("dtws", "CONTAINS"))
        assertEquals(QuickMatchMode.PREFIX, modeOf("dtws", "PREFIX"))
        assertEquals(QuickMatchMode.PREFIX, modeOf("dtws", ""))
        assertEquals(QuickMatchMode.CONTAINS, modeOf("hi", "FUZZY"))

        val quick = engineFromRemoteRules(rule("dtws", "PREFIX", "雨天泳池"))
        assertEquals("雨天泳池", quick.lookup("dtws", 10).firstOrNull()?.insertText)
        assertEquals("前缀命中行为不变", "雨天泳池", quick.lookup("dtw", 10).firstOrNull()?.insertText)
    }

    @Test
    fun `正则来源不参与降级`() {
        // regex 与 exact 同属「不受长度守卫」的口径（check_quick_trigger UNGUARDED_MODES），
        // 命中与否由表达式自己决定，拉取侧不改写云端语义
        assertEquals(QuickMatchMode.REGEX, modeOf("dtws[l]", "REGEX"))
    }

    @Test
    fun `降级只改匹配方式不动云端正文`() {
        val entry = requireNotNull(CsBabyClient.toQuickEntry(rule("dtwsl", "CONTAINS")))

        assertEquals(longBody, entry.content)
        assertEquals("dtwsl", entry.trigger)
        assertEquals("dtwsl", entry.label)
        assertEquals(110, entry.weight)
        assertEquals(LookupKind.QUICKAPPLY, entry.kind)
    }

    @Test
    fun `落盘的EXACT读回后仍是EXACT`() {
        val entry = requireNotNull(CsBabyClient.toQuickEntry(rule("dtwsl", "CONTAINS")))

        assertEquals("dtwsl\t" + entry.label + "\t" + longBody + "\tEXACT\t110\ta", lineOf(entry))
        assertEquals(QuickMatchMode.EXACT, engineFromRemoteRules(rule("dtwsl", "CONTAINS")).entries.single().mode)
    }

    /** 只返回两个单字的最小五笔词库，复刻探针里「泉 / 氺」的对照引擎。 */
    private class WubiFakeEngine : LookupEngine {
        override val displayName: String = "wubi-fake"

        override fun lookup(input: String, limit: Int): List<LookupCandidate> {
            if (input.length < MIN_TRIGGER_LENGTH || limit <= 0) return emptyList()
            return listOf(
                LookupCandidate("dtws", "泉", "泉", 22_976, LookupKind.WUBI),
                LookupCandidate("dtws", "氺", "氺", 1_000, LookupKind.WUBI),
            )
        }
    }

    private companion object {
        /** 与 `AppContainer` 的 `minTriggerLength = if (singleNoTrigger) 2 else 1` 默认口径一致。 */
        const val MIN_TRIGGER_LENGTH = 2
    }
}
