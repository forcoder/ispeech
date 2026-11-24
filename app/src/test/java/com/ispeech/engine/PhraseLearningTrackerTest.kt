package com.ispeech.engine

import com.ispeech.data.codePointLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词组学习状态机（ADR-0001 §7.2 T-1）：覆盖 AC3_1 ~ 3.6、3.9 与打断类 3.3 / 3.4 的 JVM 段。
 *
 * 全部用例用可变时钟桩注入时间，不依赖真实时间。
 */
class PhraseLearningTrackerTest {

    /** 单字 → 全码的桩词库；只放测试用到的字。 */
    private val codes = mapOf(
        "喂" to "klge",
        "猫" to "qtal",
        "加" to "lkg",
        "班" to "gytg",
        "你" to "wqiy",
        "嘿" to "klf",
        "产" to "ute",
    )

    private var now = 0L

    private fun tracker(
        existingWords: Set<String> = emptySet(),
        codeOf: (String) -> String? = { codes[it] },
        windowMillis: Long = PhraseLearningTracker.LEARN_WINDOW_MILLIS,
    ) = PhraseLearningTracker(
        codeOf = codeOf,
        systemHasWord = { it in existingWords },
        windowMillis = windowMillis,
        clock = { now },
    )

    @Test
    fun `AC3_1 两跳单字在窗口内成词并推导出 klqt`() {
        val tracker = tracker()
        assertNull("只上屏一个字不该学", tracker.onCommit("喂", CommitSource.SINGLE_CHAR))
        now = 1_900
        val decision = tracker.onCommit("猫", CommitSource.SINGLE_CHAR)

        assertNotNull(decision)
        assertEquals("klqt", decision!!.code)
        assertEquals("喂猫", decision.text)
        assertEquals("klge", decision.firstCode)
        assertEquals("qtal", decision.secondCode)
    }

