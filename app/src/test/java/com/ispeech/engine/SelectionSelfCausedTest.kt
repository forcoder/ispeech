package com.ispeech.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自因光标过滤（AC-3.4 的 JVM 段 ③④）：把「是否本服务自己造成的光标变化」表驱动断言。
 *
 * 判定纯逻辑放在这里而不是 [com.ispeech.input.IspeechImeService] 里，是为了让 AC-3.4 的
 * 「自因不误中断 / 他因必中断」两类行为能被 JVM 直接覆盖；真机只负责验回调时序。
 */
class SelectionSelfCausedTest {

    @Test
    fun `光标位置与上次已知值相同时是自因`() {
        assertTrue(isSelfCausedSelectionChange(newSelStart = 12, lastCommittedCursor = 12, tolerance = 0))
    }

    @Test
    fun `光标位置变化超过容差即他因`() {
        assertFalse(isSelfCausedSelectionChange(newSelStart = 13, lastCommittedCursor = 12, tolerance = 0))
        assertFalse(isSelfCausedSelectionChange(newSelStart = 4, lastCommittedCursor = 12, tolerance = 0))
    }

    @Test
    fun `容差内的前移后移都算自因`() {
        assertTrue(isSelfCausedSelectionChange(newSelStart = 15, lastCommittedCursor = 12, tolerance = 3))
        assertTrue(isSelfCausedSelectionChange(newSelStart = 9, lastCommittedCursor = 12, tolerance = 3))
        assertFalse(isSelfCausedSelectionChange(newSelStart = 16, lastCommittedCursor = 12, tolerance = 3))
    }

    @Test
    fun `还没观测到光标基线时不判中断`() {
        assertTrue(
            "首次回调（lastCommittedCursor = -1）不该把正常上屏判成移动光标",
            isSelfCausedSelectionChange(newSelStart = 7, lastCommittedCursor = -1, tolerance = 0),
        )
    }

    @Test
    fun `负容差按 0 处理`() {
        assertTrue(isSelfCausedSelectionChange(newSelStart = 5, lastCommittedCursor = 5, tolerance = -2))
        assertFalse(isSelfCausedSelectionChange(newSelStart = 6, lastCommittedCursor = 5, tolerance = -2))
    }
}