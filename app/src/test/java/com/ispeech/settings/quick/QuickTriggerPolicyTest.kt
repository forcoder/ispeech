package com.ispeech.settings.quick

import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷字母规则：长度范围与「匹配方式」推导。
 *
 * 关键约束：`QuickPhraseEngine.matches` 对 prefix / contains 有 `minTriggerLength = 2` 守卫，
 * 1 位快捷字母若沿用 prefix，输入该字母时**一个候选都不会出**（规则静默失效）。
 * 因此 1 位必须强制 exact —— 这里既测策略本身，也用真实引擎验证强转后确实命中。
 */
class QuickTriggerPolicyTest {

    @Test
    fun `1 到 8 位小写字母合法`() {
        assertTrue(QuickTriggerPolicy.isValid("a"))
        assertTrue(QuickTriggerPolicy.isValid("ab"))
        assertTrue(QuickTriggerPolicy.isValid("abcd"))
        assertTrue(QuickTriggerPolicy.isValid("abcdefgh"))
    }

    @Test
    fun `空串与超过 8 位不合法`() {
        assertFalse(QuickTriggerPolicy.isValid(""))
        assertFalse(QuickTriggerPolicy.isValid("abcdefghi"))
    }

    @Test
    fun `大写字母 数字 符号不合法`() {
        assertFalse(QuickTriggerPolicy.isValid("Ab"))
        assertFalse(QuickTriggerPolicy.isValid("a1"))
        assertFalse(QuickTriggerPolicy.isValid("a-"))
        assertFalse(QuickTriggerPolicy.isValid("快捷"))
    }

    @Test
    fun `1 位快捷字母强制 exact 匹配`() {
        QuickMatchMode.entries.forEach { requested ->
            assertEquals(
                "1 位 + $requested 会被 minTriggerLength 守卫拦下，必须转成 EXACT",
                QuickMatchMode.EXACT,
                QuickTriggerPolicy.effectiveMode("a", requested),
            )
        }
    }

    @Test
    fun `2 位及以上保留用户选择的匹配方式`() {
        QuickMatchMode.entries.forEach { requested ->
            assertEquals(requested, QuickTriggerPolicy.effectiveMode("ab", requested))
            assertEquals(requested, QuickTriggerPolicy.effectiveMode("abcdefgh", requested))
        }
    }

    @Test
    fun `超过五笔缓冲上限 4 位的快捷字母被标记`() {
        assertFalse(QuickTriggerPolicy.longerThanWubiBuffer("abcd"))
        assertTrue(QuickTriggerPolicy.longerThanWubiBuffer("abcde"))
        assertTrue(QuickTriggerPolicy.longerThanWubiBuffer("abcdefgh"))
    }

    /** 端到端：1 位快捷字母经策略强转 exact 后，输入该字母即命中且排在首位。 */
    @Test
    fun `1 位快捷字母强转 exact 后能被输入法命中`() {
        val rule = "a\t常用回复\t收到，稍后给您答复\t" +
            QuickTriggerPolicy.effectiveMode("a", QuickMatchMode.PREFIX).name.lowercase() + "\t100\ta"
        val engine = requireNotNull(QuickPhraseEngine.fromText(rule)) { "规则应能解析" }

        val hits = engine.lookup("a", 10)
        assertTrue("1 位 exact 规则应在输入 a 时命中（prefix 会静默失效）", hits.isNotEmpty())
        assertEquals("收到，稍后给您答复", hits.first().insertText)
    }

    /** 反向确认守卫确实存在：同一份规则若用 prefix，输入 a 命中不了。 */
    @Test
    fun `1 位 prefix 规则在输入 a 时确实命中不了`() {
        val engine = requireNotNull(
            QuickPhraseEngine.fromText("a\t常用回复\t收到，稍后给您答复\tprefix\t100\ta"),
        ) { "规则应能解析" }

        assertTrue(
            "prefix + 1 位应被 minTriggerLength=2 拦下 —— 这正是必须强转 exact 的原因",
            engine.lookup("a", 10).isEmpty(),
        )
    }

    /** 5–8 位在拼音态（缓冲上限 12）能完整打出来并按精确码命中。 */
    @Test
    fun `超过 4 位的快捷字母完整输入时仍精确命中`() {
        val rule = "abcdefgh\t长快捷字母\t完整输入即命中\tprefix\t100\ta"
        val engine = requireNotNull(QuickPhraseEngine.fromText(rule)) { "规则应能解析" }

        assertEquals(
            "完整输入应精确命中",
            "完整输入即命中",
            engine.lookup("abcdefgh", 10).firstOrNull()?.insertText,
        )
    }

    /**
     * 云端关键字：1 位必须下发 EXACT。
     *
     * 后端默认 match_type 是 CONTAINS，回读后映射成 `QuickMatchMode.CONTAINS`，
     * 同样被 `minTriggerLength` 守卫拦下 —— 云端新建的 1 位简写在输入法里不会有候选。
     * 2 位以上不下发（null），交给后端默认 CONTAINS，与远端条目既有语义一致。
     */
    @Test
    fun `云端 1 位关键字下发 EXACT 其余不下发`() {
        assertEquals("EXACT", QuickTriggerPolicy.remoteMatchType("a"))
        assertEquals(null, QuickTriggerPolicy.remoteMatchType("ab"))
        assertEquals(null, QuickTriggerPolicy.remoteMatchType("abcdefgh"))
    }
}