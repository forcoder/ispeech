package com.ispeech.data

import android.content.Context
import com.ispeech.engine.QuickEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** csBaby 同步仓库：拉取远端关键词规则并落盘为本地短语文件。 */
object SyncRepository {

    /** 远端规则文件名（与内置 quick_phrases.txt 同格式）。 */
    const val REMOTE_RULES_FILE_NAME = "quick_phrases_remote.txt"

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
                .append('a')
                .append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
    }
}
