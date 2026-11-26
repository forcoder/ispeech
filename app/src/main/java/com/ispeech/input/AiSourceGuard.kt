package com.ispeech.input

/**
 * AI 回复的「替换语义」判定：生成前原文是否仍然原样留在输入框里。
 *
 * AI 键生成建议时会把输入框已有文本当作消息记下来（`aiSourceText` / `aiSourceLen`）。
 * 采纳建议时，只有当光标前 [sourceLen] 个字**逐字等于**生成时的原文，才把这段原文删掉
 * 再插入建议——即「用建议替代自己刚说的那句」。用户中途改了输入框（增删改都算），
 * 就不许删，直接插入（`docs/requirements/VOCA-65-acceptance.md` AC-5a.3）。
 *
 * 抽成纯函数是为了让这条保护可被 JVM 单测直接覆盖：面板一步填入、详情页「上屏」、
 * 空格 / 回车上屏都走同一个判定，不存在「某条路径忘了校验」的副本。
 */
internal object AiSourceGuard {

    /**
     * 是否应删除光标前的原文。
     *
     * @param beforeCursor 光标前 `sourceLen` 个字（取不到时传 null / 空串）
     * @param sourceText 生成建议时输入框里的原文
     * @param sourceLen 生成建议时原文的长度
     */
    fun shouldReplace(beforeCursor: String?, sourceText: String, sourceLen: Int): Boolean {
        if (sourceLen <= 0) return false
        if (sourceText.isEmpty()) return false
        if (beforeCursor.isNullOrEmpty()) return false
        return beforeCursor == sourceText
    }
}