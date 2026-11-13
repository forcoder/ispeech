package com.ispeech.engine

/**
 * 联想词上屏文本。
 *
 * 联想候选以「已上屏文本的末字」为线索，词条本身就以该字开头；整词上屏会把已经上屏的字
 * 再写一遍（「你」+「你好」→「你你好」，用户看到的就是「第一个字重复了」）。
 *
 * 规则：联想词以已上屏文本整体开头时，只上屏多出来的部分，让已上屏的文字接续；
 * 与已上屏文本完全相同则无需再写；其余情况（用户词库的「包含」联想、末字相同但开头不同等）
 * 保持整词上屏。
 *
 * 纯函数，便于单测；不依赖 Android。
 */
internal object AssociationCommit {

    /**
     * @param lastCommit 最近一次上屏的文本
     * @param candidate 联想候选的 insertText
     * @return 实际需要上屏的文本；空串表示候选与已上屏内容重合、无需再写
     */
    fun textToCommit(lastCommit: String, candidate: String): String {
        if (lastCommit.isEmpty() || candidate.isEmpty()) return candidate
        if (candidate == lastCommit) return ""
        if (candidate.length <= lastCommit.length) return candidate
        return if (candidate.startsWith(lastCommit)) candidate.substring(lastCommit.length) else candidate
    }
}
