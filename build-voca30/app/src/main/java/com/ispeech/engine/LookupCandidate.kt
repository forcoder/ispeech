package com.ispeech.engine

/**
 * 候选来源：五笔、快捷短语、快速应用；label 用于候选面板的来源角标。
 */
enum class LookupKind(val label: String) {
    WUBI("五笔"),
    WUBIPHRASE("词"),
    PHRASE("短语"),
    QUICKAPPLY("应用"),
    PINYIN("拼");

    /**
     * 全局排序优先级：应用 < 短语 < 词库单字 < 词库词组，数值越小越靠前。
     *
     * 词库词组单独占一档，是因为 rime / 搜狗词库的词组权重普遍高于常用单字权重
     * （例如 `wqiy` 下「仰泳」57816 > 「你」22976），若与单字按权重混排，
     * `trnt` 的首选会从「我」变成「选择性」。单字优先、词组用数字键选，才是五笔的肌肉记忆。
     */
    val order: Int
        get() = when (this) {
            QUICKAPPLY -> 0
            PHRASE -> 1
            WUBI -> 2
            WUBIPHRASE -> 3
            PINYIN -> 4
        }
}

/**
 * 统一候选项：五笔、快捷短语、快速应用都走这个类型。
 * code 为触发码 / 五笔码，display 为候选面板展示文本，insertText 为真正上屏内容。
 */
data class LookupCandidate(
    val code: String,
    val display: String,
    val insertText: String,
    val weight: Int,
    val kind: LookupKind
)
