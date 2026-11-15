package com.ispeech.data

import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickTrigger
import java.security.MessageDigest

/**
 * 本地规则上传前的决策层（ADR-0002 C3-1…C3-5、C3-7、C2-5）。
 *
 * 「云端正文会不会被本地静默覆盖」的唯一决策点，因此做成纯函数、可单测：
 * **没有有效映射记录时，同码远端规则一律记为冲突，绝不进入上传集合（C3-1 / C3-5′）。**
 *
 * 指纹口径保持原样：本地是否「变了」仍由 [RemoteRuleMap.fingerprint] 的远端可见字段指纹判定，
 * 这里只负责把它接进决策；冲突留痕用的远端指纹取正文 sha256（转义后），
 * 与落盘形态同口径，便于离线复现对比。
 */
internal object LocalRuleSyncPlan {

    /** 待推送的修改：远端 id + 本地条目。 */
    internal data class Update(val remoteId: String, val entry: QuickEntry)

    /** 首次绑定：远端已有同码规则且内容一致，只补映射，不推送。 */
    internal data class Binding(val trigger: String, val remoteId: String, val fingerprint: String)

    /** 同码冲突：本地与远端都用这个触发词、正文不同、且本地没有**有效**映射（C3-1 / C3-5′）。 */
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
        /** 本轮判为冲突、且映射已失效（远端不再返回该 id）的触发词：调用方要把它从映射表清掉（C3-5′）。 */
        val staleMappings: List<String> = emptyList(),
    )

    /**
     * @param locals 待上传的本地规则（读取侧从不校验长度，这里也不裁剪：C1-5）
     * @param known 本地映射表，主键必须是 [QuickTrigger.normalize] 后的 trigger（C3-1：它是唯一身份来源）
     * @param remoteById 远端规则按 id 建索引
     * @param remoteByKeyword 远端规则按归一化 keyword 建索引（两个索引都只装未软删的条目，C3-11）
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
    ): Decisions {
        val creates = ArrayList<QuickEntry>()
        val updates = ArrayList<Update>()
        val bindings = ArrayList<Binding>()
        val conflicts = ArrayList<Conflict>()
        val staleMappings = ArrayList<String>()

        locals.forEach { entry ->
            val trigger = QuickTrigger.normalize(entry.trigger)
            val recorded = known[trigger]
            // C3-5′：映射是**会失效的凭证**，不是永久产权。远端不再返回这个 id 时「我有映射」信息量为 0，
            // 于是视同无映射，重新按 C3-1 / C3-2 / C3-3 分类 —— 同码被他人占用且正文不一致判冲突，
            // 不再走 create（那会在云端留下两条同 keyword 规则，命中哪条只取决于返回顺序）。
            val ref = recorded?.takeIf { remoteById.containsKey(it.id) }
            // C3-1/C3-2：只有「无有效映射」时才允许 keyword 兜底，且兜底命中正文不一致一律记冲突。
            val target = if (ref != null) remoteById[ref.id] else remoteByKeyword[trigger]
            val fingerprint = localFingerprintOf(entry)
            when {
                // C3-5′：远端没有这条规则，而且这个码也没人占 → 新建
                target == null -> creates += entry
                // C3-1：无有效映射 + 同码远端正文不同 → 冲突，禁止覆盖
                ref == null && !remoteMatches(target, entry) -> {
                    conflicts += conflict(trigger, entry, target, remoteFingerprintOf)
                    // 冲突分支既不补映射也不更新，映射表里那条死凭证没人改写：交给调用方清（C3-5′）
                    if (recorded != null) staleMappings += trigger
                }
                // C3-2：无有效映射 + 同码远端正文一致 → 只补映射，不推送
                ref == null -> bindings += Binding(trigger, target.id, fingerprint)
                // C3-4：映射在、本地正文变了 → 更新（用户改的是自己拥有的规则）
                ref.fingerprint != fingerprint -> updates += Update(target.id, entry)
                // C3-1：映射在 = 这条远端规则归本地所有；远端正文被人改了 → 推回本地正文
                !remoteMatches(target, entry) -> updates += Update(target.id, entry)
                else -> Unit
            }
        }

        return Decisions(creates, updates, bindings, conflicts, staleMappings)
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
