package com.ispeech.engine

import com.ispeech.data.unescapeLineText

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
        val scanCap = maxOf(limit, 64)
        for (entry in entries) {
            if (!matches(entry, needle)) continue
            // 精确命中的条目必须参与排序：扫描上限只约束模糊命中，
            // 否则规则表很大时，排在后面的精确简写会被提前截断，拿不到候选首位。
            val exact = entry.trigger.equals(needle, ignoreCase = true)
            if (!exact && out.size >= scanCap) continue
            out.add(
                LookupCandidate(
                    code = entry.trigger,
                    display = entry.label,
                    insertText = entry.content,
                    weight = entry.weight,
                    kind = entry.kind
                )
            )
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

        /**
         * 五笔态能敲全的触发码长度上限（与 `IspeechImeService.MAX_CODE_LENGTH` 同值）。
         * 超过它的简写在五笔态只能靠前缀 / 子串命中，而 `minTriggerLength` 只挡单字母，
         * 挡不住「敲 4 位 `dtws` 命中 5 位码 `dtwsl`」把整段长正文顶到候选首位。
         */
        const val WUBI_REACHABLE_TRIGGER_LENGTH = 4

        /** 解析规则文本，按文件声明的匹配方式原样生效；没有任何有效条目时返回 null。 */
        fun fromText(text: String, minTriggerLength: Int = 2): QuickPhraseEngine? =
            parse(text, minTriggerLength, applyTriggerLengthGuard = false)

        /**
         * 解析云端落盘的远端规则（`filesDir/quick_phrases_remote.txt`），并对存量行补一道读盘侧长度守卫：
         * `trigger` 超过 [WUBI_REACHABLE_TRIGGER_LENGTH] 位且声明 `PREFIX` / `CONTAINS` 的行按 `EXACT` 生效。
         *
         * 为什么守卫要放在读盘侧：VOCA-391 的降级只做在拉取侧，对「装上新包之后再点一次同步」的设备才有效；
         * 升级前已经写在盘上的 `CONTAINS` / `PREFIX` 行不会被回写，那些设备升级后行为一切照旧。
         *
         * 只重解释匹配方式：`trigger` / `label` / `content` / `weight` / `kind` 原样保留，磁盘文件也不动。
         * 内置资产与用户文件不走这里——那两份的 `>4 位 + prefix` 是有意的（搜狗 18 条 5 位常用语、
         * CSV 导入「打前 4 位即前缀命中」），口径见 ADR-0002 C1-5 / C1-10。
         */
        fun fromRemoteText(text: String, minTriggerLength: Int = 2): QuickPhraseEngine? =
            parse(text, minTriggerLength, applyTriggerLengthGuard = true)

        /**
         * 读盘侧长度守卫的判定，与拉取侧云端 `match_type` 映射（VOCA-391）、ADR-0002 C1-6 同口径。
         * `REGEX` 不降级：它与 `EXACT` 同属不受长度守卫的模式（`check_quick_trigger.py` 的 `UNGUARDED_MODES`），
         * 命中与否由表达式自己决定。
         */
        fun guardedRemoteMode(trigger: String, declared: QuickMatchMode): QuickMatchMode =
            if (trigger.length > WUBI_REACHABLE_TRIGGER_LENGTH &&
                (declared == QuickMatchMode.PREFIX || declared == QuickMatchMode.CONTAINS)
            ) {
                QuickMatchMode.EXACT
            } else {
                declared
            }

        private fun parse(
            text: String,
            minTriggerLength: Int,
            applyTriggerLengthGuard: Boolean,
        ): QuickPhraseEngine? {
            val parsed = ArrayList<QuickEntry>()
            for (rawLine in text.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 3) continue
                val trigger = fields[0]
                val label = fields[1]
                // 写入侧（SyncRepository / QuickPhraseCodec）把换行 / 制表符写成字面量 \n \t，这里还原
                val content = unescapeLineText(fields[2])
                if (trigger.isEmpty() || content.isEmpty()) continue
                val declaredMode =
                    if (fields.size > 3) QuickMatchMode.parse(fields[3]) else QuickMatchMode.PREFIX
                val mode = if (applyTriggerLengthGuard) guardedRemoteMode(trigger, declaredMode) else declaredMode
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
