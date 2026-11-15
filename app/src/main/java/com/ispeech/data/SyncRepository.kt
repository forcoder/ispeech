package com.ispeech.data

import android.content.Context
import android.util.Log
import com.ispeech.data.remote.RuleUpsertRequest
import com.ispeech.data.remote.SyncPushRequest
import com.ispeech.data.remote.SyncPushRule
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickLocator
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickOverride
import com.ispeech.engine.QuickPhraseEngine
import com.ispeech.engine.QuickTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** csBaby 同步仓库：拉取远端关键词规则并落盘为本地短语文件。 */
object SyncRepository {

    private const val TAG = "SyncRepository"

    /** 日志里指纹只保留前缀：足够定位单次同步，又不把可逆哈希整段写进日志。 */
    private const val FINGERPRINT_LOG_PREFIX = 12

    /** 远端规则文件名（与内置 quick_phrases.txt 同格式）。 */
    const val REMOTE_RULES_FILE_NAME = "quick_phrases_remote.txt"

    /** 触发词 → 远端规则 id / 内容指纹的映射文件；修改与删除靠它定位远端条目。 */
    const val REMOTE_RULE_MAP_FILE_NAME = "quick_phrases_remote_map.txt"

    /** 远端 priority 为 0 / 负数时视为未设置，与 CsBabyClient.toQuickEntry 的默认权重口径一致。 */
    private const val DEFAULT_PRIORITY = 100

    /** 一次「保存并同步」的结果：本地文件已写盘，这里只统计远端实际生效的条数。 */
    data class RuleSyncReport(
        val created: Int = 0,
        val updated: Int = 0,
        val updatedApplied: Int = 0,
        val deleted: Int = 0,
        val deletedApplied: Int = 0,
        /** 同码冲突条数：本轮没有上传，等用户在同步页选择（ADR-0002 C3-1 / C3-7）。 */
        val conflicts: Int = 0,
    ) {
        /** 本次尝试推送的条数（新增 + 修改 + 删除）。 */
        val pushed: Int get() = created + updated + deleted

        /** 远端未生效的条数；后端缺少修改 / 删除能力时大于 0。 */
        val pending: Int get() = (updated - updatedApplied) + (deleted - deletedApplied)
    }

    /**
     * 拉取远端关键词规则并写入 filesDir/quick_phrases_remote.txt。
     *
     * 流程：读取本地登录态（未登录直接抛错）→ 过期且可刷新时先刷新并保存 →
     * fetchAllRules → 转换为 QuickEntry → 写文件。
     *
     * @return 写入的规则条数（无有效规则时同样写入只含注释头的空文件）
     */
    suspend fun pullAndWriteRemoteRules(context: Context): Int = withContext(Dispatchers.IO) {
        var auth = CsBabyAuthStore.load(context)
            ?: throw CsBabyException.Api("未登录：请先登录 csBaby 账号")

        val client = CsBabyClient(context)
        if (auth.isExpired && auth.refreshToken.isNotBlank()) {
            auth = client.refresh(auth.refreshToken, auth.identifier)
            CsBabyAuthStore.save(context, auth)
        }

        val entries = client.fetchAllRules(auth).mapNotNull { CsBabyClient.toQuickEntry(it) }
        writeEntries(File(context.filesDir, REMOTE_RULES_FILE_NAME), entries)
        entries.size
    }

