package com.ispeech.engine

/**
 * 快捷短语 / 快速应用引擎。
 *
 * 规则文本每行（tab 分隔）：
 * trigger <TAB> label <TAB> content [<TAB> mode] [<TAB> weight] [<TAB> kind]
 * - mode: exact / prefix / contains / regex，缺省 prefix
 * - weight: 整数，缺省 100
 * - kind: p=短语、a=应用；缺省时 content 与 label 相同判为短语，否则判为应用
 */
class QuickPhraseEngine(
    val entries: List<QuickEntry>,
    val minTriggerLength: Int = 2,
) : LookupEngine, AssociationSource {

    override val displayName: String = "quick"

    /**
     * 按触发码匹配候选。
     *
     * 排序：精确码优先 → 应用 < 短语 < 五笔 → 权重降序 → display 升序。
     * 防误触：prefix / contains 模式在输入不足 2 个字符时不返回，把单字母留给五笔一级简码；
     * exact / regex 不受该限制。
     */
    override fun lookup(input: String, limit: Int): List<LookupCandidate> {
        val needle = input.lowercase()
        if (needle.isEmpty() || limit <= 0) return emptyList()

        val out = ArrayList<LookupCandidate>()
        for (entry in entries) {
            if (!matches(entry, needle)) continue
            out.add(
                LookupCandidate(
                    code = entry.trigger,
                    display = entry.label,
                    insertText = entry.content,
                    weight = entry.weight,
                    kind = entry.kind
                )
            )
            if (out.size >= maxOf(limit, 64)) break
        }
        if (out.isEmpty()) return emptyList()
        out.sortWith(CandidateRanking.comparator(input))
        return out.take(limit)
    }

    private fun matches(entry: QuickEntry, needle: String): Boolean {
        val trigger = entry.trigger.lowercase()
        // 单个字母不触发前缀 / 包含规则：留给五笔一级简码，避免打字中途上屏长文本
        val guarded = entry.mode == QuickMatchMode.PREFIX || entry.mode == QuickMatchMode.CONTAINS
        if (guarded && needle.length < minTriggerLength) return false
        return when (entry.mode) {
            QuickMatchMode.EXACT -> trigger == needle
            QuickMatchMode.PREFIX -> trigger.startsWith(needle) || needle.startsWith(trigger)
            QuickMatchMode.CONTAINS -> trigger.contains(needle) || needle.contains(trigger)
            QuickMatchMode.REGEX -> runCatching {
                Regex(entry.trigger).containsMatchIn(needle)
            }.getOrDefault(false)
        }
    }

    /**
     * 快捷短语联想：已上屏文本 [text] 出现在规则的触发码 / 名称 / 内容中时给出候选。
     * 用于「上屏一个词后，顺手带出相关快捷短语」的场景（例如客户提到「发票」）。
     */
    override fun related(text: String, limit: Int): List<LookupCandidate> {
        if (limit <= 0) return emptyList()
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val out = ArrayList<LookupCandidate>()
        for (entry in entries) {
            val haystacks = listOf(
                entry.trigger.lowercase(),
                entry.label.lowercase(),
                entry.content.lowercase(),
            )
            if (haystacks.none { it.contains(needle) }) continue
            out.add(
                LookupCandidate(
                    code = entry.trigger,
                    display = entry.label,
                    insertText = entry.content,
                    weight = entry.weight,
                    kind = entry.kind,
                )
            )
            if (out.size >= ASSOCIATION_SCAN_LIMIT) break
        }
        if (out.isEmpty()) return emptyList()
        out.sortWith(Comparator { a, b -> b.weight.compareTo(a.weight) })
        return out.take(limit)
    }

    companion object {
        /** 联想扫描上限，避免规则很多时每次都全量排序大列表。 */
        private const val ASSOCIATION_SCAN_LIMIT = 64

        /** 解析规则文本；没有任何有效条目时返回 null。 */
        fun fromText(text: String, minTriggerLength: Int = 2): QuickPhraseEngine? {
            val parsed = ArrayList<QuickEntry>()
            for (rawLine in text.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 3) continue
                val trigger = fields[0]
                val label = fields[1]
                val content = fields[2]
                if (trigger.isEmpty() || content.isEmpty()) continue
                val mode = if (fields.size > 3) QuickMatchMode.parse(fields[3]) else QuickMatchMode.PREFIX
                val weight = if (fields.size > 4) fields[4].toIntOrNull() ?: 100 else 100
                val kind = if (fields.size > 5) {
                    if (fields[5].lowercase().startsWith("a")) LookupKind.QUICKAPPLY else LookupKind.PHRASE
                } else {
                    if (content == label) LookupKind.PHRASE else LookupKind.QUICKAPPLY
                }
                parsed.add(
                    QuickEntry(
                        trigger = trigger,
                        label = label.ifEmpty { content },
                        content = content,
                        weight = weight,
                        mode = mode,
                        kind = kind
                    )
                )
            }
            return if (parsed.isEmpty()) null else QuickPhraseEngine(parsed, minTriggerLength)
        }
    }
}
