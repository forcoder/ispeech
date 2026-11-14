package com.ispeech.input

import com.ispeech.settings.store.ChineseInputMethod
import org.junit.Assert.assertEquals
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
}