    /**
     * 把本地用户快捷短语与 csBaby 远端对齐（新增 / 修改 / 删除），返回远端生效统计。
     *
     * 对账依据是 [REMOTE_RULE_MAP_FILE_NAME]（触发词 → 远端 id + 内容指纹）：本地删除一条规则后
     * 文件里不留痕迹，没有这张表就无从知道该删远端哪个 id，删除同步也就无法实现。
     *
     * 新增走 `POST api/rules`（唯一确认可用的写接口）；修改 / 删除走 `POST sync/push`
     * （rules 带 id 表示按 id 覆盖，deletedIds 表示删除）。后端对单条规则没有 PUT / DELETE
     * 路由，且 push 的生效范围未经确认，因此推送后**重新拉取远端列表复核**：只有远端确实变了
     * 才计入 applied 并更新映射表，未生效的部分如实上报为 pending，不做「假装成功」。
     *
     * 写安全线（ADR-0002 C3）：哪些条目进新增、哪些进修改、哪些只补映射、哪些算冲突，
     * 全部由 [LocalRuleSyncPlan.decide] 决定；没有映射记录的同码远端规则一律记冲突，
     * 不会被本地正文覆盖。
     */
    suspend fun pushLocalRules(context: Context): RuleSyncReport = withContext(Dispatchers.IO) {
        var auth = CsBabyAuthStore.load(context)
            ?: throw CsBabyException.Api("未登录：请先登录 csBaby 账号")

        val client = CsBabyClient(context)
        if (auth.isExpired && auth.refreshToken.isNotBlank()) {
            auth = client.refresh(auth.refreshToken, auth.identifier)
            CsBabyAuthStore.save(context, auth)
        }

        // 快捷字母覆盖只在本地生效（见 QuickOverride）：跳过它们，避免云端出现同内容的重复规则
        val remoteEntries = readRemoteEntries(context)
        val allLocals = readLocalUserEntries(context)
            .filterNot { entry -> QuickOverride.matchesAny(entry, remoteEntries) }
        // C3-3：上传集合按校验器过滤（不放宽上限）。被挡下的条目仍参与删除对账，
        // 绝不能因为「不进上传集合」就被当成本地已删除而删掉远端规则。
        val locals = allLocals.filter { QuickTrigger.isValidShortcutTrigger(it.trigger) }
        val stale = allLocals.filterNot { QuickTrigger.isValidShortcutTrigger(it.trigger) }

        val remote = client.listRules(auth)
        val mapFile = File(context.filesDir, REMOTE_RULE_MAP_FILE_NAME)
        val known = RemoteRuleMap.load(mapFile)
        val next = LinkedHashMap(known)

        val remoteById = remote.associateBy { it.id }
        val remoteByKeyword = remote.associateBy { QuickTrigger.normalize(it.keyword) }

        val decisions = LocalRuleSyncPlan.decide(
            locals = locals,
            known = known,
            remoteById = remoteById,
            remoteByKeyword = remoteByKeyword,
            localFingerprintOf = { RemoteRuleMap.fingerprint(it.remoteFields()) },
            remoteFingerprintOf = { LocalRuleSyncPlan.fingerprintOf(it.replyTemplate) },
            remoteMatches = { rule, entry -> rule.matches(entry) },
            stale = stale,
        )

        // C3-2：冲突先写盘、再动网络。写盘失败就整轮不上传（不降级成「盲推」），
        // 否则用户看到的「已同步」会把没解决的冲突一起盖过去。
        val evaluated = (locals + stale).map { QuickTrigger.normalize(it.trigger) }.toSet()
        val conflictFile = File(context.filesDir, QuickConflictStore.FILE_NAME)
        val conflicts = runCatching {
            QuickConflictStore.save(
                conflictFile,
                QuickConflictStore.merge(
                    existing = QuickConflictStore.load(conflictFile),
                    detected = decisions.conflicts,
                    evaluatedTriggers = evaluated,
                ),
            )
            decisions.conflicts
        }.getOrElse { throwable ->
            Log.w(TAG, "quick_sync_conflict_write_failed reason=${throwable.javaClass.simpleName}")
            // 不降级：冲突一条都没解决就整轮不上传，条目数如实上报（ADR-0002 C3-2）
            return@withContext RuleSyncReport(conflicts = decisions.conflicts.size)
        }
        conflicts.forEach { conflict ->
            // 只记触发词、远端 id、正文长度与指纹前缀，不落任何正文（ADR-0002 §10）
            Log.w(
                TAG,
                "quick_sync_conflict trigger=${conflict.trigger} remoteId=${conflict.remoteId} " +
                    "localLen=${conflict.localContent.length} remoteLen=${conflict.remoteContent.length} " +
                    "remoteFp=${conflict.remoteFingerprint.take(FINGERPRINT_LOG_PREFIX)}",
            )
        }

        val updates = ArrayList<SyncPushRule>()
        // ruleId / 触发词 / 本地条目：推送后逐条复核远端是否真的改了
        val updateTargets = ArrayList<Triple<String, String, QuickEntry>>()
        val deletes = ArrayList<String>()
        // 远端 id / 触发词：复核删除是否真的生效
        val deleteTargets = ArrayList<Pair<String, String>>()
        var created = 0

        decisions.creates.forEach { entry ->
            val trigger = QuickTrigger.normalize(entry.trigger)
            val fingerprint = RemoteRuleMap.fingerprint(entry.remoteFields())
            val rule = client.createRule(auth, entry.toRuleRequest())
            if (rule.id.isNotBlank()) next[trigger] = RemoteRuleRef(rule.id, fingerprint)
            created++
        }
        decisions.bindings.forEach { binding ->
            next[binding.trigger] = RemoteRuleRef(binding.remoteId, binding.fingerprint)
        }
        decisions.updates.forEach { update ->
            val trigger = QuickTrigger.normalize(update.entry.trigger)
            updates.add(update.entry.toPushRule(update.remoteId))
            updateTargets.add(Triple(update.remoteId, trigger, update.entry))
        }

        val localTriggers = allLocals.map { QuickTrigger.normalize(it.trigger) }.toHashSet()
        for ((trigger, ref) in known) {
            if (trigger in localTriggers) continue
            if (remoteById.containsKey(ref.id)) {
                deletes.add(ref.id)
                deleteTargets.add(ref.id to trigger)
            } else {
                // 远端已经没有了，映射表也一并清掉
                next.remove(trigger)
            }
        }

        var updatedApplied = 0
        var deletedApplied = 0
        if (updates.isNotEmpty() || deletes.isNotEmpty()) {
            // 推送失败不阻断本地保存：复核结果会体现为 pending，下次保存再重试
            runCatching { client.pushSync(auth, SyncPushRequest(updates, deletes)) }
            val after = runCatching { client.listRules(auth) }.getOrElse { remote }
            val afterById = after.associateBy { it.id }
            for ((id, trigger, entry) in updateTargets) {
                val rule = afterById[id]
                if (rule != null && !rule.deleted && rule.matches(entry)) {
                    updatedApplied++
                    next[trigger] = RemoteRuleRef(id, RemoteRuleMap.fingerprint(entry.remoteFields()))
                } else if (rule != null) {
                    // 推了但远端没变：保留映射（下次仍按「修改」重试），不删映射，
                    // 否则下一轮会把它当新规则 create，云端出现同码重复。
                    Log.w(
                        TAG,
                        "quick_sync_update_not_applied trigger=$trigger remoteId=$id " +
                            "remoteFp=${LocalRuleSyncPlan.fingerprintOf(rule.replyTemplate).take(FINGERPRINT_LOG_PREFIX)}",
                    )
                }
            }
            for ((id, trigger) in deleteTargets) {
                val rule = afterById[id]
                if (rule == null || rule.deleted) {
                    deletedApplied++
                    next.remove(trigger)
                }
            }
        }

        RemoteRuleMap.save(mapFile, next)
        RuleSyncReport(
            created = created,
            updated = updates.size,
            updatedApplied = updatedApplied,
            deleted = deletes.size,
            deletedApplied = deletedApplied,
            conflicts = conflicts.size,
        )
    }

