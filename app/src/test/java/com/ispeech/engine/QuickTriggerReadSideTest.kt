package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 C1-5：读取侧从不校验长度、从不截断。
 *
 * 校验器只管「新建 / 编辑」入口。VOCA-729 起编辑器不限位数，但设备上更长的存量条目
 * 仍必须照常解析、照常匹配，读取侧永不校验长度。
 */
class QuickTriggerReadSideTest {

    /** 13 位：超过拼音态组合串上限，读取侧仍要能用。 */
    private val longTrigger = "abcdefghijklm"

    @Test
    fun `超上限触发码完整解析且不截断`() {
        val row = "$longTrigger\t长码短语\t正文内容\tprefix\t100\ta"

        val engine = requireNotNull(QuickPhraseEngine.fromText(row))

        assertEquals(longTrigger, engine.entries.single().trigger)
        assertEquals(13, engine.entries.single().trigger.length)
        assertEquals(QuickMatchMode.PREFIX, engine.entries.single().mode)
    }

    @Test
    fun `超上限触发码敲全仍命中`() {
        val row = "$longTrigger\t长码短语\t正文内容\tprefix\t100\ta"
        val engine = requireNotNull(QuickPhraseEngine.fromText(row))

        val hit = engine.lookup(longTrigger, 10).firstOrNull { it.insertText == "正文内容" }

        assertNotNull("读取侧不能因为超长就把规则判死", hit)
    }

    @Test
    fun `单字母守卫与长度上限无关`() {
        val row = "$longTrigger\t长码短语\t正文内容\tprefix\t100\ta"
        val engine = requireNotNull(QuickPhraseEngine.fromText(row))

        assertTrue(
            "单字母仍按防误触守卫不命中长码规则",
            engine.lookup("a", 10).none { it.insertText == "正文内容" },
        )
    }

    @Test
    fun `12 位合法码与 13 位超上限码都能解析`() {
        val text = buildString {
            append("abcdefghijkl\t十二位\t正文A\tprefix\t100\ta\n")
            append("$longTrigger\t十三位\t正文B\tprefix\t100\ta\n")
        }

        val engine = requireNotNull(QuickPhraseEngine.fromText(text))

        assertEquals(2, engine.entries.size)
        assertEquals("正文A", engine.lookup("abcdefghijkl", 10).first().insertText)
        assertEquals("正文B", engine.lookup(longTrigger, 10).first().insertText)
    }
}