    @Test
    fun `AC3_2 间隔超过 5 秒不学（边界 5000 学、5001 不学）`() {
        val atWindow = tracker()
        atWindow.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 5_000
        assertNotNull("恰好等于窗口 W 仍应学习", atWindow.onCommit("猫", CommitSource.SINGLE_CHAR))

        now = 0
        val beyond = tracker()
        beyond.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 5_001
        assertNull("超过窗口 1 ms 即不学", beyond.onCommit("猫", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.WINDOW, beyond.lastSkip?.reason)
    }

    @Test
    fun `AC3_3 中间出现标点空格英文或语音都不学`() {
        for (source in listOf(
            CommitSource.DIRECT,
            CommitSource.WORD_CANDIDATE,
            CommitSource.PANEL,
            CommitSource.ASSOCIATION,
        )) {
            now = 0
            val tracker = tracker()
            tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
            now = 1_000
            assertNull("来源 $source 应中断", tracker.onCommit("，", source))
            now = 1_900
            assertNull("中断后再上屏第二字也不该学", tracker.onCommit("猫", CommitSource.SINGLE_CHAR))
        }
    }

    @Test
    fun `AC3_4 面板开合与换输入框后不学`() {
        now = 0
        val byPanel = tracker()
        byPanel.onCommit("喂", CommitSource.SINGLE_CHAR)
        byPanel.onInterrupt()
        now = 1_900
        assertNull("切面板后第二字不该成词", byPanel.onCommit("猫", CommitSource.SINGLE_CHAR))

        now = 0
        val byReset = tracker()
        byReset.onCommit("喂", CommitSource.SINGLE_CHAR)
        byReset.reset()
        now = 1_900
        assertNull("换输入框后第二字不该成词", byReset.onCommit("猫", CommitSource.SINGLE_CHAR))
    }

    @Test
    fun `AC3_4 中断后再拼一次仍能正常学习（中断只清上一跳）`() {
        now = 0
        val tracker = tracker()
        tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        tracker.onInterrupt()
        now = 1_000
        tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 2_000

        val decision = tracker.onCommit("猫", CommitSource.SINGLE_CHAR)
        assertEquals("klqt", decision?.code)
    }

    @Test
    fun `AC3_5 系统词库已有該词则跳过且原因是 existing`() {
        now = 0
        val tracker = tracker(existingWords = setOf("加班"))
        tracker.onCommit("加", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("班", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.EXISTING, tracker.lastSkip?.reason)
    }

    @Test
    fun `AC3_9 同字连打跳过且原因是 repeat`() {
        now = 0
        val tracker = tracker()
        tracker.onCommit("你", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("你", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.REPEAT, tracker.lastSkip?.reason)
    }

    @Test
    fun `叠字词已在系统词库时原因是 existing 而不是 repeat`() {
        now = 0
        val tracker = tracker(existingWords = setOf("嘿嘿"))
        tracker.onCommit("嘿", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("嘿", CommitSource.SINGLE_CHAR))
        assertEquals("已有不学优先于同字不学", PhraseSkipReason.EXISTING, tracker.lastSkip?.reason)
    }

    @Test
    fun `非汉字首字推导不出（T恤 类）`() {
        now = 0
        val tracker = tracker()
        tracker.onCommit("T", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("恤", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.UNDECIDABLE, tracker.lastSkip?.reason)
    }

    @Test
    fun `单字不在词库时推导不出`() {
        now = 0
        val tracker = tracker()
        tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("𠀀", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.UNDECIDABLE, tracker.lastSkip?.reason)
    }

    @Test
    fun `全码只有 1 位时放弃，绝不用短码凑（产 类错误）`() {
        now = 0
        val tracker = tracker(codeOf = { if (it == "产") "u" else codes[it] })
        tracker.onCommit("产", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("喂", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.UNDECIDABLE, tracker.lastSkip?.reason)
    }

    @Test
    fun `时间戳回退时不学`() {
        now = 10_000
        val tracker = tracker()
        tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 9_000

        assertNull(tracker.onCommit("猫", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.CLOCK_BACKWARDS, tracker.lastSkip?.reason)
    }

    @Test
    fun `非单码点文本不算单字来源`() {
        now = 0
        val tracker = tracker()
        tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 1_000

        assertNull(tracker.onCommit("喂猫", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.INTERRUPT, tracker.lastSkip?.reason)
    }

    @Test
    fun `时间窗是可调参数，不是写死的常量`() {
        now = 0
        val narrow = tracker(windowMillis = 1_000)
        narrow.onCommit("喂", CommitSource.SINGLE_CHAR)
        now = 1_500

        assertNull("窗口改成 1 s 后 1.5 s 应超时", narrow.onCommit("猫", CommitSource.SINGLE_CHAR))
        assertEquals(PhraseSkipReason.WINDOW, narrow.lastSkip?.reason)
        assertEquals("默认窗口 W = 5 s", 5_000L, PhraseLearningTracker.LEARN_WINDOW_MILLIS)
    }

    @Test
    fun `码点长度为 1 的非 BMP 单字可按单字参与`() {
        assertEquals(1, codePointLength("𧝣"))
        now = 0
        val tracker = tracker(codeOf = { char ->
            when (char) {
                "𧝣" -> "aaae"
                "喂" -> "klge"
                else -> null
            }
        })
        tracker.onCommit("𧝣", CommitSource.SINGLE_CHAR)
        now = 1_000

        val decision = tracker.onCommit("喂", CommitSource.SINGLE_CHAR)
        assertEquals("aakl", decision?.code)
        assertEquals("𧝣喂", decision?.text)
    }

    @Test
    fun `lastSkip 在学习成功或首次上屏时为 null`() {
        now = 0
        val tracker = tracker()
        assertNull(tracker.onCommit("喂", CommitSource.SINGLE_CHAR))
        assertNull("首次上屏不是跳过", tracker.lastSkip)
        now = 2_000
        assertNotNull(tracker.onCommit("猫", CommitSource.SINGLE_CHAR))
        assertNull("学习成功不该留下跳过原因", tracker.lastSkip)
    }

    @Test
    fun `空串与非单码点输入不抛异常`() {
        now = 0
        val tracker = tracker()
        assertNull(tracker.onCommit("", CommitSource.SINGLE_CHAR))
        assertNull(tracker.onCommit("", CommitSource.DIRECT))
        assertTrue(codePointLength("") == 0)
    }
}