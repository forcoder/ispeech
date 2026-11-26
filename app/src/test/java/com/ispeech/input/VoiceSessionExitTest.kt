package com.ispeech.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音会话出口判据回归测试（VOCA-231）。
 *
 * 守的是架构复审 §一 那条真行为回归：点候选条上的语音候选上屏，走的是所有候选上屏的必经路
 * `commit()`，而 `commit()` 旧实现只清 `voiceActive`、不停识别器、不摘兜底表。
 * 于是**一次已经上屏成功的会话**，66 秒后仍会被 [IspeechImeService] 的 UI 兜底判成
 * 「还卡在识别中」→ 清空当时的候选、把候选条文案覆盖成「说话时间过长」。
 *
 * 这里测判据本身：三个状态漏掉任何一个都算会话没结束。
 * 「每条出口都走了单一出口」由 `tools/verify/check_crosscut.py` 8b.3 静态守住。
 */
class VoiceSessionExitTest {

    @Test
    fun `只清 voiceActive 不停识别器，仍算会话进行中——点选上屏漏的就是这一条`() {
        // 旧 commit() 走完之后的状态：UI 标志清了，识别器还在收音
        assertTrue(voiceSessionOccupied(voiceActive = false, systemListening = true, voskListening = false))
        assertTrue(voiceSessionOccupied(voiceActive = false, systemListening = false, voskListening = true))
    }

    @Test
    fun `识别器已经停了但 UI 还占着候选条，也算进行中`() {
        assertTrue(voiceSessionOccupied(voiceActive = true, systemListening = false, voskListening = false))
    }

    @Test
    fun `单一出口走完（三个状态全清）才算结束，兜底表不再打进已经上屏的会话`() {
        assertFalse(voiceSessionOccupied(voiceActive = false, systemListening = false, voskListening = false))
    }
}
