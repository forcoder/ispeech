package com.ispeech.engine

/**
 * 快捷短语 / 快速应用规则条目。
 */
data class QuickEntry(
    val trigger: String,
    val label: String,
    val content: String,
    val weight: Int,
    val mode: QuickMatchMode,
    val kind: LookupKind,
    /** 规则来源分组标签（内置 / 远端 / 用户），用于快捷短语面板分类筛选。 */
    val source: String = QuickSource.BUILT_IN
)

/** 快捷规则来源分组：内置 assets、csBaby 远端同步、用户自定义。 */
object QuickSource {
    const val BUILT_IN = "内置"
    const val REMOTE = "远端"
    const val USER = "用户"

    /** 面板分类筛选用到的全部来源，顺序即展示顺序。 */
    val all: List<String> = listOf(BUILT_IN, REMOTE, USER)
}
