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
 * 远端规则的「快捷字母覆盖」判定与同 trigger 的所有权裁决。
 *
 * 远端文件是同步拉取产物，用户设置的快捷字母不能写回去；约定用户文件里
 * **归一化 trigger 相同**的条目就是该规则的覆盖条目（ADR-0002 C2-1 / C2-2）。
 * 覆盖条目仍按普通用户规则写盘 / 生效，但不应再被当成新规则推送到 csBaby。
 *
 * 为什么不用 `label + content`：写入侧（`upsert`）、解析侧（`QuickPhraseCodec.parse`）、
 * 删除侧（`remove`）全都把 trigger 当主键，只有覆盖判定用 label+content，
 * 于是「正文过期但简写相同」的本地条目不被认成覆盖，既在列表里重复出现，
 * 又被当成新规则推回云端覆盖掉正确正文。
 */
object QuickOverride {

    /** 用户条目 [user] 是否是远端规则 [remote] 的快捷字母覆盖（只看归一化 trigger）。 */
    fun matches(user: QuickEntry, remote: QuickEntry): Boolean {
        val key = QuickTrigger.normalize(user.trigger)
        return key.isNotEmpty() && key == QuickTrigger.normalize(remote.trigger)
    }

    /** 用户条目 [user] 是否覆盖了 [remote] 中的任意一条规则。 */
    fun matchesAny(user: QuickEntry, remote: List<QuickEntry>): Boolean =
        remote.any { matches(user, it) }
}

/** 一组同来源的快捷规则（内置的两个 assets 文件各算一组，保留文件顺序）。 */
data class QuickRuleGroup(val source: String, val entries: List<QuickEntry>)

/**
 * 同 trigger 跨来源去重（ADR-0002 C2-4）：用户 > 远端 > 内置，同来源内先出现者胜。
 *
 * 取代原先「权重 +1000 + `CompositeEngine` 按 display 去重」的机制：覆盖条目的 display 是中文名、
 * 远端条目的 display 是 keyword，两者不同 → 根本不去重，两条候选并存，谁在首位取决于权重与
 * display 字典序，用户和测试都无法预测。去重后候选池里同一个 trigger 只有一条，正确性不再依赖排序。
 */
object QuickOwnership {

    /** 来源优先级：数字越小越权威。 */
    private val rankBySource = mapOf(
        QuickSource.USER to 0,
        QuickSource.REMOTE to 1,
        QuickSource.BUILT_IN to 2,
    )

    /** 每个归一化 trigger 的归属组下标（按优先级顺序先到先得）。 */
    private fun ownerIndex(groups: List<QuickRuleGroup>): Map<String, Int> {
        val ordered = groups.indices.sortedBy { rankBySource[groups[it].source] ?: Int.MAX_VALUE }
        val owner = LinkedHashMap<String, Int>()
        for (index in ordered) {
            for (entry in groups[index].entries) {
                val key = QuickTrigger.normalize(entry.trigger)
                if (key.isEmpty()) continue
                owner.putIfAbsent(key, index)
            }
        }
        return owner
    }

    /** 去掉被更高优先级来源占用的同 trigger 条目，组的顺序与组内顺序保持不变。 */
    fun dedupe(groups: List<QuickRuleGroup>): List<QuickRuleGroup> {
        if (groups.size < 2) return groups
        val owner = ownerIndex(groups)
        return groups.mapIndexed { index, group ->
            val kept = group.entries.filter { entry ->
                val key = QuickTrigger.normalize(entry.trigger)
                key.isEmpty() || owner[key] == index
            }
            group.copy(entries = kept)
        }
    }
}
