package com.ispeech.settings.quick

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地规则新增 / 编辑的列表契约（VOCA-74）。
 *
 * 重点是「编辑时改了快捷字母」这一条：只按新 trigger 查找替换的话，旧条目会留在列表与文件里
 * 成为孤儿——列表多一条、输入法两个字母都能命中。
 */
class QuickPhraseEditsTest {

    private fun entry(trigger: String, content: String = "内容-$trigger") = QuickEntry(
        trigger = trigger,
        label = content,
        content = content,
        weight = 100,
        mode = QuickMatchMode.PREFIX,
        kind = LookupKind.QUICKAPPLY,
    )

    @Test
    fun `新增条目追加到列表且来源统一为用户`() {
        val result = QuickPhraseEdits.upsert(emptyList(), entry("thx").copy(source = QuickSource.REMOTE))
        assertEquals(1, result.size)
        assertEquals("thx", result[0].trigger)
        assertEquals(QuickSource.USER, result[0].source)
    }

    @Test
    fun `同快捷字母再新增是原地覆盖而不是追加`() {
        val existing = listOf(entry("thx", "谢谢"))
        val result = QuickPhraseEdits.upsert(existing, entry("thx", "多谢"))

        assertEquals(1, result.size)
        assertEquals("多谢", result[0].content)
    }

    @Test
    fun `编辑改快捷字母后旧条目被移除`() {
        val existing = listOf(entry("thx"), entry("brb"))
        val result = QuickPhraseEdits.upsert(existing, entry("thks"), originalTrigger = "thx")

        assertEquals(2, result.size)
        assertTrue(result.none { it.trigger == "thx" })
        assertEquals("thks", result[1].trigger)
    }

    @Test
    fun `编辑改成的快捷字母已存在时只留一条`() {
        val existing = listOf(entry("thx"), entry("thks"))
        val result = QuickPhraseEdits.upsert(existing, entry("thks", "新的"), originalTrigger = "thx")

        assertEquals(1, result.size)
        assertEquals("thks", result[0].trigger)
        assertEquals("新的", result[0].content)
    }

    @Test
    fun `快捷字母未变时原地更新不改变顺序`() {
        val existing = listOf(entry("aaa"), entry("thx"), entry("zzz"))
        val result = QuickPhraseEdits.upsert(existing, entry("thx", "改过"), originalTrigger = "thx")

        assertEquals(listOf("aaa", "thx", "zzz"), result.map { it.trigger })
        assertEquals("改过", result[1].content)
    }
}