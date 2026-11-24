package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目 1 的 `(keyword, cursor)` 双状态与**四条写入路径汇入单入口**（AC-1.1~1.4 / 1.9）。
 *
 * 为什么测 [PanelKeywordState] 而不是直接测服务：服务是 Android 组件、JVM 单测里起不来；
 * 而「四条路径是否真的汇到一个入口」这件事的实质是「改状态的算式只有一份」——
 * 这里逐条复刻软键 / 硬件键盘 / 候选上屏 / `✕` 与服务侧调用的同一个方法，
 * 断言它们都得到同一套结果，服务侧那些方法体只是把返回值换上去（见 `IspeechImeService`）。
 *
 * 索引单位是码点（R1-1），所以本文件也锁住 emoji 不拆半。
 */
class PanelKeywordStateTest {

    // ── AC-1.1 / 1.2 / 1.3 / 1.4 / 1.9：规格表格逐条 ──

    @Test
    fun `AC-1_1 光标索引 2 插入 X 得到 abXcd 且光标 3`() {
        assertEquals(PanelKeywordState("abXcd", 3), PanelKeywordState("abcd", 2).insert("X"))
    }

    @Test
    fun `AC-1_2 光标索引 2 退格得到 acd 且光标 1`() {
        assertEquals(PanelKeywordState("acd", 1), PanelKeywordState("abcd", 2).backspace())
    }

    @Test
    fun `AC-1_3 光标在 0 左移仍在 0 且状态不变`() {
        val state = PanelKeywordState("abcd", 0)
        assertEquals(0, state.moveBy(-1).cursor)
        assertEquals("abcd", state.moveBy(-1).keyword)
        assertTrue("光标在 0 时 atStart 必须为 true（◀ 的禁用态判据）", state.atStart)
    }

    @Test
    fun `AC-1_4 光标在末尾右移不越界`() {
        val state = PanelKeywordState("abcd", 4)
        assertEquals(4, state.moveBy(1).cursor)
        assertTrue("光标在末尾时 atEnd 必须为 true（▶ 的禁用态判据）", state.atEnd)
    }

    @Test
    fun `AC-1_9 光标中间连输入 3 字符 插入光标处且光标加 3 末尾不截断`() {
        var state = PanelKeywordState("abcd", 2)
        for (letter in listOf("X", "Y", "Z")) {
            state = state.insert(letter)
        }
        assertEquals("abXYZcd", state.keyword)
        assertEquals(5, state.cursor)
    }

    @Test
    fun `R1-8 连续右移 5 次停在末尾不环绕`() {
        var state = PanelKeywordState("abcd", 2)
        repeat(5) { state = state.moveBy(1) }
        assertEquals(4, state.cursor)
    }

    @Test
    fun `R1-10 先左移再退格得到 bcd 且光标 0`() {
        val state = PanelKeywordState("abcd", 2).moveBy(-1).backspace()
        assertEquals("bcd", state.keyword)
        assertEquals(0, state.cursor)
    }

    // ── 四条写入路径：同一套算式，结果必须逐字相同 ──

    @Test
    fun `四条写入路径都落在光标处而不是末尾`() {
        // 软键字母 / 硬件键盘字母都调 insertPanelText(1 字符)
        assertEquals(PanelKeywordState("abXcd", 3), PanelKeywordState("abcd", 2).insert("X"))
        // 候选上屏调 insertPanelText(整段)：按码点数前移，不是按码元数
        assertEquals(PanelKeywordState("ab你好cd", 4), PanelKeywordState("abcd", 2).insert("你好"))
        // ✕ 清空调 replace("")
        assertEquals(PanelKeywordState.EMPTY, PanelKeywordState("abcd", 2).replace(""))
        // 退格调 backspace()
        assertEquals(PanelKeywordState("acd", 1), PanelKeywordState("abcd", 2).backspace())
    }

    @Test
    fun `候选整段上屏按码点数前移光标`() {
        // 「😀」是 1 个码点 2 个码元：光标必须 +1，否则下次退格会拆到它中间
        val state = PanelKeywordState("ab", 1).insert("😀")
        assertEquals("a😀b", state.keyword)
        assertEquals(2, state.cursor)
    }

    // ── 码点口径（本单第一件事，非协商项） ──

    @Test
    fun `emoji 退格删整个码点不留孤立代理`() {
        val state = PanelKeywordState("a😀d", 2).backspace()
        assertEquals("ad", state.keyword)
        assertEquals(1, state.cursor)
        assertNoLoneSurrogate(state.keyword)
    }

    @Test
    fun `emoji 处插入不拆半`() {
        val state = PanelKeywordState("a😀d", 2).insert("X")
        assertEquals("a😀Xd", state.keyword)
        assertEquals(3, state.cursor)
        assertNoLoneSurrogate(state.keyword)
    }

    @Test
    fun `emoji 左右移按码点走`() {
        assertEquals(2, PanelKeywordState("a😀d", 3).moveBy(-1).cursor)
        assertEquals(1, PanelKeywordState("a😀d", 2).moveBy(-1).cursor)
        assertEquals(3, PanelKeywordState("a😀d", 2).moveBy(1).cursor)
    }

    @Test
    fun `构造时越界光标被夹紧 结尾不报错`() {
        assertEquals(4, PanelKeywordState("abcd", 99).safeCursor)
        assertEquals(0, PanelKeywordState("abcd", -7).safeCursor)
        assertEquals(0, PanelKeywordState("", 9).safeCursor)
    }

    // ── AC-1.5 / AC-1.6：空词与清空 ──

    @Test
    fun `AC-1_5 空词退格无变化`() {
        assertEquals(PanelKeywordState.EMPTY, PanelKeywordState.EMPTY.backspace())
        assertEquals(PanelKeywordState.EMPTY, PanelKeywordState.EMPTY.deleteAfter())
    }

    @Test
    fun `AC-1_6 点叉清空后光标归 0 且 atEnd 也为真`() {
        val cleared = PanelKeywordState("abcd", 2).replace("")
        assertEquals("", cleared.keyword)
        assertEquals(0, cleared.cursor)
        // 空词既在最前也在最后：两个移动键都该进禁用态（规格 §3.9 打开·空 一行）
        assertTrue(cleared.atStart)
        assertTrue(cleared.atEnd)
    }

    @Test
    fun `R1-43 向后删删光标后一个码点`() {
        assertEquals(PanelKeywordState("acd", 1), PanelKeywordState("abcd", 1).deleteAfter())
        // 末尾 / 空词无变化
        assertEquals(PanelKeywordState("abcd", 4), PanelKeywordState("abcd", 4).deleteAfter())
    }

    @Test
    fun `R1-31 重新打开回到末尾 不记忆上次中间位置`() {
        // 收起重开 = EMPTY，再以新种子 replace 后光标落在末尾（AC-1.10）
        val reopened = PanelKeywordState.EMPTY.replace("abcdef")
        assertEquals(6, reopened.cursor)
        assertTrue(reopened.atEnd)
        assertFalse(reopened.atStart)
    }

    private fun assertNoLoneSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("位置 $i 的高位代理没有配对：$text",
                    i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
            } else {
                assertFalse("位置 $i 出现没有配对的低位代理：$text", Character.isLowSurrogate(c))
                i += 1
            }
        }
    }
}