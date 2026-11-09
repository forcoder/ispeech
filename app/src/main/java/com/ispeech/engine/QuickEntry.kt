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

/**
 * 远端规则的「快捷字母覆盖」判定。
 *
 * 远端文件是同步拉取产物，用户设置的快捷字母不能写回去；约定用户文件里
 * `label + content` 与某条远端规则相同的条目就是该规则的覆盖条目。覆盖条目仍按普通
 * 用户规则写盘 / 生效，但不应再被当成新规则推送到 csBaby，否则云端会出现同内容的重复规则。
 */
object QuickOverride {

    /** 用户条目 [user] 是否是远端规则 [remote] 的快捷字母覆盖。 */
    fun matches(user: QuickEntry, remote: QuickEntry): Boolean =
        user.label == remote.label && user.content == remote.content

    /** 用户条目 [user] 是否覆盖了 [remote] 中的任意一条规则。 */
    fun matchesAny(user: QuickEntry, remote: List<QuickEntry>): Boolean =
        remote.any { matches(user, it) }
}
