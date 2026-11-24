package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * VOCA-627（D-2）搜索框「文本 + 光标」双状态的可判定性测试。
 *
 * D-2 只恢复渲染面，但「光标位置可被读取、且不被钉在末尾」必须在 JVM 层可验证，
 * 否则条目 1 的 AC-1.1~1.4 / 1.9 在接服务前无法判定。所有断言都走 [SearchCaret] 纯函数，
 * 与服务侧将来的接线调用同一份算式，不重复实现。
 */
class SearchCaretTest {

    // ── D2-5：空搜索框光标在索引 0，不报错 ──

    @Test
    fun `空文本时光标索引恒为 0`() {
        assertEquals(0, SearchCaret.clamp("", 0))
        assertEquals(0, SearchCaret.clamp("", 7))
        assertEquals(0, SearchCaret.clamp("", -3))
        assertEquals(0, SearchCaret.moveBy("", 0, -1))
        assertEquals(0, SearchCaret.moveBy("", 0, 1))
    }

    @Test
    fun `空文本退格无变化且不抛异常`() {
        val (text, cursor) = SearchCaret.backspace("", 0)
        assertEquals("", text)
        assertEquals(0, cursor)
    }

    // ── AC-1.1 / AC-1.9：光标处插入 ──

    @Test
    fun `光标索引 2 处插入 X 得到 abXcd 且光标移到 3`() {
        val (text, cursor) = SearchCaret.insert("abcd", 2, "X")
        assertEquals("abXcd", text)
        assertEquals(3, cursor)
    }

    @Test
    fun `连续输入 3 个字母按序插在光标处 末尾内容不被截断`() {
        var text = "abcd"
        var cursor = 2
        for (letter in listOf("1", "2", "3")) {
            val next = SearchCaret.insert(text, cursor, letter)
            text = next.first
            cursor = next.second
        }
        assertEquals("ab123cd", text)
        assertEquals(5, cursor)
    }

    @Test
    fun `末尾插入等价于追加`() {
        val (text, cursor) = SearchCaret.insert("abcd", 4, "Z")
        assertEquals("abcdZ", text)
        assertEquals(5, cursor)
    }

    // ── AC-1.2：退格删光标前一个字符 ──

    @Test
    fun `光标索引 2 退格得到 acd 且光标移到 1`() {
        val (text, cursor) = SearchCaret.backspace("abcd", 2)
        assertEquals("acd", text)
        assertEquals(1, cursor)
    }

    @Test
    fun `光标在 0 时退格原样返回`() {
        val (text, cursor) = SearchCaret.backspace("abcd", 0)
        assertEquals("abcd", text)
        assertEquals(0, cursor)
    }

    // ── AC-1.3 / AC-1.4：移动不越界 ──

    @Test
    fun `光标在 0 左移仍为 0`() {
        assertEquals(0, SearchCaret.moveBy("abcd", 0, -1))
    }

    @Test
    fun `光标在末尾右移仍为末尾`() {
        assertEquals(4, SearchCaret.moveBy("abcd", 4, 1))
    }

    @Test
    fun `越界下标一律夹紧`() {
        assertEquals(0, SearchCaret.clamp("abcd", -1))
        assertEquals(4, SearchCaret.clamp("abcd", 99))
        assertEquals(2, SearchCaret.clamp("abcd", 2))
    }

    @Test
    fun `先左移再插入落在中间而不是末尾`() {
        // 「光标在中间」的关键回归：位置是独立状态，不会因为文本没变又跳回末尾。
        val cursor = SearchCaret.moveBy("abcd", 4, -2)
        assertEquals(2, cursor)
        val (text, after) = SearchCaret.insert("abcd", cursor, "X")
        assertEquals("abXcd", text)
        assertEquals(3, after)
    }

    // ── R1-1：索引一律按码点，代理对不拆半（本单第一件事，非协商项） ──

    /** 断言串里没有孤立代理：每个代理都必须成对出现，否则渲染成豆腐块、上屏即成乱码。 */
    private fun assertNoLoneSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) -> {
                    assertTrue(
                        "位置 $i 的高位代理后面没有低位代理（孤立代理）：$text",
                        i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
                    )
                    i += 2
                }
                Character.isLowSurrogate(c) -> {
                    fail("位置 $i 出现没有配对的低位代理（孤立代理）：$text")
                    i += 1
                }
                else -> i += 1
            }
        }
    }

    @Test
    fun `emoji 算一个码点 长度不是 UTF-16 码元数`() {
        // 😀 在 UTF-16 里长 2，码点口径必须是 1
        assertEquals(3, SearchCaret.length("a😀d"))
        assertEquals(4, "a😀d".length)
    }

    @Test
    fun `退格删掉整个 emoji 而不是半个代理对`() {
        // 光标在 emoji 之后（码点 2）：删的是 emoji 整个码点
        val (text, cursor) = SearchCaret.backspace("a😀d", 2)
        assertEquals("ad", text)
        assertEquals(1, cursor)
        assertNoLoneSurrogate(text)
    }

    @Test
    fun `插入落在 emoji 之后 不拆半`() {
        // 光标在 emoji 之后（码点 2）插入 X → a😀Xd，光标 3
        val (text, cursor) = SearchCaret.insert("a😀d", 2, "X")
        assertEquals("a😀Xd", text)
        assertEquals(3, cursor)
        assertNoLoneSurrogate(text)
    }

    @Test
    fun `插入内容自身带 emoji 时光标按码点数前移`() {
        // 插入 2 个码点的 emoji，光标应 +2 而不是 +4（insert.length 是码元数）
        val (text, cursor) = SearchCaret.insert("ab", 1, "😀")
        assertEquals("a😀b", text)
        assertEquals(2, cursor)
        assertNoLoneSurrogate(text)
    }

    @Test
    fun `左右移按码点走 不会停在代理对中间`() {
        // 从末尾（码点 3）左移 1 → 2（emoji 之后）；再左移 → 1（emoji 之前）
        assertEquals(2, SearchCaret.moveBy("a😀d", 3, -1))
        assertEquals(1, SearchCaret.moveBy("a😀d", 2, -1))
        // 光标在 emoji 之前退格删的是 a，不碰 emoji
        val (text, _) = SearchCaret.backspace("a😀d", 1)
        assertEquals("😀d", text)
        assertNoLoneSurrogate(text)
    }

    @Test
    fun `码点口径下越界仍夹紧`() {
        assertEquals(0, SearchCaret.clamp("a😀d", -1))
        assertEquals(3, SearchCaret.clamp("a😀d", 99))
        assertEquals(2, SearchCaret.clamp("a😀d", 2))
    }

    @Test
    fun `向后删也按码点`() {
        val (text, cursor) = SearchCaret.deleteAfter("a😀d", 1)
        assertEquals("ad", text)
        assertEquals(1, cursor)
        assertNoLoneSurrogate(text)
        // 末尾 / 空词无变化
        assertEquals("a😀d" to 3, SearchCaret.deleteAfter("a😀d", 3))
        assertEquals("" to 0, SearchCaret.deleteAfter("", 0))
    }
}