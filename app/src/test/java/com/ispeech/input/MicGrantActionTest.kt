package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选条麦克风授权按钮的状态机回归测试（VOCA-166）。
 *
 * 覆盖的是「什么时候出按钮、出哪一个」这条判定：修复前语音键一按就 `startActivity` 抢前台，
 * 整个输入法随宿主退后台，用户还没说话键盘就没了；现在改由这一判定决定候选条上的动作。
 */
class MicGrantActionTest {

    @Test
    fun `授权提示挂着且未授予时 出「去授权」`() {
        assertEquals(
            MicGrantAction.REQUEST,
            MicGrantAction.resolve(PROMPT, PROMPT, granted = false, permanentlyDenied = false),
        )
    }

    @Test
    fun `永久拒绝后 按钮换成「去设置」而不是继续弹没反应的授权框`() {
        assertEquals(
            MicGrantAction.OPEN_SETTINGS,
            MicGrantAction.resolve(PROMPT, PROMPT, granted = false, permanentlyDenied = true),
        )
    }

    @Test
    fun `用户继续打字时 提示已被组合码顶掉 按钮跟着消失`() {
        // 常态第 1 行是用户正在打的码：不能把它读成授权态，否则打字途中会挂出一个动作按钮
        assertEquals(
            MicGrantAction.NONE,
            MicGrantAction.resolve(null, "zhong", granted = false, permanentlyDenied = false),
        )
        assertEquals(
            MicGrantAction.NONE,
            MicGrantAction.resolve(PROMPT, "zhong", granted = false, permanentlyDenied = false),
        )
    }

    @Test
    fun `权限实时已授予时不出按钮 永久拒绝标志只是提示不是门禁`() {
        assertEquals(
            MicGrantAction.NONE,
            MicGrantAction.resolve(PROMPT, PROMPT, granted = true, permanentlyDenied = false),
        )
        // 用户在系统设置里手工开好、标志还没被清掉：按钮必须消失，不能给一个点了没反应的动作
        assertEquals(
            MicGrantAction.NONE,
            MicGrantAction.resolve(PROMPT, PROMPT, granted = true, permanentlyDenied = true),
        )
    }

    @Test
    fun `别的通知顶掉提示行时 按钮不残留`() {
        assertEquals(
            MicGrantAction.NONE,
            MicGrantAction.resolve(PROMPT, VOICE_NO_MATCH, granted = false, permanentlyDenied = false),
        )
    }

    private companion object {
        const val PROMPT = "请先授予麦克风权限"
        const val VOICE_NO_MATCH = "没有识别到内容"
    }
}