    /**
     * 冲突「以本地为准并更新云端」（ADR-0002 C3-7）：只推这一条，推后复验，
     * 生效才从冲突表移出并更新映射；未生效保持原状，下次同步会再次列出。
     */
    suspend fun resolveConflictWithLocal(context: Context, trigger: String): Boolean =
        withContext(Dispatchers.IO) {
            val auth = CsBabyAuthStore.load(context) ?: return@withContext false
            val normalized = QuickTrigger.normalize(trigger)
            if (!QuickTrigger.isValidShortcutTrigger(normalized)) return@withContext false
            val entry = readLocalUserEntries(context)
                .firstOrNull { QuickTrigger.normalize(it.trigger) == normalized }
                ?: return@withContext false

            val client = CsBabyClient(context)
            val mapFile = File(context.filesDir, REMOTE_RULE_MAP_FILE_NAME)
            val known = RemoteRuleMap.load(mapFile)
            val remote = runCatching { client.listRules(auth) }.getOrNull().orEmpty()
            val target = remoteById(remote)[known[normalized]?.id.orEmpty()]
                ?: remoteByKeyword(remote)[normalized]
                ?: return@withContext false

            val pushed = runCatching {
                client.pushSync(auth, SyncPushRequest(listOf(entry.toPushRule(target.id)), emptyList()))
            }.isSuccess
            val after = runCatching { client.listRules(auth) }.getOrElse { remote }
            val applied = after.find { it.id == target.id }?.let { !it.deleted && it.matches(entry) } == true
            if (!pushed || !applied) return@withContext false

            val next = LinkedHashMap(known)
            next[normalized] = RemoteRuleRef(target.id, RemoteRuleMap.fingerprint(entry.remoteFields()))
            RemoteRuleMap.save(mapFile, next)
            val conflictFile = File(context.filesDir, QuickConflictStore.FILE_NAME)
            runCatching {
                val records = QuickConflictStore.load(conflictFile)
                records.remove(normalized)
                QuickConflictStore.save(conflictFile, records)
            }
            true
        }

