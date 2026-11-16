package com.ispeech.input

import com.ispeech.settings.store.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 空格键判定回归测试（VOCA-45 §2.2 / AC-2 / AC-3）。
 *
 * 本仓库单测是 JVM-only（无 Robolectric、无 `androidTest`），拿不到「宿主文本域内容」，
 * 所以判的是与服务侧完全相同的那个决策：上屏链条优先级 + 双击时间窗 + 状态前提。
 * 「光标前那个字符是不是空格」由服务侧读一次喂进 State，这里判的是「没确认就不许删」这半边。
 * 真机侧「文本域 == 。/ 两个空格」仍由 adb 用例覆盖（AC-2 / AC-3 原文）；
 * `commit()` / `commitAiReply()` 上屏后把时间戳清零（R3）在 JVM 里测不到，
 * 由 `tools/verify/check_crosscut.py` 的静态门禁钉住每个 commitText 落点。
 */
class SpaceKeyPolicyTest {

    private val enabled = KeyboardSettings(doubleSpacePeriod = true)

    /** 本次按键的时刻：用正值，避免与「时间戳 0」这个合法值混淆。 */
    private val now = 1_000_000L

    /**
     * @param spaceAgoMs 距上一次「空格上屏」过了多少毫秒；null = 没有记录（本次是第一次）。
     * @param precedingIsSpace 光标前那个字符确认是空格。默认 true = 双击的常规局面（刚上屏的空格还在原地），
     *                         只有专门测「未确认就不许删」的用例才把它关掉。
     */
    private fun judge(
        settings: KeyboardSettings = enabled,
        spaceAgoMs: Long? = null,
        t9Pending: Boolean = false,
        composing: Boolean = false,
        aiReplyPending: Boolean = false,
        voicePending: Boolean = false,
        associationPending: Boolean = false,
        englishMode: Boolean = false,
        accessibilityEnabled: Boolean = false,
        precedingIsSpace: Boolean = true,
    ): SpaceKeyPolicy.Action = SpaceKeyPolicy.decide(
        state = SpaceKeyPolicy.State(
            t9Pending = t9Pending,
            composing = composing,
            aiReplyPending = aiReplyPending,
            voicePending = voicePending,
            associationPending = associationPending,
            englishMode = englishMode,
            accessibilityEnabled = accessibilityEnabled,
            precedingIsSpace = precedingIsSpace,
            lastSpaceCommitAt = spaceAgoMs?.let { now - it },
        ),
        settings = settings,
        now = now,
    )

    // ── AC-2：开关开启 → 窗口内第二次空格出句号；第一次仍是普通空格 ──

