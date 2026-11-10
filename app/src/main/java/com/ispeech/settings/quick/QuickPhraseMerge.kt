package com.ispeech.settings.quick

import com.ispeech.data.PhraseUsageStore
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickSource

/**
 * 用户规则与远端规则的展示合并（从 [QuickPhrasesViewModel.mergeForDisplay] 原样抽出，便于单测）。
 *
 * 远端文件是同步拉取产物，用户设置的快捷字母不能写回去；约定用户文件里
 * `label + content` 与某条远端规则相同的条目就是该规则的「快捷字母覆盖」
 * （见 `QuickOverride`）。合并时覆盖条目折叠到远端卡片上，不再单独展示。
 */
internal object QuickPhraseMerge {

    /** 合并结果：展示条目 + 远端信息（按展示 id）+ 应跳过云端推送的用户 trigger。 */
    data class Result(
        val entries: List<QuickEntry>,
        val infoById: Map<String, RemoteRuleInfo>,
        /** 覆盖条目的 trigger：它们是本地别名，推送会在云端产生同内容重复规则。 */
        val skipPushTriggers: Set<String>,
        /** 展示 id -> 覆盖前远端条目的 id，用于把原远端规则的使用次数 / 收藏并到新 id。 */
        val originalIdById: Map<String, String> = emptyMap(),
    )

    fun merge(user: List<QuickEntry>, remote: List<QuickEntry>): Result {
        val overrides = HashMap<Pair<String, String>, ArrayDeque<QuickEntry>>()
        user.forEach { entry ->
            overrides.getOrPut(entry.label to entry.content) { ArrayDeque() }.addLast(entry)
        }
        val display = ArrayList<QuickEntry>(user.size + remote.size)
        val infoById = LinkedHashMap<String, RemoteRuleInfo>()
        val originalIdById = LinkedHashMap<String, String>()
        val consumed = HashSet<String>()
        remote.forEach { rule ->
            val override = overrides[rule.label to rule.content]?.removeFirstOrNull()
            val effective = if (override == null) {
                rule
            } else {
                consumed.add(override.trigger)
                rule.copy(
                    trigger = override.trigger,
                    mode = override.mode,
                    kind = override.kind,
                )
            }
            val displayId = PhraseUsageStore.idOf(effective)
            infoById[displayId] = RemoteRuleInfo(rule, override?.trigger)
            originalIdById[displayId] = PhraseUsageStore.idOf(rule)
            display.add(effective)
        }
        display.addAll(user.filterNot { it.trigger in consumed })
        return Result(display, infoById, consumed, originalIdById)
    }

    /** 合并结果里每条展示条目的稳定 id（与收藏 / 计数共用同一算法）。 */
    fun idOf(entry: QuickEntry): String = PhraseUsageStore.idOf(entry)

    /** 展示条目是否来自远端（有远端信息即视为远端卡片）。 */
    fun isRemote(result: Result, entry: QuickEntry): Boolean =
        result.infoById.containsKey(idOf(entry))

    /** 用户条目是否是远端规则的本地覆盖（推送时跳过）。 */
    fun isLocalOverride(result: Result, entry: QuickEntry): Boolean =
        entry.source == QuickSource.USER && entry.trigger in result.skipPushTriggers
}