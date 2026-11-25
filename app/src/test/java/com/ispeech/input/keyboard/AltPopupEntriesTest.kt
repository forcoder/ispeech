package com.ispeech.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字母键长按浮层的条目生成（VOCA-65 条目 2）。
 *
 * AC-2.7 / AC-2.8 在验收文件里判为 JVM 层，所以这两条必须能在没有设备、没有 View 的情况下
 * 直接断言 [AltPopupEntries.build]，而不是只在真机上肉眼点数。
 */
class AltPopupEntriesTest {

    /** 26 个字母键：中英文两张字母页内容一致（R2-12），这里取中文页。 */
    private fun letterKeys(): List<KeyDef> =
        KeyboardLayouts.letterZh().rows.flatten().filter { AltPopupEntries.isLetterKey(it) }

    @Test
    fun `AC27 条目顺序是小写 大写 角标 且只可能 2 项或 3 项`() {
        val keys = letterKeys()
        assertEquals("字母页应有 26 个字母键", 26, keys.size)
        for (key in keys) {
            val entries = AltPopupEntries.build(key)
            val lower = key.output.lowercase()
            val upper = key.output.uppercase()
            val expected = if (key.hint.isEmpty() || key.hint == lower || key.hint == upper) {
                listOf(lower, upper)
            } else {
                listOf(lower, upper, key.hint)
            }
            assertEquals("${key.output} 的浮层条目", expected, entries)
            assertTrue("${key.output} 的条目数要落在 {2,3}", entries.size == 2 || entries.size == 3)
        }
    }

    @Test
    fun `AC27 去重按字符串完全相同 不做大小写归一化`() {
        // 角标与小写相同：只留首次出现的 q，条目降到 2 项
        assertEquals(listOf("q", "Q"), AltPopupEntries.build(KeyDef.char("q", hint = "q")))
        // 角标与大写相同：同理
        assertEquals(listOf("q", "Q"), AltPopupEntries.build(KeyDef.char("q", hint = "Q")))
        // 只按大小写不同的两项都保留：q 与 Q 不算重复
        assertEquals(listOf("q", "Q"), AltPopupEntries.build(KeyDef.char("q")))
    }

    @Test
    fun `AC21 数据面 q 浮层是 q Q 1 a 浮层是 a A 波浪`() {
        val q = KeyboardLayouts.letterZh().rows[0].first { it.output == "q" }
        assertEquals(listOf("q", "Q", "1"), AltPopupEntries.build(q))
        val a = KeyboardLayouts.letterZh().rows[1].first { it.output == "a" }
        assertEquals(listOf("a", "A", "~"), AltPopupEntries.build(a))
    }

    @Test
    fun `AC28 每个字母键的角标要么为空 要么恰好一个码点`() {
        for (key in letterKeys()) {
            val points = key.hint.codePointCount(0, key.hint.length)
            assertTrue("${key.output} 的角标超出 1 个码点：`${key.hint}`", points <= 1)
        }
    }

    @Test
    fun `AC28 m 键角标明确为空 浮层只有 m 和 M`() {
        val m = KeyboardLayouts.letterZh().rows[2].first { it.output == "m" }
        assertEquals("", m.hint)
        assertEquals(listOf("m", "M"), AltPopupEntries.build(m))
    }

    @Test
    fun `R212 英文字母页与中文页的浮层内容一致`() {
        val zh = KeyboardLayouts.letterZh().rows.flatten()
            .filter { AltPopupEntries.isLetterKey(it) }
            .map { AltPopupEntries.build(it) }
        val en = KeyboardLayouts.letterEn().rows.flatten()
            .filter { AltPopupEntries.isLetterKey(it) }
            .map { AltPopupEntries.build(it) }
        assertEquals(zh, en)
    }

    @Test
    fun `非字母键不参与字母浮层 沿用键位自带候选`() {
        val digit = KeyDef.char("1", alt = listOf("１", "⑴"))
        assertTrue(AltPopupEntries.isLetterKey(digit).not())
        assertEquals(listOf("１", "⑴"), AltPopupEntries.build(digit))

        // emoji 是代理对，长度 2：既不是字母，也没有自带候选
        val emoji = KeyDef.char("🙂")
        assertTrue(AltPopupEntries.isLetterKey(emoji).not())
        assertEquals(emptyList<String>(), AltPopupEntries.build(emoji))

        val shift = KeyDef.fn(KeyAction.SHIFT, "⇧")
        assertEquals(emptyList<String>(), AltPopupEntries.build(shift))
    }
}