    @Test
    fun `开关开启 窗口内的第二次空格出句号`() {
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge())
        assertEquals(SpaceKeyPolicy.Action.DOUBLE_SPACE_PERIOD, judge(spaceAgoMs = 150L))
        // 恰好压在 300 ms 边界上仍算双击
        assertEquals(SpaceKeyPolicy.Action.DOUBLE_SPACE_PERIOD, judge(spaceAgoMs = 300L))
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(spaceAgoMs = 301L))
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(spaceAgoMs = 5_000L))
    }

    @Test
    fun `开关关闭 连按两次都只出空格`() {
        val off = KeyboardSettings()
        assertFalse("双击空格出句号必须默认关闭（§2.2 默认值）", off.doubleSpacePeriod)
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(settings = off, spaceAgoMs = 50L))
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(settings = off, spaceAgoMs = 0L))
    }

    // ── §5：无障碍模式下窗口放宽到 600 ms ──

    @Test
    fun `无障碍模式下判定窗口放宽到 600ms`() {
        assertEquals(300L, SpaceKeyPolicy.windowMs(accessibilityEnabled = false))
        assertEquals(600L, SpaceKeyPolicy.windowMs(accessibilityEnabled = true))
        // 450 ms：普通模式不相干，无障碍模式算双击
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(spaceAgoMs = 450L))
        assertEquals(
            SpaceKeyPolicy.Action.DOUBLE_SPACE_PERIOD,
            judge(spaceAgoMs = 450L, accessibilityEnabled = true),
        )
        assertEquals(
            SpaceKeyPolicy.Action.SPACE,
            judge(spaceAgoMs = 601L, accessibilityEnabled = true),
        )
    }

    // ── AC-3：有候选时永远先上屏首选，双击不参与 ──

    @Test
    fun `组合中有候选时第一次空格上屏首选 不出句号`() {
        assertEquals(SpaceKeyPolicy.Action.COMMIT_TOP, judge(composing = true))
        assertEquals(SpaceKeyPolicy.Action.COMMIT_TOP, judge(spaceAgoMs = 100L, composing = true))
    }

    @Test
    fun `时间窗成立但光标前不是空格 不做破坏性删除`() {
        // State 的默认值必须是「未确认」：这个动作要删字符，前提喂漏了只能少删不能多删
        assertFalse(SpaceKeyPolicy.State().precedingIsSpace)
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(spaceAgoMs = 50L, precedingIsSpace = false))
        // 无障碍态同理：窗口再宽，目标字符没确认也不转
        assertEquals(
            SpaceKeyPolicy.Action.SPACE,
            judge(spaceAgoMs = 450L, precedingIsSpace = false, accessibilityEnabled = true),
        )
        // 对照：同一时刻、同一状态，确认了光标前是空格才出句号
        assertEquals(SpaceKeyPolicy.Action.DOUBLE_SPACE_PERIOD, judge(spaceAgoMs = 50L))
    }

    @Test
    fun `链条被接管时双击一律不参与`() {
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_T9,
            judge(spaceAgoMs = 50L, t9Pending = true),
        )
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_TOP,
            judge(spaceAgoMs = 50L, composing = true),
        )
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_AI_REPLY,
            judge(spaceAgoMs = 50L, aiReplyPending = true),
        )
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_VOICE,
            judge(spaceAgoMs = 50L, voicePending = true),
        )
        assertEquals(
            SpaceKeyPolicy.Action.PICK_FIRST_CANDIDATE,
            judge(spaceAgoMs = 50L, associationPending = true),
        )
    }

    @Test
    fun `链条优先级按规格顺序 前面的状态压住后面的`() {
        val all = SpaceKeyPolicy.State(
            t9Pending = true,
            composing = true,
            aiReplyPending = true,
            voicePending = true,
            associationPending = true,
            lastSpaceCommitAt = now - 50L,
        )
        assertEquals(SpaceKeyPolicy.Action.COMMIT_T9, SpaceKeyPolicy.decide(all, enabled, now))
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_TOP,
            SpaceKeyPolicy.decide(all.copy(t9Pending = false), enabled, now),
        )
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_AI_REPLY,
            SpaceKeyPolicy.decide(all.copy(t9Pending = false, composing = false), enabled, now),
        )
        assertEquals(
            SpaceKeyPolicy.Action.COMMIT_VOICE,
            SpaceKeyPolicy.decide(
                all.copy(t9Pending = false, composing = false, aiReplyPending = false),
                enabled,
                now,
            ),
        )
        assertEquals(
            SpaceKeyPolicy.Action.PICK_FIRST_CANDIDATE,
            SpaceKeyPolicy.decide(
                all.copy(
                    t9Pending = false,
                    composing = false,
                    aiReplyPending = false,
                    voicePending = false,
                ),
                enabled,
                now,
            ),
        )
    }

    // ── R6：中文标点关闭 / 英文态不转换 ──

    @Test
    fun `中文标点关闭时不转换句号`() {
        val noPunct = KeyboardSettings(doubleSpacePeriod = true, chinesePunctuation = false)
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(settings = noPunct, spaceAgoMs = 50L))
    }

    @Test
    fun `英文态连按两次空格出两个空格`() {
        assertEquals(SpaceKeyPolicy.Action.SPACE, judge(spaceAgoMs = 50L, englishMode = true))
    }
}
