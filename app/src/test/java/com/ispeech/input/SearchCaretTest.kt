package com.ispeech.input

import org.junit.Assert.assertEquals
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
        assertEquals(0, SearchCaret.clamp(0, 0))
        assertEquals(0, SearchCaret.clamp(0, 7))
        assertEquals(0, SearchCaret.clamp(0, -3))
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
        assertEquals(0, SearchCaret.clamp(4, -1))
        assertEquals(4, SearchCaret.clamp(4, 99))
        assertEquals(2, SearchCaret.clamp(4, 2))
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
}