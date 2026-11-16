package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASR 埋点行格式回归测试（VOCA-231）。
 *
 * 只覆盖纯函数 [AsrTrace.line] / [AsrTrace.newSessionId]，不触碰 `android.util.Log`
 * ——单测里没有 Android 运行时，一旦让 `Log` 参与就会变成「测不出真实行为」的假绿。
 */
class AsrTraceTest {

    @Test
    fun `固定列顺序：会话 id、事件、时间戳在最前，便于按列统计`() {
        val line = AsrTrace.line("v7", AsrTrace.EV_START, 1234L, listOf("engine" to "SYSTEM", "chars" to 3))
        assertEquals("session=v7 ev=start t=1234 engine=SYSTEM chars=3", line)
    }

    @Test
    fun `会话 id 缺失记占位符而不是 null 字样`() {
        val line = AsrTrace.line(null, AsrTrace.EV_TIMEOUT, 9L, emptyList())
        assertEquals("session=- ev=timeout t=9", line)
    }

    @Test
    fun `值里的空白压成分隔符，保证 key=value 可被逐列解析`() {
        val line = AsrTrace.line(
            "v1",
            AsrTrace.EV_ERROR,
            0L,
            listOf("reason" to "audio read failed", "component" to null, "ms" to 42),
        )
        assertEquals("session=v1 ev=error t=0 reason=audio_read_failed component=- ms=42", line)
    }

    @Test
    fun `连续会话 id 递增，可区分同一次开机里的多次语音输入`() {
        val first = AsrTrace.newSessionId()
        val second = AsrTrace.newSessionId()
        assertTrue("会话 id 应带 v 前缀：$first", first.startsWith("v"))
        assertNotEquals(first, second)
        assertTrue(
            "后一个 id 的序号应更大：$first -> $second",
            second.drop(1).toLong() > first.drop(1).toLong(),
        )
    }
}
