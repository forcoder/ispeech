package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 决策一：快捷字母校验的唯一校验器。
 *
 * VOCA-729 起不再限制位数（原 2–12 位契约废止），只校验非空与 a–z；
 * 长度常量退化为可达性提示阈值。这里把口径钉成一份，防止再分裂。
 */
class QuickTriggerTest {

    @Test
    fun `VOCA-729 快捷字母不限位数`() {
        assertTrue("单字母也应允许", QuickTrigger.isValidShortcutTrigger("d"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("yc"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("ylwsl"))
        assertTrue("不再有 12 位上限", QuickTrigger.isValidShortcutTrigger("a".repeat(13)))
        assertTrue(QuickTrigger.isValidShortcutTrigger("a".repeat(64)))
        assertFalse("空串仍不合法", QuickTrigger.isValidShortcutTrigger(""))
        assertFalse("非字母仍不合法", QuickTrigger.isValidShortcutTrigger("dt1"))
        assertNull(QuickTrigger.issue("a".repeat(13)))
        assertNull(QuickTrigger.issue("d"))
    }

    @Test
    fun `C1-1 合法简写是小写字母且不限位数`() {
        assertTrue(QuickTrigger.isValidShortcutTrigger("yc"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("dtwsl"))
        assertTrue(QuickTrigger.isValidShortcutTrigger("abcdefghijkl"))
        assertTrue("单字母也合法", QuickTrigger.isValidShortcutTrigger("d"))
        assertTrue("超过 12 位仍合法", QuickTrigger.isValidShortcutTrigger("abcdefghijklm"))
        assertFalse("数字不合法", QuickTrigger.isValidShortcutTrigger("dtws1"))
        assertFalse("符号不合法", QuickTrigger.isValidShortcutTrigger("dtw-s"))
    }

    @Test
    fun `C1-1 判定前先归一化`() {
        assertEquals("dtwsl", QuickTrigger.normalize("  DTWsl "))
        assertTrue(QuickTrigger.isValidShortcutTrigger("  DTWsl "))
    }

    @Test
    fun `C1-7 非法原因可区分两态`() {
        assertEquals(QuickTriggerIssue.EMPTY, QuickTrigger.issue(""))
        assertEquals(QuickTriggerIssue.NOT_LETTERS, QuickTrigger.issue("dt1"))
        assertNull("单字母不再判死", QuickTrigger.issue("d"))
        assertNull("超长不再判死", QuickTrigger.issue("a".repeat(13)))
        assertNull(QuickTrigger.issue("dtwsl"))
    }

    @Test
    fun `C1-6 覆盖条目的 mode 只由长度给出`() {
        assertEquals(QuickMatchMode.PREFIX, QuickTrigger.overrideMode("dtws"))
        assertEquals("单字母会被引擎 minTriggerLength 守卫拦下，必须 EXACT",
            QuickMatchMode.EXACT, QuickTrigger.overrideMode("d"))
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
