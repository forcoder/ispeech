package com.ispeech.data

import android.content.Context
import java.io.File

/**
 * 建议回复的本地学习库。
 *
 * 目标：让 AI 建议越用越准。做法是**只在本机**记录「用户采纳的建议」和「当时的输入」，
 * 下次生成时把它们当作 few-shot 示例塞进提示词，模型就会照着用户自己的口气写。
 * 不上传任何内容到云端；用户可在设置页一键清空。
 *
 * 落盘格式（`ai_learning.txt`，每行一条）：
 * `次数 <TAB> 输入（转义） <TAB> 采纳的回复（转义）`
 * 次数用于把最常用的写法排在前面，并在示例超额时淘汰低频条目。
 *
 * 统计（`ai_learning_stats.txt`）：`生成次数 <TAB> 采纳次数`，用于设置页展示采纳率。
 */
class AiLearningStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val statsFile = File(appContext.filesDir, STATS_FILE_NAME)
    private val lock = Any()

    /** 一条学习记录：输入 → 用户最终采纳的建议文本。 */
    data class Example(val source: String, val reply: String, val count: Int)

    /** 采纳率统计。 */
    data class Stats(val generated: Int, val accepted: Int) {
        /** 采纳率（0..1）；没有生成过时按 0 处理，避免除零。 */
        val rate: Double get() = if (generated <= 0) 0.0 else accepted.toDouble() / generated
    }

    /** 记一次「展示过建议」：采纳率的分母。 */
    fun recordGenerated() = synchronized(lock) {
        val (generated, accepted) = readStats()
        writeStats(generated + 1, accepted)
    }

    /**
     * 记一次「用户采纳了建议」：采纳率的分子 + 学习语料。
     *
     * [source] 为生成时输入框里的原文，[reply] 为最终上屏的文本。两者都为空时直接忽略。
     * 相同 (source, reply) 只保留一条，次数累加。
     */
    fun recordAccepted(source: String, reply: String) = synchronized(lock) {
        val (generated, accepted) = readStats()
        writeStats(generated, accepted + 1)
        if (source.isBlank() || reply.isBlank()) return@synchronized

        val rows = readRows().toMutableList()
        val index = rows.indexOfFirst { it.source == source && it.reply == reply }
        if (index >= 0) {
            val old = rows[index]
            rows[index] = old.copy(count = old.count + 1)
        } else {
            rows.add(Example(source, reply, 1))
        }
        // 只保留最常用的若干条，避免文件无界增长
        val trimmed = rows.sortedByDescending { it.count }.take(MAX_EXAMPLES)
        writeRows(trimmed)
    }

    /**
     * 取 few-shot 示例，最多 [limit] 条。
     *
     * 排序：与 [like] 字符重合度高的优先（同类话题更容易模仿），其次按使用次数。
     */
    fun examples(limit: Int, like: String): List<Example> {
        if (limit <= 0) return emptyList()
        val rows = synchronized(lock) { readRows() }
        if (rows.isEmpty()) return emptyList()
        val target = like.toSet()
        return rows
            .sortedWith(
                compareByDescending<Example> { row ->
                    if (target.isEmpty()) 0 else row.source.count { it in target }
                }.thenByDescending { it.count }
            )
            .take(limit)
    }

    /**
     * 找一条**可以直接复用**的历史回复。
     *
     * 用户八成会再发一遍几乎相同的话（「客户问发票什么时候开」这类），
     * 与其再调一次模型，不如直接用上次他亲手采纳过的回复：命中率高、零延迟、零成本。
     *
     * 判定用字符集合的 Jaccard 相似度（中文按字比对即可，不引分词）。
     * 阈值默认 0.8 偏保守——宁可回退到模型生成，也不要给一条不贴题的旧回复。
     */
    fun findReusable(source: String, minScore: Double = DEFAULT_REUSE_SCORE): Example? {
        val needle = source.trim()
        if (needle.isEmpty()) return null
        val rows = synchronized(lock) { readRows() }
        if (rows.isEmpty()) return null
        var best: Example? = null
        var bestScore = 0.0
        for (row in rows) {
            val score = similarity(row.source, needle)
            // 同分时用次数多的那条
            if (score > bestScore || (score == bestScore && best != null && row.count > best.count)) {
                best = row
                bestScore = score
            }
        }
        return if (bestScore >= minScore) best else null
    }

    /** 字符集合 Jaccard 相似度，取值 0..1。 */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val setA = a.toSet()
        val setB = b.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val inter = setA.count { it in setB }
        val union = setA.size + setB.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    /** 当前统计。 */
    fun stats(): Stats = synchronized(lock) { readStats() }

    /** 清空学习数据与统计（设置页「清空学习数据」）。 */
    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        runCatching { statsFile.delete() }
        Unit
    }

    // ── 读写 ──

    private fun readRows(): List<Example> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val fields = line.split('\t')
                if (fields.size < 3) return@mapNotNull null
                val count = fields[0].toIntOrNull() ?: return@mapNotNull null
                val source = unescapeLineText(fields[1])
                val reply = unescapeLineText(fields[2])
                if (source.isBlank() || reply.isBlank()) null else Example(source, reply, count)
            }
        }.getOrElse { emptyList() }
    }

    private fun writeRows(rows: List<Example>) {
        runCatching {
            val text = buildString {
                append("# 次数\t输入\t采纳的回复\n")
                rows.forEach { row ->
                    append(row.count)
                    append('\t')
                    append(escapeLineText(row.source))
                    append('\t')
                    append(escapeLineText(row.reply))
                    append('\n')
                }
            }
            file.writeText(text)
        }
    }

    private fun readStats(): Stats {
        if (!statsFile.exists()) return Stats(0, 0)
        return runCatching {
            val fields = statsFile.readText().trim().split('\t')
            Stats(
                generated = fields.getOrNull(0)?.toIntOrNull() ?: 0,
                accepted = fields.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }.getOrElse { Stats(0, 0) }
    }

    private fun writeStats(generated: Int, accepted: Int) {
        runCatching { statsFile.writeText("$generated\t$accepted") }
    }

    companion object {
        const val FILE_NAME = "ai_learning.txt"
        const val STATS_FILE_NAME = "ai_learning_stats.txt"

        /** 学习语料保留上限：够模型模仿口气，又不会让提示词无限膨胀。 */
        const val MAX_EXAMPLES = 200

        /** 复用历史回复的相似度阈值：低于此值宁可重新生成。 */
        const val DEFAULT_REUSE_SCORE = 0.8
    }
}