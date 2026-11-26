package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 已启用输入法清单的交叉校验回归测试（ADR-0003 D3）。
 *
 * 包可见性裁掉的项在面板上和「系统里就只有这几个」不可区分，所以「差值」必须是纯算术、
 * 且不能被设置串里的空段或子类型算错。
 */
class ImeListVisibilityTest {

    @Test
    fun `冒号分隔的启用串按条目计数`() {
        val raw = "com.sohu.inputmethod.sogou/.SogouIME:" +
            "com.google.android.inputmethod.latin/.LatinIME:" +
            "com.ispeech.ime/.IspeechImeService:" +
            "com.google.android.tts/.VoiceInputMethodService"
        assertEquals(4, ImeListVisibility.enabledCount(raw))
    }

    @Test
    fun `子类型用分号分隔不额外计数`() {
        val raw = "com.google.android.inputmethod.latin/.LatinIME;" +
            "com.google.android.inputmethod.latin/subtype#qwerty;" +
            "com.google.android.inputmethod.latin/subtype#number_row:" +
            "com.ispeech.ime/.IspeechImeService"
        assertEquals(2, ImeListVisibility.enabledCount(raw))
    }

    @Test
    fun `空段与空串不算条目`() {
        assertEquals(0, ImeListVisibility.enabledCount(null))
        assertEquals(0, ImeListVisibility.enabledCount(""))
        assertEquals(1, ImeListVisibility.enabledCount("com.ispeech.ime/.IspeechImeService:"))
        assertEquals(2, ImeListVisibility.enabledCount("a/.A::b/.B"))
    }

    @Test
    fun `可见清单少于启用条数时差值为被裁条数`() {
        assertEquals(2, ImeListVisibility.hiddenCount(enabledCount = 4, visibleCount = 2))
        assertEquals(0, ImeListVisibility.hiddenCount(enabledCount = 4, visibleCount = 4))
    }

    @Test
    fun `读不到启用条数时不误报未显示`() {
        assertEquals(0, ImeListVisibility.hiddenCount(enabledCount = 0, visibleCount = 4))
        assertEquals(0, ImeListVisibility.hiddenCount(enabledCount = 1, visibleCount = 1))
    }
}
