package com.ispeech.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-5a.3 的判定层：一步填入只允许在「输入框仍是生成前原文」时替换，
 * 用户改过内容就必须只插入、不删除。
 *
 * 这里覆盖的是 [AiSourceGuard] 的纯逻辑；真实 `getTextBeforeCursor` 时序仍要真机验，
 * 单测不替代那一步（见 VOCA-628 交付评论）。
 */
class AiSourceGuardTest {

    @Test
    fun `原文未变时替换`() {
        assertTrue(AiSourceGuard.shouldReplace("今天天气不错", "今天天气不错", 6))
    }

    @Test
    fun `用户改了内容时不替换`() {
        // AC-5a.3 的正例：生成时为「今天天气不错」，用户改成「今天天气很好」再点 AI 结果
        assertFalse(AiSourceGuard.shouldReplace("今天天气很好", "今天天气不错", 6))
    }

    @Test
    fun `用户追加了内容时不替换`() {
        // 光标前检索到的仍是原文长度的一段，但用户后面又打了字：这里 beforeCursor 取到的是
        // 光标前 sourceLen 个字，若与原文字面不同就不能删。
        assertFalse(AiSourceGuard.shouldReplace("今天天气不好", "今天天气不错", 6))
    }

    @Test
    fun `取不到光标前文本时不替换`() {
        assertFalse(AiSourceGuard.shouldReplace(null, "原文", 2))
        assertFalse(AiSourceGuard.shouldReplace("", "原文", 2))
    }

    @Test
    fun `没有原文时不替换`() {
        // 输入框本来是空的（没有可替换的原文），直接插入即可
        assertFalse(AiSourceGuard.shouldReplace("任意", "", 0))
        assertFalse(AiSourceGuard.shouldReplace("", "", 0))
    }

    @Test
    fun `单字原文也要严格相等`() {
        assertTrue(AiSourceGuard.shouldReplace("好", "好", 1))
        assertFalse(AiSourceGuard.shouldReplace("坏", "好", 1))
    }

    @Test
    fun `长度与原文一致但内容不同不替换`() {
        assertFalse(AiSourceGuard.shouldReplace("123456", "abcdef", 6))
    }
}