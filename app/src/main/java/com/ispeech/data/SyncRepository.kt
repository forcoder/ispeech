package com.ispeech.data

import android.content.Context
import com.ispeech.data.remote.RuleUpsertRequest
import com.ispeech.data.remote.SyncPushRequest
import com.ispeech.data.remote.SyncPushRule
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickLocator
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** csBaby 同步仓库：拉取远端关键词规则并落盘为本地短语文件。 */
object SyncRepository {

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
     */
    suspend fun pushLocalRules(context: Context): RuleSyncReport = withContext(Dispatchers.IO) {
        var auth = CsBabyAuthStore.load(context)
            ?: throw CsBabyException.Api("未登录：请先登录 csBaby 账号")

        val client = CsBabyClient(context)
        if (auth.isExpired && auth.refreshToken.isNotBlank()) {
            auth = client.refresh(auth.refreshToken, auth.identifier)
            CsBabyAuthStore.save(context, auth)
        }

        val locals = readLocalUserEntries(context)
        val remote = client.listRules(auth)
        val mapFile = File(context.filesDir, REMOTE_RULE_MAP_FILE_NAME)
        val known = RemoteRuleMap.load(mapFile)
        val next = LinkedHashMap(known)

        val remoteById = remote.associateBy { it.id }
        val remoteByKeyword = remote.associateBy { it.keyword }
        val updates = ArrayList<SyncPushRule>()
        // ruleId / 触发词 / 本地条目：推送后逐条复核远端是否真的改了
        val updateTargets = ArrayList<Triple<String, String, QuickEntry>>()
        val deletes = ArrayList<String>()
        // 远端 id / 触发词：复核删除是否真的生效
        val deleteTargets = ArrayList<Pair<String, String>>()
        var created = 0

        for (entry in locals) {
            if (entry.trigger.isBlank()) continue
            val fingerprint = RemoteRuleMap.fingerprint(entry.remoteFields())
            val ref = known[entry.trigger]
            // 映射表里没有该触发词时按触发词兜底匹配（换机恢复 / 旧版本上传 / 远端手工建的同名规则）
            val target = ref?.let { remoteById[it.id] } ?: remoteByKeyword[entry.trigger]
            when {
                target == null -> {
                    val rule = client.createRule(auth, entry.toRuleRequest())
                    if (rule.id.isNotBlank()) next[entry.trigger] = RemoteRuleRef(rule.id, fingerprint)
                    created++
                }
                // 指纹一致且远端内容一致：无需推送
                ref != null && ref.fingerprint == fingerprint && target.matches(entry) -> Unit
                // 远端内容本来就一致，只是本地缺映射：补上映射即可
                ref == null && target.matches(entry) ->
                    next[entry.trigger] = RemoteRuleRef(target.id, fingerprint)
                else -> {
                    updates.add(entry.toPushRule(target.id))
                    updateTargets.add(Triple(target.id, entry.trigger, entry))
                }
            }
        }

        val localTriggers = locals.map { it.trigger }.toHashSet()
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
        )
    }

    /** 读取本地用户自定义规则（filesDir/quick_phrases_user.txt），失败返回空列表。 */
    private fun readLocalUserEntries(context: Context): List<QuickEntry> {
        val stream = QuickLocator.openUser(context) ?: return emptyList()
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
        enabled = true,
    )

    /** 本地用户规则 → 推送请求（带 id 表示覆盖远端已有条目）。 */
    private fun QuickEntry.toPushRule(id: String): SyncPushRule = SyncPushRule(
        id = id,
        keyword = trigger,
        replyTemplate = content,
        matchType = remoteMatchType(mode),
        priority = weight,
        enabled = true,
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

    /** 换行 / 制表符转成字面量：与写入本地文件、CsBabyClient.toQuickEntry 的转义口径一致。 */
    private fun escapeForFile(text: String): String =
        text.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t")

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
 * 远端规则映射表（filesDir/quick_phrases_remote_map.txt）：`trigger \t id \t fingerprint`。
 *
 * 与 PhraseUsageStore 同风格：TAB 分隔、`#` 注释、转义复用 escapeLineText / unescapeLineText。
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
                val trigger = unescapeLineText(fields[0]).trim()
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
