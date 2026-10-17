package com.ispeech.data

import android.util.Log
import java.io.File

/** 剪贴板历史：filesDir/clipboard_history.txt，每行一条（换行/制表符转义存储），最近使用的在前。 */
class ClipboardHistory private constructor(private val file: File) {

    private val entries = ArrayList<String>()

    /** 懒加载标记：保证首次访问才读盘。 */
    private var loaded = false

    companion object {
        const val MAX_ITEMS = 50

        private const val TAG = "ClipboardHistory"

        /** 单条最长字符数，超出截断。 */
        private const val MAX_TEXT_LENGTH = 2000

        fun open(file: File): ClipboardHistory = ClipboardHistory(file)
    }

    /** 历史条目，最近使用的在前。 */
    @Synchronized
    fun items(): List<String> {
        ensureLoaded()
        return ArrayList(entries)
    }

    /** 记录一条剪贴板文本：空白忽略、去重提前、超长截断、最多 [MAX_ITEMS] 条。 */
    @Synchronized
    fun record(text: String) {
        val value = text.take(MAX_TEXT_LENGTH)
        if (value.isBlank()) return
        ensureLoaded()
        entries.remove(value)
        entries.add(0, value)
        while (entries.size > MAX_ITEMS) {
            entries.removeAt(entries.size - 1)
        }
        persist()
    }

    /** 删除某条历史。 */
    @Synchronized
    fun remove(text: String) {
        ensureLoaded()
        val truncated = text.take(MAX_TEXT_LENGTH)
        var changed = entries.remove(truncated)
        if (!changed && truncated != text) changed = entries.remove(text)
        if (changed) persist()
    }

    /** 清空历史。 */
    @Synchronized
    fun clear() {
        ensureLoaded()
        entries.clear()
        persist()
    }

    /** 懒加载：文件缺失 / 损坏一律当空，不抛异常。 */
    private fun ensureLoaded(): Unit {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            for (rawLine in file.readText(Charsets.UTF_8).split('\n')) {
                if (rawLine.isEmpty() || rawLine.startsWith("#")) continue
                // 写出时给以 # 开头的文本加了 "\\" 前缀，读回时去掉再反转义。
                val body = if (rawLine.startsWith("\\#")) rawLine.substring(1) else rawLine
                val value = unescapeLineText(body)
                if (value.isBlank()) continue
                if (entries.size >= MAX_ITEMS) break
                if (!entries.contains(value)) entries.add(value)
            }
        }.onFailure { Log.w(TAG, "读取剪贴板历史失败: ${file.path}", it) }
    }

    /** 写回文件：IO 异常吞掉并打日志。 */
    private fun persist(): Unit {
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            for (entry in entries) {
                val line = escapeLineText(entry)
                // 以 # 开头的文本会被当成注释行，加反斜杠前缀防止读回时丢失。
                sb.append(if (line.startsWith("#")) "\\$line" else line).append('\n')
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写入剪贴板历史失败: ${file.path}", it) }
    }
}
