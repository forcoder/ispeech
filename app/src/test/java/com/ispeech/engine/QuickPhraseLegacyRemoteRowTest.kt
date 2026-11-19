package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试（VOCA-423 / DEF-3）：升级前已经落盘的云端规则行必须在读盘侧就被长度守卫管住。
 *
 * VOCA-391（PR #19）的降级只做在拉取侧 `CsBabyClient.toQuickEntry`，对「装上新包之后再点一次
 * 立即同步」的设备才生效；`filesDir/quick_phrases_remote.txt` 里已有的 `CONTAINS` / `PREFIX`
 * 行不会被回写，引擎按原 mode 匹配，五笔态敲 4 位 `dtws` 仍会把 245 字长正文顶到候选首位、空格整段上屏。
 * 这里锁的是读盘侧的兜底：`QuickPhraseEngine.fromRemoteText`。
 *
 * 探针同款最小数据：一行修复前落盘格式的 `dtwsl` + 245 字正文，配一个只出「泉 / 氺」的假五笔引擎。
 */
class QuickPhraseLegacyRemoteRowTest {

    /** 与线上那条云端 `dtwsl` 等长的正文（5 字标题 + 240 字说明 = 245）。 */
    private val longBody = "泡池须知：" + "须".repeat(240)

    /** 修复前 `SyncRepository.writeEntries` 的落盘格式：mode 取 `QuickMatchMode.name`（大写）。 */
    private fun legacyRow(
        trigger: String,
        mode: String?,
        content: String = longBody,
        weight: Int = 110,
    ): String = buildString {
        append(trigger).append('\t').append(trigger).append('\t').append(content)
        if (mode != null) append('\t').append(mode)
        append('\t').append(weight).append("\ta")
    }

    private fun remoteEngine(vararg rows: String, minTriggerLength: Int = MIN_TRIGGER_LENGTH): QuickPhraseEngine =
        requireNotNull(QuickPhraseEngine.fromRemoteText(rows.joinToString("\n"), minTriggerLength = minTriggerLength))

    private fun localEngine(vararg rows: String): QuickPhraseEngine =
        requireNotNull(QuickPhraseEngine.fromText(rows.joinToString("\n")))

    /** 五笔态候选源：词库只给「泉 / 氺」两个单字，剩下看快捷短语会不会顶到首位。 */
    private fun wubiComposite(quick: QuickPhraseEngine): CompositeEngine =
        CompositeEngine(listOf(WubiFakeEngine(), quick))

    @Test
    fun `存量云端 CONTAINS 行在五笔态敲 4 位不命中`() {
        val composite = wubiComposite(remoteEngine(legacyRow("dtwsl", "CONTAINS")))

        val hit = composite.lookup("dtws", 10)
        assertTrue("4 位输入不应带出任何快捷短语候选", hit.none { it.kind == LookupKind.QUICKAPPLY })
        assertEquals("泉", hit.firstOrNull()?.display)
        assertTrue("候选里不得出现长正文", hit.none { it.insertText.length > 10 })
    }

    @Test
    fun `存量云端行缺省与 FUZZY 口径同样按 EXACT 生效`() {
        // 缺省（没有 mode 列）原本落 PREFIX，云端 match_type 缺失 / 无法识别都走这条路
        listOf(null, "PREFIX", "CONTAINS", "FUZZY").forEach { declared ->
            val quick = remoteEngine(legacyRow("dtwsl", declared))

            assertEquals(
                "declared=$declared 应被守卫成 EXACT",
                QuickMatchMode.EXACT,
                quick.entries.single().mode,
            )
            assertTrue(
                "declared=$declared 时 4 位输入不应命中 5 位简写",
                wubiComposite(quick).lookup("dtws", 10).none { it.kind == LookupKind.QUICKAPPLY },
            )
        }
    }

    @Test
    fun `敲满 5 位仍然命中长正文`() {
        val quick = remoteEngine(legacyRow("dtwsl", "CONTAINS"))

        val hit = quick.lookup("dtwsl", 10).firstOrNull()

        assertNotNull("敲满完整码必须仍命中", hit)
        assertEquals("dtwsl", hit!!.display)
        assertEquals(longBody, hit.insertText)
        assertEquals(245, hit.insertText.length)
    }

