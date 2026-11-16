package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自因光标过滤（AC-3.4 的 JVM 段 ③④）：裁决要求的是**表驱动**断言，判定列为
 * `(newSelStart, lastCommittedCursor, tolerance) -> 是否自因`，一条数据表覆盖
 * 「自因不误中断 / 他因必中断」两类 + ④ 的 `tolerance = 0` 降级分支。
 *
 * 判定纯逻辑放在这里而不是 [com.ispeech.input.IspeechImeService] 里，是为了让 AC-3.4 的两类行为
 * 能被 JVM 直接覆盖；真机只负责验 `onUpdateSelection` 的回调时序（见
 * `docs/voice-phrase-learning-verify.md` §1）。
 */
class SelectionSelfCausedTest {

    private data class Case(
        val newSelStart: Int,
        val lastCommittedCursor: Int,
        val tolerance: Int,
        val expectedSelfCaused: Boolean,
        val why: String,
    )

    @Test
    fun `自因判定表逐行成立`() {
        val cases = listOf(
            // ③ 自因不误中断
            Case(12, 12, 0, true, "位置与基线相同 = 本服务自己上屏造成的回调"),
            Case(12, 12, 3, true, "同一 commit 的重复回调：两次拿到同一个位置，仍是自因"),
            Case(15, 12, 3, true, "容差内前移（上屏让光标右移）"),
            Case(9, 12, 3, true, "容差内后移"),
            Case(7, -1, 0, true, "还没观测到光标基线（-1）时不判中断，否则首次正常上屏会被误判成移动光标"),

            // ③ 他因必中断
            Case(13, 12, 0, false, "前移 1 位、容差 0 = 用户动了光标"),
            Case(4, 12, 0, false, "后移 8 位、容差 0 = 用户点了输入框开头"),
            Case(16, 12, 3, false, "超出容差一位即他因"),
            Case(0, 12, 3, false, "大跨度跳到文本开头"),

            // ④ tolerance = 0 的降级列：只比 newSelStart，任何位移都是他因
            Case(15, 12, 0, false, "同一组输入在 tolerance=3 是自因、在 tolerance=0 必须是他因"),
            Case(5, 5, -2, true, "负容差按 0 处理：相等仍自因"),
            Case(6, 5, -2, false, "负容差按 0 处理：位移 1 位仍他因"),
        )

        for (c in cases) {
            assertEquals(
                "isSelfCausedSelectionChange(newSelStart=${c.newSelStart}, " +
                    "lastCommittedCursor=${c.lastCommittedCursor}, tolerance=${c.tolerance})：${c.why}",
                c.expectedSelfCaused,
                isSelfCausedSelectionChange(c.newSelStart, c.lastCommittedCursor, c.tolerance),
            )
        }
    }
}