    /** 冲突清单（离线读落盘记录 + 本地文件 + pull 产物），供同步页逐条对比正文。 */
    internal suspend fun loadConflicts(context: Context): List<LocalRuleSyncPlan.Conflict> =
        withContext(Dispatchers.IO) {
            val idByTrigger = runCatching {
                RemoteRuleMap.load(File(context.filesDir, REMOTE_RULE_MAP_FILE_NAME))
            }.getOrDefault(LinkedHashMap())
                .entries.associate { (trigger, ref) -> trigger to ref.id }
            LocalRuleSyncPlan.conflictsFromFiles(
                // C3-3 同口径：只有校验通过的本地条目参与同步，冲突清单也不列被挡下的条目
                locals = readLocalUserEntries(context).filter {
                    QuickTrigger.isValidShortcutTrigger(it.trigger)
                },
                remoteEntries = readRemoteEntries(context),
                // pull 产物不带远端 id，用触发词反查映射表；查不到留空串，由界面显示「未知」
                remoteIdOf = { entry -> idByTrigger[QuickTrigger.normalize(entry.trigger)].orEmpty() },
            )
        }

    private fun remoteById(rules: List<RemoteKeywordRule>): Map<String, RemoteKeywordRule> =
        rules.associateBy { it.id }

    private fun remoteByKeyword(rules: List<RemoteKeywordRule>): Map<String, RemoteKeywordRule> =
        rules.associateBy { QuickTrigger.normalize(it.keyword) }

