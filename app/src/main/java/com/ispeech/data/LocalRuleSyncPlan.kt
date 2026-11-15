package com.ispeech.data

import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickTrigger
import java.security.MessageDigest

/**
 * 本地规则上传前的决策层（ADR-0002 C3-1…C3-7、C3-10、C2-5）。
 *
 * 「云端正文会不会被本地静默覆盖」的唯一决策点，因此做成纯函数、可单测：
 * **没有本地映射记录时，同码远端规则一律记为冲突，绝不进入上传集合。**
 *
 * 指纹口径保持原样：本地是否「变了」仍由 [RemoteRuleMap.fingerprint] 的远端可见字段指纹判定，
 * 这里只负责把它接进决策；冲突记录里的远端指纹取正文的 sha256（转义后），
 * 与落盘形态同口径，便于离线复现对比。
 */
internal object LocalRuleSyncPlan {

    /** 待推送的修改：远端 id + 本地条目。 */
    internal data class Update(val remoteId: String, val entry: QuickEntry)

    /** 首次绑定：远端已有同码规则且内容一致，只补映射，不推送。 */
    internal data class Binding(val trigger: String, val remoteId: String, val fingerprint: String)

    /** 同码冲突：本地与远端都用这个触发词、正文不同、且本地没有映射记录。 */
    internal data class Conflict(
        val trigger: String,
        val remoteId: String,
        val remoteFingerprint: String,
        val localContent: String,
        val remoteContent: String,
    )

    internal data class Decisions(
        val creates: List<QuickEntry> = emptyList(),
        val updates: List<Update> = emptyList(),
        val bindings: List<Binding> = emptyList(),
        val conflicts: List<Conflict> = emptyList(),
    )

    /**
     * @param locals 允许上传的本地规则（已过 [QuickTrigger.isValidShortcutTrigger]，ADR-0002 C3-3）
     * @param known 本地映射表，主键必须是 [QuickTrigger.normalize] 后的 trigger（C3-9）
     * @param remoteById 远端规则按 id 建索引
     * @param remoteByKeyword 远端规则按归一化 keyword 建索引
     * @param stale 有映射记录、但本轮被校验器挡在上传集合外的本地条目（C3-10：不得静默）
     * @param localFingerprintOf 本地条目的远端可见字段指纹（与映射表记录同口径）
     * @param remoteFingerprintOf 远端规则正文指纹（只用于冲突留痕）
     * @param remoteMatches 远端规则与本地条目在远端可见字段上是否一致
     */
    fun decide(
        locals: List<QuickEntry>,
        known: Map<String, RemoteRuleRef>,
        remoteById: Map<String, RemoteKeywordRule>,
        remoteByKeyword: Map<String, RemoteKeywordRule>,
        localFingerprintOf: (QuickEntry) -> String,
        remoteFingerprintOf: (RemoteKeywordRule) -> String,
        remoteMatches: (RemoteKeywordRule, QuickEntry) -> Boolean,
        stale: List<QuickEntry> = emptyList(),
    ): Decisions {
        val creates = ArrayList<QuickEntry>()
        val updates = ArrayList<Update>()
        val bindings = ArrayList<Binding>()
        val conflicts = ArrayList<Conflict>()

        locals.forEach { entry ->
            val trigger = QuickTrigger.normalize(entry.trigger)
            val ref = known[trigger]
            // 映射表里没有该触发词时按触发词兜底匹配（换机恢复 / 旧版本上传 / 远端手工建的同码规则）
            val target = ref?.let { remoteById[it.id] } ?: remoteByKeyword[trigger]
            val fingerprint = localFingerprintOf(entry)
            when {
                // C3-3：远端没有这个码 → 新建
                target == null -> creates += entry
                // C3-1：无映射记录 + 同码远端内容不同 → 冲突，不覆盖
                ref == null && !remoteMatches(target, entry) ->
                    conflicts += conflict(trigger, entry, target, remoteFingerprintOf)
                // C3-6：无映射记录 + 同码远端内容一致 → 只补映射
                ref == null -> bindings += Binding(trigger, target.id, fingerprint)
                // C3-4：映射在、本地正文变了 → 更新
                ref.fingerprint != fingerprint -> updates += Update(target.id, entry)
                // C3-5：映射在、本地没变、远端被改了 → 更新回本地内容
                !remoteMatches(target, entry) -> updates += Update(target.id, entry)
                else -> Unit
            }
        }

        // C3-10：条目被校验器挡在上传集合外，映射记录仍在 → 同码远端若内容不同必须记冲突
        stale.forEach { entry ->
            val trigger = QuickTrigger.normalize(entry.trigger)
            val ref = known[trigger] ?: return@forEach
            val target = remoteById[ref.id] ?: remoteByKeyword[trigger] ?: return@forEach
            if (remoteMatches(target, entry)) return@forEach
            conflicts += conflict(trigger, entry, target, remoteFingerprintOf)
        }

        return Decisions(creates, updates, bindings, conflicts)
    }

    /**
     * 离线冲突对比：两边正文都取落盘形态（pull 产物与本地用户文件），
     * 供同步页在不联网时列出「本地 vs 云端」差异。
     *
     * @param remoteIdOf 从远端条目取规则 id，取不到返回空串
     */
    fun conflictsFromFiles(
        locals: List<QuickEntry>,
        remoteEntries: List<QuickEntry>,
        remoteIdOf: (QuickEntry) -> String,
    ): List<Conflict> {
        val remoteByTrigger = remoteEntries.filter { it.trigger.isNotBlank() }.associateBy {
            QuickTrigger.normalize(it.trigger)
        }
        return locals.mapNotNull { local ->
            val remote = remoteByTrigger[QuickTrigger.normalize(local.trigger)] ?: return@mapNotNull null
            if (contentEquals(local.content, remote.content)) return@mapNotNull null
            Conflict(
                trigger = QuickTrigger.normalize(local.trigger),
                remoteId = remoteIdOf(remote),
                remoteFingerprint = fingerprintOf(remote.content),
                localContent = escapeForFile(local.content),
                remoteContent = escapeForFile(remote.content),
            )
        }
    }

    /**
     * 「以云端为准并保留本地副本」给本地副本换用的新码：
     * 原码后依次追加 b…z，取第一个未占用且通过校验的（ADR-0002 C3-7）。
     */
    fun backupTrigger(taken: Set<String>, trigger: String): String? {
        val base = QuickTrigger.normalize(trigger)
        for (suffix in 'b'..'z') {
            val candidate = "$base$suffix"
            if (candidate.length > QuickTrigger.MAX_TRIGGER_LENGTH) return null
            if (!QuickTrigger.isValidShortcutTrigger(candidate) || candidate in taken) continue
            return candidate
        }
        return null
    }

    /** 正文比较口径：落盘转义后的字符串全等（ADR-0002 C2-5）。 */
    fun contentEquals(local: String, remote: String): Boolean =
        escapeForFile(local) == escapeForFile(remote)

    /** 正文指纹：落盘转义后 sha256；转义对已转义文本幂等，联网与离线两条路径同口径。 */
    fun fingerprintOf(text: String): String = sha256Hex(escapeForFile(text))

    private fun conflict(
        trigger: String,
        entry: QuickEntry,
        target: RemoteKeywordRule,
        remoteFingerprintOf: (RemoteKeywordRule) -> String,
    ): Conflict = Conflict(
        trigger = trigger,
        remoteId = target.id,
        remoteFingerprint = remoteFingerprintOf(target),
        localContent = escapeForFile(entry.content),
        remoteContent = escapeForFile(target.replyTemplate),
    )

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
