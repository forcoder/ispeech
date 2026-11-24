package com.ispeech.engine

/**
 * 联想词上屏文本。
 *
 * 联想候选是按「已上屏文本的末字」查出来的（见 [LookupEngine.related]）：词条开头往往就是
 * 刚上屏的那个字，整词上屏就会把它再写一遍（「你」+「你好」→「你你好」，用户看到的就是
 * 「第一个字重复了」）。上屏多字候选时更明显：「指导老师」+「师专」→「指导老师师专」。
 *
 * 规则：候选开头与已上屏文本结尾的最长重合部分视为已写过，只上屏剩下的部分；
 * 完全重合则不写；没有重合（用户词库的「包含」联想等）保持整词上屏。
 *
 * 纯函数，便于单测；不依赖 Android。
 */
internal object AssociationCommit {

    /** 锚点最多回看多少字：联想词很短，回看太多只会误伤更早的正文 */
    private const val ANCHOR_WINDOW = 16

    /**
     * @param lastCommit 最近一次上屏的文本
     * @param candidate 联想候选的 insertText
     * @param textBeforeCursor 光标前的实际文本，仅在 [lastCommit] 为空时兜底
     *   （宿主重启输入连接会清掉服务侧状态，此时仍以输入框里的真实文字为准）
     * @return 实际需要上屏的文本；空串表示候选与已上屏内容重合、无需再写
     */
    fun textToCommit(
        lastCommit: String,
        candidate: String,
        textBeforeCursor: String = "",
    ): String {
        if (candidate.isEmpty()) return candidate
        val anchor = if (lastCommit.isNotEmpty()) lastCommit else textBeforeCursor
        if (anchor.isEmpty()) return candidate
        if (candidate == anchor) return ""
        val overlap = suffixPrefixOverlap(anchor, candidate)
        return if (overlap == 0) candidate else candidate.substring(overlap)
    }

    /**
     * [lastCommit] 的后缀与 [candidate] 前缀的最长重合长度。
     *
     * 「你」+「你好」→ 1；「天气」+「天气预报」→ 2；「指导老师」+「师专」→ 1（师）。
     */
    private fun suffixPrefixOverlap(lastCommit: String, candidate: String): Int {
        val window = lastCommit.takeLast(minOf(lastCommit.length, ANCHOR_WINDOW))
        for (length in minOf(window.length, candidate.length) downTo 1) {
            if (candidate.regionMatches(0, window, window.length - length, length)) return length
        }
        return 0
    }
}
