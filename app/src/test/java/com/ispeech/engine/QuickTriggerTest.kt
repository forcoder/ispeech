package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 决策一：快捷字母长度契约的唯一校验器。
 *
 * 之前编辑器写死 2–4 位，而仓库自己的数据校验器与既有数据都允许到 12 位，
 * 结果是「数据里存在的东西 UI 不让写」。这里把口径钉成一份，防止再分裂。
 */
class QuickTriggerTest {

    @Test
    fun `C1-1 合法简写是 2 到 12 位小写字母`() {
        assertTrue(QuickTrigger.isValidShortcutTrigger("yc"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("dtwsl"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("abcdefghijkl"))
        assertFalse("单字母留给五笔一级简码", QuickTrigger.isValidShortcutTrigger("d"))
        assertFalse("超过拼音态组合串上限", QuickTrigger.isValidShortcutTrigger("abcdefghijklm"))
        assertFalse("数字不合法", QuickTrigger.isValidShortcutTrigger("dtws1"))
        assertFalse("符号不合法", QuickTrigger.isValidShortcutTrigger("dtw-s"))
    }

    @Test
    fun `C1-1 判定前先归一化`() {
        assertEquals("dtwsl", QuickTrigger.normalize("  DTWsl "))
        assertTrue(QuickTrigger.isValidShortcutTrigger("  DTWsl "))
    }

    @Test
    fun `C1-7 非法原因可区分四态`() {
        assertEquals(QuickTriggerIssue.EMPTY, QuickTrigger.issue(""))
        assertEquals(QuickTriggerIssue.TOO_SHORT, QuickTrigger.issue("d"))
        assertEquals(QuickTriggerIssue.TOO_LONG, QuickTrigger.issue("a".repeat(13)))
        assertEquals(QuickTriggerIssue.NOT_LETTERS, QuickTrigger.issue("dt1"))
        assertNull(QuickTrigger.issue("dtwsl"))
    }

    @Test
    fun `C1-6 覆盖条目的 mode 只由长度给出`() {
        assertEquals(QuickMatchMode.PREFIX, QuickTrigger.overrideMode("dtws"))
        assertEquals("5 位码在五笔态打不全，前缀匹配会让长正文压掉正在输入的词",
            QuickMatchMode.EXACT, QuickTrigger.overrideMode("dtwsl"))
    }

    @Test
    fun `C1-9 长码配前缀或包含才有误弹风险`() {
        assertTrue(QuickTrigger.mayMisfire("dtwsl", QuickMatchMode.PREFIX))
        assertTrue(QuickTrigger.mayMisfire("dtwsl", QuickMatchMode.CONTAINS))
        assertFalse(QuickTrigger.mayMisfire("dtwsl", QuickMatchMode.EXACT))
        assertFalse(QuickTrigger.mayMisfire("dtwsl", QuickMatchMode.REGEX))
        assertFalse("4 位码两态都能敲全，不算风险", QuickTrigger.mayMisfire("dtws", QuickMatchMode.PREFIX))
    }

    @Test
    fun `C1-8 上限与缓冲区口径`() {
        assertEquals(12, QuickTrigger.MAX_TRIGGER_LENGTH)
        assertEquals(4, QuickTrigger.WUBI_REACHABLE_LENGTH)
        assertEquals(2, QuickTrigger.MIN_TRIGGER_LENGTH)
        assertTrue(QuickTrigger.isBeyondWubiBuffer("dtwsl"))
        assertFalse(QuickTrigger.isBeyondWubiBuffer("dtws"))
    }
}