    /** 读取本地用户自定义规则（filesDir/quick_phrases_user.txt），失败返回空列表。 */
    private fun readLocalUserEntries(context: Context): List<QuickEntry> {
        val stream = QuickLocator.openUser(context) ?: return emptyList()
        return runCatching {
            stream.use { input ->
                QuickPhraseEngine.fromText(input.readBytes().toString(Charsets.UTF_8))?.entries.orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    /** 读取远端规则文件（filesDir/quick_phrases_remote.txt），用于识别本地覆盖条目。 */
    private fun readRemoteEntries(context: Context): List<QuickEntry> {
        val stream = QuickLocator.openRemote(context) ?: return emptyList()
        return runCatching {
            stream.use { input ->
                QuickPhraseEngine.fromText(input.readBytes().toString(Charsets.UTF_8))?.entries.orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    /** 本地用户规则 → 远端创建请求；PREFIX 后端不支持，省略 match_type 由后端默认 CONTAINS。 */
    private fun QuickEntry.toRuleRequest(): RuleUpsertRequest = RuleUpsertRequest(
        keyword = trigger,
        replyTemplate = content,
        matchType = remoteMatchType(mode),
        priority = weight,
        // ADR-0002 C3-8：不写 enabled。带上 true 会让云端被用户停用的规则每次同步都偷偷打开。
        enabled = null,
    )

    /** 本地用户规则 → 推送请求（带 id 表示覆盖远端已有条目）。 */
    private fun QuickEntry.toPushRule(id: String): SyncPushRule = SyncPushRule(
        id = id,
        keyword = trigger,
        replyTemplate = content,
        matchType = remoteMatchType(mode),
        priority = weight,
        // ADR-0002 C3-8：同上，push 也不带 enabled
        enabled = null,
    )

    /** 条目在远端可见的字段（推送与内容指纹共用，保证「改了才推」与「推了才算」口径一致）。 */
    private fun QuickEntry.remoteFields(): List<String> = listOf(
        trigger,
        content,
        remoteMatchType(mode).orEmpty(),
        weight.toString(),
        if (kind == LookupKind.QUICKAPPLY) "a" else "p",
    )

    /**
     * 远端 match_type 写法：PREFIX 与 CONTAINS 都归到 CONTAINS。
     *
     * 后端只认 EXACT / CONTAINS / REGEX，PREFIX 会被拒或按默认值落成 CONTAINS；若把两者
     * 当成两个档，每次同步都会「推上去又变回来」，反复重推。归一化后语义差异只体现在本地匹配。
     */
    private fun remoteMatchType(mode: QuickMatchMode): String? = when (mode) {
        QuickMatchMode.EXACT -> "EXACT"
        QuickMatchMode.REGEX -> "REGEX"
        QuickMatchMode.CONTAINS, QuickMatchMode.PREFIX -> "CONTAINS"
    }

    /** 远端 match_type 归一化，与 [remoteMatchType] 同口径（未知 / 空值按 CONTAINS）。 */
    private fun normalizeRemoteMatchType(raw: String): String = when (raw.trim().uppercase()) {
        "EXACT" -> "EXACT"
        "REGEX" -> "REGEX"
        else -> "CONTAINS"
    }

    /** 远端规则是否与本条目在远端可见字段上完全一致（enabled / deleted 由调用方另行判断）。 */
    private fun RemoteKeywordRule.matches(entry: QuickEntry): Boolean =
        keyword == entry.trigger &&
            escapeForFile(replyTemplate) == entry.content &&
            normalizeRemoteMatchType(matchType) == remoteMatchType(entry.mode) &&
            normalizedPriority() == entry.weight

    /** 远端 priority 为 0 / 负数时按默认权重比较，避免与后端默认值不一致时反复重推。 */
    private fun RemoteKeywordRule.normalizedPriority(): Int =
        if (priority > 0) priority else DEFAULT_PRIORITY

    /** 按 trigger\tlabel\tcontent\tmode\tweight\ta 格式写入，首行为 # 注释。 */
    private fun writeEntries(file: File, entries: List<QuickEntry>) {
        val builder = StringBuilder()
        builder.append("# csBaby 远端关键词规则（自动生成，请勿手动编辑）：")
            .append(entries.size)
            .append(" 条\n")
        entries.forEach { entry ->
            builder.append(entry.trigger).append('\t')
                .append(entry.label).append('\t')
                .append(entry.content).append('\t')
                .append(entry.mode.name).append('\t')
                .append(entry.weight).append('\t')
                .append(if (entry.kind == LookupKind.QUICKAPPLY) 'a' else 'p')
                .append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
    }
}

/** 本地触发词对应的远端规则引用：远端 id + 上次同步成功时的内容指纹。 */
internal class RemoteRuleRef(val id: String, val fingerprint: String)

/**
 * 换行 / 制表符转成字面量：与写入本地文件、CsBabyClient.toQuickEntry 的转义口径一致。
 *
 * 对已转义的文本幂等（不含真实 CR/LF/TAB），所以联网正文与落盘正文可以用同一个函数比较，
 * 这就是 ADR-0002 C2-5 要求的「比较口径 = 落盘转义口径」。
 */
internal fun escapeForFile(text: String): String =
    text.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t")

/**
 * 远端规则映射表（filesDir/quick_phrases_remote_map.txt）：`trigger \t id \t fingerprint`。
 *
 * 与 PhraseUsageStore 同风格：TAB 分隔、`#` 注释、转义复用 escapeLineText / unescapeLineText。
 * 主键一律经 [QuickTrigger.normalize] 归一化（ADR-0002 C3-9）：大小写或首尾空格的差异
 * 不允许变成两条映射记录，否则同一远端规则会被反复重推甚至重复新建。
 * 坏行 / 缺字段直接跳过：映射表损坏的代价只是下次同步重推一遍，不值得抛异常打断保存。
 */
internal object RemoteRuleMap {

    private const val SEPARATOR = '\t'
    private const val FIELD_COUNT = 3

    /** 读取映射表；文件不存在返回空表。 */
    fun load(file: File): MutableMap<String, RemoteRuleRef> {
        val out = LinkedHashMap<String, RemoteRuleRef>()
        if (!file.exists()) return out
        runCatching {
            file.readText(Charsets.UTF_8).lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val fields = line.split(SEPARATOR)
                if (fields.size < FIELD_COUNT) return@forEach
                val trigger = QuickTrigger.normalize(unescapeLineText(fields[0]))
                val id = unescapeLineText(fields[1]).trim()
                if (trigger.isEmpty() || id.isEmpty()) return@forEach
                out[trigger] = RemoteRuleRef(id, fields[2].trim())
            }
        }
        return out
    }

    /** 写回映射表（整表覆盖）。 */
    fun save(file: File, map: Map<String, RemoteRuleRef>) {
        val builder = StringBuilder()
        builder.append("# csBaby 远端规则映射（自动生成，请勿手动编辑）：trigger\\tid\\tfingerprint\n")
        map.forEach { (trigger, ref) ->
            builder.append(escapeLineText(trigger)).append(SEPARATOR)
                .append(escapeLineText(ref.id)).append(SEPARATOR)
                .append(ref.fingerprint).append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
    }

    /** 内容指纹：远端可见字段任一变化即视为「需要重推」。 */
    fun fingerprint(fields: List<String>): String =
        fields.joinToString("\u0001").hashCode().toUInt().toString(16)
}
