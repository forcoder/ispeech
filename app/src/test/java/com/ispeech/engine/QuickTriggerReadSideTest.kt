package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 C1-5：读取侧从不校验长度、从不截断。
 *
 * 校验器只管「新建 / 编辑」入口。设备上已有的超上限条目（手改文件、旧版本导入）
 * 必须照常解析、照常匹配，否则放宽编辑器反而会让存量数据失效。
 */
class QuickTriggerReadSideTest {

    /** 13 位：编辑器（2–12）不让写，读取侧却要能用。 */
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
