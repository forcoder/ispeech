package com.ispeech.data

import com.ispeech.engine.QuickTrigger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 同码冲突记录文件（filesDir/quick_phrases_conflicts.txt）：`trigger \t remoteId \t remoteFingerprint \t detectedAt`。
 *
 * 存在理由（ADR-0002 C3-2）：push 遇到冲突时不覆盖远端，但必须把「哪条没同步上去」留痕，
 * 否则用户只会看到「已同步」而云端正文一直是别人的内容。正文不进这张表，
 * 展示时按 trigger 现读本地文件与 pull 产物，避免两处副本互相打架。
 *
 * 与 [RemoteRuleMap] 同风格：TAB 分隔、`#` 注释、坏行跳过，读写失败由调用方决定后果。
 */
internal object QuickConflictStore {

    /** 冲突记录文件名。 */
    const val FILE_NAME = "quick_phrases_conflicts.txt"

    private const val SEPARATOR = '\t'
    private const val FIELD_COUNT = 4

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 一条未解决的冲突记录。 */
    internal data class Record(
        val trigger: String,
        val remoteId: String,
        val remoteFingerprint: String,
        val detectedAt: String,
    )

    /** 读取冲突记录；文件不存在返回空表，主键为归一化 trigger。 */
    fun load(file: File): LinkedHashMap<String, Record> {
        val out = LinkedHashMap<String, Record>()
        if (!file.exists()) return out
        runCatching {
            file.readText(Charsets.UTF_8).lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val fields = line.split(SEPARATOR)
                if (fields.size < FIELD_COUNT) return@forEach
                val trigger = QuickTrigger.normalize(unescapeLineText(fields[0]))
                val remoteId = unescapeLineText(fields[1]).trim()
                if (trigger.isEmpty() || remoteId.isEmpty()) return@forEach
                out[trigger] = Record(
                    trigger = trigger,
                    remoteId = remoteId,
                    remoteFingerprint = fields[2].trim(),
                    detectedAt = unescapeLineText(fields[3]).trim(),
                )
            }
        }
        return out
    }

    /** 写回冲突记录（整表覆盖）。 */
    fun save(file: File, records: Map<String, Record>) {
        val builder = StringBuilder()
        builder.append("# 同码冲突记录（自动生成，请勿手动编辑）：trigger\\tremoteId\\tremoteFingerprint\\tdetectedAt\n")
        records.forEach { (_, record) ->
            builder.append(escapeLineText(record.trigger)).append(SEPARATOR)
                .append(escapeLineText(record.remoteId)).append(SEPARATOR)
                .append(record.remoteFingerprint).append(SEPARATOR)
                .append(escapeLineText(record.detectedAt)).append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
    }

    /**
     * 合并一轮检测结果：
     * 新冲突登记（同 trigger 保留最早 detectedAt，避免时间戳每轮刷新）；
     * 本轮**已参与评估**且不再冲突的移出；未评估到的（如远端列表没返回）原样保留。
     *
     * @param detected 本轮发现的冲突
     * @param evaluatedTriggers 本轮确实比对过的 trigger（归一化）
     */
    fun merge(
        existing: Map<String, Record>,
        detected: List<LocalRuleSyncPlan.Conflict>,
        evaluatedTriggers: Set<String>,
        now: String = currentTime(),
    ): LinkedHashMap<String, Record> {
        val out = LinkedHashMap(existing)
        val detectedByTrigger = detected.associateBy { it.trigger }
        evaluatedTriggers.forEach { trigger ->
            if (trigger !in detectedByTrigger) out.remove(trigger)
        }
        detected.forEach { conflict ->
            val previous = out[conflict.trigger]
            out[conflict.trigger] = Record(
                trigger = conflict.trigger,
                remoteId = conflict.remoteId,
                remoteFingerprint = conflict.remoteFingerprint,
                detectedAt = previous?.detectedAt ?: now,
            )
        }
        return out
    }

    /** 当前时间戳（首次检测时间）。 */
    fun currentTime(): String = LocalDateTime.now().format(TIME_FORMAT)
}
