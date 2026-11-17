package com.ispeech.input

import com.ispeech.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入时限契约（VOCA-231）。
 *
 * 缺陷本体是「语音识别中…约 75 秒不复位」：当时全链路只有「松手后等收尾」一处超时，
 * 抬起事件没回来 / 服务不回话 / 离线引擎卡在读音频三种情况都没有时限。
 * 这里锁住修好后的三条不变量，任何一条被改回去都会在单测里红：
 *
 * 1. 每条时限都是有限正数（不存在「永远等下去」的阶段）；
 * 2. 阶段之间严格递增，且 UI 侧最后一道比引擎侧晚一个收尾窗口
 *    （否则引擎「自动收尾让已说的话落地」这条路会被 UI 抢先掐断）；
 * 3. 每个超时阶段都有**各自**的可读文案，不落到笼统的「识别失败」。
 */
class AsrTimingTest {

    @Test
    fun `每个阶段都有有限的时限，不存在会永远等的阶段`() {
        val deadlines = listOf(
            AsrTiming.FIRST_CALLBACK_TIMEOUT_MS,
            AsrTiming.FINISH_TIMEOUT_MS,
            AsrTiming.SESSION_TIMEOUT_MS,
            AsrTiming.UI_SESSION_TIMEOUT_MS,
            AsrTiming.NO_AUDIO_TIMEOUT_MS,
        )
        deadlines.forEach {
            assertTrue("时限必须是正数毫秒，实际 $it", it > 0L)
            assertTrue("单次语音输入不该超过 5 分钟，实际 $it", it <= 300_000L)
        }
    }

    @Test
    fun `收尾早于首个回调兜底，首个回调早于会话上限`() {
        assertTrue(
            "收尾窗口应最短（用户已经说完，在等结果）",
            AsrTiming.FINISH_TIMEOUT_MS < AsrTiming.FIRST_CALLBACK_TIMEOUT_MS,
        )
        assertTrue(
            "会话上限必须明显大于单个阶段兜底，否则会掐掉正常长句",
            AsrTiming.FIRST_CALLBACK_TIMEOUT_MS < AsrTiming.SESSION_TIMEOUT_MS,
        )
    }

    @Test
    fun `UI 兜底比引擎会话上限晚一个收尾窗口，让自动收尾先落地`() {
        assertEquals(
            "引擎到点会自动收尾（已说的话还可能出字），UI 必须等它走完再掐",
            AsrTiming.SESSION_TIMEOUT_MS + AsrTiming.FINISH_TIMEOUT_MS,
            AsrTiming.UI_SESSION_TIMEOUT_MS,
        )
    }

    @Test
    fun `四个超时阶段各有各的文案，不复用笼统的识别失败`() {
        val stages = listOf(
            AsrTiming.STAGE_START,
            AsrTiming.STAGE_FINISH,
            AsrTiming.STAGE_SESSION,
        )
        val ids = stages.map { AsrTiming.messageFor(it) }
        assertEquals("阶段数与文案数应一一对应", stages.size, ids.distinct().size)
        stages.forEachIndexed { i, stage ->
            assertNotEquals("$stage 落到了兜底文案", R.string.voice_error, ids[i])
        }
        // 录音不可用走既有的麦克风文案，不再新造一条同义句
        assertEquals(R.string.voice_audio_error, AsrTiming.messageFor(AsrTiming.STAGE_AUDIO))
    }

    @Test
    fun `未知阶段回落通用失败文案而不是空提示`() {
        assertEquals(R.string.voice_error, AsrTiming.messageFor("no-such-stage"))
    }
}