    @Test
    fun `4 位及以内的存量行行为不变`() {
        val contains = remoteEngine(legacyRow("dtws", "CONTAINS", "雨天泳池", weight = 100))
        val prefix = remoteEngine(legacyRow("dtws", "PREFIX", "雨天泳池", weight = 100))

        assertEquals("包含命中不变", "雨天泳池", contains.lookup("dtw", 10).firstOrNull()?.insertText)
        assertEquals("前缀命中不变", "雨天泳池", prefix.lookup("dtw", 10).firstOrNull()?.insertText)
        assertEquals(QuickMatchMode.CONTAINS, contains.entries.single().mode)
        assertEquals(QuickMatchMode.PREFIX, prefix.entries.single().mode)
    }

    @Test
    fun `正则与精确行不受长度守卫`() {
        // regex 与 exact 同属不受守卫模式（check_quick_trigger 的 UNGUARDED_MODES），命中与否由表达式自己决定
        assertEquals(QuickMatchMode.REGEX, QuickPhraseEngine.guardedRemoteMode("dtws[l]", QuickMatchMode.REGEX))
        assertEquals(QuickMatchMode.EXACT, QuickPhraseEngine.guardedRemoteMode("dtwsl", QuickMatchMode.EXACT))
        assertEquals(QuickMatchMode.CONTAINS, QuickPhraseEngine.guardedRemoteMode("dtws", QuickMatchMode.CONTAINS))
        assertEquals(QuickMatchMode.PREFIX, QuickPhraseEngine.guardedRemoteMode("dtws", QuickMatchMode.PREFIX))
        assertEquals(QuickMatchMode.EXACT, QuickPhraseEngine.guardedRemoteMode("dtwsl", QuickMatchMode.CONTAINS))

        val quick = remoteEngine(legacyRow("dtws[l]", "REGEX", "正则命中"))

        assertEquals(QuickMatchMode.REGEX, quick.entries.single().mode)
        assertEquals("正则命中", quick.lookup("dtwsl", 10).firstOrNull()?.insertText)
    }

    @Test
    fun `守卫只改匹配方式不动云端正文与其它字段`() {
        val row = legacyRow("dtwsl", "CONTAINS")
        val entry = remoteEngine(row).entries.single()

        assertEquals(longBody, entry.content)
        assertEquals("dtwsl", entry.trigger)
        assertEquals("dtwsl", entry.label)
        assertEquals(110, entry.weight)
        assertEquals(LookupKind.QUICKAPPLY, entry.kind)
    }

    @Test
    fun `守卫不截断超长触发码且敲全仍命中`() {
        val thirteen = "abcdefghijklm"
        val quick = remoteEngine(legacyRow(thirteen, "CONTAINS", "正文内容"))

        assertEquals(thirteen, quick.entries.single().trigger)
        assertEquals(13, quick.entries.single().trigger.length)
        assertEquals("正文内容", quick.lookup(thirteen, 10).firstOrNull { it.insertText == "正文内容" }?.insertText)
    }

    @Test
    fun `长度守卫与单字母防误触守卫互不相干`() {
        val row = legacyRow("dtwsl", "CONTAINS")

        // 关掉「单字母不触发」（minTriggerLength = 1）也不放行：4 位输入仍不命中 5 位云端码
        val guarded = remoteEngine(row, minTriggerLength = 1)
        assertTrue(guarded.lookup("dtws", 10).none { it.kind == LookupKind.QUICKAPPLY })
        assertEquals("敲满仍命中", longBody, guarded.lookup("dtwsl", 10).first().insertText)

        // 对照组：同一行不守卫时 4 位就会命中，说明拦住它的是长度守卫，不是 minTriggerLength
        val unguarded = requireNotNull(QuickPhraseEngine.fromText(row, minTriggerLength = 1))
        assertEquals(longBody, unguarded.lookup("dtws", 10).first().insertText)
    }

    @Test
    fun `内置资产与用户文件仍按声明的匹配方式解析`() {
        // fromText 是内置 assets / quick_phrases_user.txt / CSV 导入的解析入口，>4 位 + prefix 是有意行为
        // （搜狗 18 条 5 位常用语、导入说明「打前 4 位即可前缀命中」，ADR-0002 C1-5 / C1-10），不得被守卫改写
        val quick = localEngine(legacyRow("dtwsl", "PREFIX", "雨天泳池"))

        assertEquals(QuickMatchMode.PREFIX, quick.entries.single().mode)
        assertEquals("雨天泳池", quick.lookup("dtws", 10).firstOrNull()?.insertText)
    }

    @Test
    fun `守卫不影响换行转义的还原`() {
        val row = "dtwsl\tdtwsl\t第一行\\n第二行\tCONTAINS\t110\ta"
        val hit = remoteEngine(row).lookup("dtwsl", 10).first()

        assertEquals("第一行\n第二行", hit.insertText)
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
