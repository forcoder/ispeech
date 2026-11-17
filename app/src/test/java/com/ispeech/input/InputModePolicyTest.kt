package com.ispeech.input

import com.ispeech.settings.store.ChineseInputMethod
import com.ispeech.settings.store.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「中/英」切换回归测试：只在中文态与英文态之间切换，回到中文时使用设置的默认中文输入法。
 *
 * 修复前的三态循环（五笔 → 拼音 → 英文 → 五笔）会让默认五笔的用户按一次「中/英」
 * 先落到拼音，正是 VOCA-29 报告的问题。
 */
class InputModePolicyTest {

    @Test
    fun `默认五笔时 中英切换只在五笔与英文之间`() {
        assertEquals(
            InputMode.ENGLISH,
            InputModePolicy.toggle(InputMode.WUBI, ChineseInputMethod.WUBI),
        )
        assertEquals(
            InputMode.WUBI,
            InputModePolicy.toggle(InputMode.ENGLISH, ChineseInputMethod.WUBI),
        )
    }

    @Test
    fun `默认拼音时 中英切换只在拼音与英文之间`() {
        assertEquals(
            InputMode.ENGLISH,
            InputModePolicy.toggle(InputMode.PINYIN, ChineseInputMethod.PINYIN),
        )
        assertEquals(
            InputMode.PINYIN,
            InputModePolicy.toggle(InputMode.ENGLISH, ChineseInputMethod.PINYIN),
        )
    }

    @Test
    fun `从英文回到中文时以设置为准 而不是切换前的输入法`() {
        assertEquals(
            InputMode.WUBI,
            InputModePolicy.toggle(InputMode.ENGLISH, ChineseInputMethod.WUBI),
        )
        assertEquals(
            InputMode.PINYIN,
            InputModePolicy.toggle(InputMode.ENGLISH, ChineseInputMethod.PINYIN),
        )
    }

    @Test
    fun `设置项到中文输入态的映射`() {
        assertEquals(InputMode.PINYIN, InputModePolicy.chineseMode(ChineseInputMethod.PINYIN))
        assertEquals(InputMode.WUBI, InputModePolicy.chineseMode(ChineseInputMethod.WUBI))
    }

    @Test
    fun `旧版本持久化值可解析 未知值回落拼音`() {
        assertEquals(ChineseInputMethod.WUBI, ChineseInputMethod.parse("WUBI"))
        assertEquals(ChineseInputMethod.PINYIN, ChineseInputMethod.parse("PINYIN"))
        assertEquals(ChineseInputMethod.PINYIN, ChineseInputMethod.parse(null))
        assertEquals(ChineseInputMethod.PINYIN, ChineseInputMethod.parse("UNKNOWN"))
    }

    /**
     * VOCA-465 D1：中英切换落到的输入态，就是硬件键入口判「空格归谁」时读的那个 `mode`。
     *
     * 规格 AC-2 的 Given 是「中文输入态（拼音或五笔任一）」—— 两态都要接管，英文态一律放行。
     */
    @Test
    fun `中英切换后的输入态与硬件键空格接管口径一致`() {
        val enabled = KeyboardSettings(doubleSpacePeriod = true)
        for (method in listOf(ChineseInputMethod.WUBI, ChineseInputMethod.PINYIN)) {
            val chinese = InputModePolicy.toggle(InputMode.ENGLISH, method)
            assertNotEquals(InputMode.ENGLISH, chinese)
            assertTrue("中文态（$method）硬件键空格要进判定链", takesSpaceIn(chinese, enabled))

            val english = InputModePolicy.toggle(chinese, method)
            assertEquals(InputMode.ENGLISH, english)
            assertFalse("英文态空格交回宿主（R6）", takesSpaceIn(english, enabled))
        }
    }

    @Test
    fun `开关关闭时任何输入态都不接管硬件键空格`() {
        val off = KeyboardSettings()
        for (mode in InputMode.entries) {
            assertFalse(takesSpaceIn(mode, off))
        }
    }

    private fun takesSpaceIn(mode: InputMode, settings: KeyboardSettings): Boolean =
        SpaceKeyPolicy.takesHardwareSpace(
            state = SpaceKeyPolicy.State(englishMode = mode == InputMode.ENGLISH),
            settings = settings,
        )
}