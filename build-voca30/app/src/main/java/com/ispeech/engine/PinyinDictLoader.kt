package com.ispeech.engine

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * 拼音词条：code 为「空格分隔的音节串」（如 `ni hao`），text 为上屏文本。
 *
 * [full] / [initials] 是给检索用的派生键，解析时一次性算好，避免查询期重复拼接。
 */
data class PinyinEntry(
    val code: String,
    val text: String,
    val weight: Int,
) {
    /** 无空格全拼，`ni hao` → `nihao`。 */
    val full: String = code.replace(" ", "")

    /** 简拼（每个音节首字母），`ni hao` → `nh`。 */
    val initials: String = buildString(code.length) {
        for (syllable in code.split(' ')) {
            if (syllable.isNotEmpty()) append(syllable[0])
        }
    }

    /** 是否为词组（多于一个汉字）。 */
    val isWord: Boolean = text.length > 1
}

/**
 * 拼音词典：不可变的词条列表 + 统计信息。
 *
 * [EMPTY] 表示 assets 缺失 / 解析失败，此时拼音引擎整体停用而不是抛异常。
 */
class PinyinDict(val entries: List<PinyinEntry>) {

    /** 词条总数（单字多音字算多条）。 */
    val size: Int = entries.size

    /** 单字词条数。 */
    val charCount: Int = entries.count { !it.isWord }

    /** 词组词条数。 */
    val wordCount: Int = entries.count { it.isWord }

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        /** 空词典：加载失败时的返回值。 */
        val EMPTY = PinyinDict(emptyList())
    }
}

/**
 * 拼音词典加载器：assets/pinyin86.dict → [PinyinDict]。
 *
 * 码表行格式（详见 docs/pinyin-data.md）：
 * ```
 * code <TAB> text <TAB> weight
 * ```
 * - `code`：小写 a-z 音节，音节之间单个空格；ü 写作 v
 * - `text`：汉字（单字或词语）
 * - `weight`：1..9999，对数压缩词频，仅用于同层排序
 *
 * 空行与 `#` 开头的注释行跳过；任何读取 / 解析异常都返回 [PinyinDict.EMPTY]，
 * 保证输入法不会因为词典问题崩溃。
 */
object PinyinDictLoader {

    private const val TAG = "PinyinDictLoader"

    /** 码表文件名（assets 目录下）。 */
    const val FILE_NAME = "pinyin86.dict"

    /** 权重上限：与码表约定一致，超出部分截断。 */
    private const val MAX_WEIGHT = 65535

    /** 合法 code：小写字母音节 + 单空格分隔。 */
    private val CODE_PATTERN = Regex("^[a-z]+( [a-z]+)*$")

    /** 打开内置拼音码表，失败（文件缺失等）返回 null。 */
    fun openDict(context: Context): InputStream? = try {
        context.assets.open(FILE_NAME)
    } catch (e: Exception) {
        null
    }

    /**
     * 从 assets 加载拼音码表；文件缺失、读取失败或解析异常时返回 [PinyinDict.EMPTY]。
     *
     * 会读取约 1 MB 文本并建立索引，建议在后台线程调用一次后缓存实例。
     */
    fun load(context: Context): PinyinDict = try {
        openDict(context)?.use { input ->
            parse(input.readBytes().toString(Charsets.UTF_8))
        } ?: PinyinDict.EMPTY
    } catch (e: Exception) {
        Log.w(TAG, "加载 $FILE_NAME 失败，拼音引擎停用", e)
        PinyinDict.EMPTY
    }

    /** 解析码表文本；不抛异常，非法行直接跳过。 */
    fun parse(text: String): PinyinDict {
        if (text.isEmpty()) return PinyinDict.EMPTY

        val entries = ArrayList<PinyinEntry>(estimatedCount(text.length))
        val seen = HashSet<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val fields = line.split('\t')
            if (fields.size < 2) continue
            val code = fields[0].trim().lowercase()
            val word = fields[1].trim()
            if (word.isEmpty() || !CODE_PATTERN.matches(code)) continue

            val weight = if (fields.size >= 3) {
                fields[2].toIntOrNull()?.coerceIn(0, MAX_WEIGHT) ?: 0
            } else {
                0
            }
            // 同一 (code, text) 只保留一条；同一 text 的不同读音（如「长」chang/zhang）都保留
            if (!seen.add("$code\u0000$word")) continue
            entries.add(PinyinEntry(code = code, text = word, weight = weight))
        }

        if (entries.isEmpty()) return PinyinDict.EMPTY
        return PinyinDict(entries)
    }

    /** 按文本长度粗估词条数量，避免 ArrayList 反复扩容。 */
    private fun estimatedCount(textLength: Int): Int = (textLength / 20).coerceAtLeast(16)
}
