package com.ispeech.data

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 「通用短语」CSV 导入结果：成功映射出的规则 + 各类跳过计数。
 *
 * 跳过计数全部如实上报（设置页提示条按项展示），避免导入时静默丢数据：
 * 用户拿到的条数必须能对上 CSV 的行数。
 */
data class PhraseCsvImportResult(
    /** 可写入 quick_phrases_user.txt 的规则（trigger 已转小写、内容已规范化）。 */
    val entries: List<QuickEntry> = emptyList(),
    /** 实际处理的数据行数（不含表头与空行）。 */
    val totalRows: Int = 0,
    /** INPUT_CODE 为空：这一步没有快捷字母可打，跳过。 */
    val skippedNoCode: Int = 0,
    /** INPUT_CODE 不是纯小写字母（或超过 [PhraseCsvImport.MAX_TRIGGER_LENGTH] 位）：跳过。 */
    val skippedBadCode: Int = 0,
    /** 同一个快捷字母出现多次：用户规则文件按 trigger 唯一，只保留首条。 */
    val skippedDuplicateCode: Int = 0,
    /** IS_DELETE 非 0：源系统里已删除的条目。 */
    val skippedDeleted: Int = 0,
    /** CONTENT 为空：没有可上屏的内容。 */
    val skippedEmptyContent: Int = 0,
    /** 快捷字母超过 4 位（五笔态组合缓冲上限）：仍导入，打前 4 位即可前缀命中。 */
    val longCodeCount: Int = 0,
    /** 快捷字母只有 1 位：只能精确匹配，会与五笔一级简码同码竞争。 */
    val singleLetterCount: Int = 0,
    /** 表头缺失等致命问题；非 null 时 [entries] 必为空。 */
    val formatError: String? = null,
) {
    /** 被跳过的行数合计（不含 [longCodeCount] / [singleLetterCount] 这类「已导入但需提示」）。 */
    val skippedCount: Int
        get() = skippedNoCode + skippedBadCode + skippedDuplicateCode +
            skippedDeleted + skippedEmptyContent
}

/**
 * 通用短语 CSV 导入器。
 *
 * 对应导出源（搜狗输入法 / csBaby 客户端的「常用语」导出）的 10 列表头：
 * `UUID, GROUP_NAME, CATEGORY_TYPE, CONTENT, INPUT_CODE, CANDIDATE_INDEX, IS_DELETE,
 * ORDER_TIMESTAMP, UPDATE_TIMESTAMP, SYNC_STATUS`。
 * 只用得到 `INPUT_CODE`（快捷字母）、`CONTENT`（上屏内容）、`GROUP_NAME`（分组，仅作兜底名称）
 * 与 `IS_DELETE`（删除标记）；其余列忽略，列顺序不敏感（按表头定位）。
 *
 * 解析要点（这几种情况真实数据里都存在，缺一条就会丢短语）：
 * - **编码**：导出文件是 GBK（Excel 另存），也可能是带 BOM 的 UTF-8。先按严格 UTF-8 解，
 *   解不开再按 GBK 解，见 [decode]。
 * - **字段内换行**：117 条内容含换行，字段用双引号包裹，必须按 RFC 4180 逐字符解析，
 *   不能按行 split（否则长文本会被拆成多行、后续列全部错位）。
 * - **换行规范化**：源文件是 CRLF，字段内还夹着孤立的 CR（`\r\r\n`）；统一成 `\n`，
 *   去掉行尾空白与多余空行，再交给 [QuickPhraseCodec] 以字面量 `\n` 落盘。
 *
 * 映射到 [QuickEntry] 的口径：
 * - `trigger` = INPUT_CODE 小写；`label` = 内容首个非空行截断到 20 个码点（候选条只显示
 *   label，长文本必须有可辨识的短名；重名时追加 `·2`、`·3`，避免 `CompositeEngine`
 *   按 display 去重时把不同短语吞成一条）；
 * - `content` = CONTENT；`kind` = [LookupKind.PHRASE]；`weight` = [DEFAULT_WEIGHT]；
 * - `mode`：1 位字母用 [QuickMatchMode.EXACT]（prefix 在「单字母不触发」下永远不会命中），
 *   其余用 [QuickMatchMode.PREFIX]（2–4 位可以完整输入并拿到「精确码优先」的首位加成；
 *   5 位以上在五笔态打前 4 位即可前缀命中，拼音态可以打全）。
 *
 * 已知边界：落盘格式只转义 `\r` / `\n` / `\t`，内容里若出现字面量 `\n`、`\t`、
 * 反斜杠 + n 之类的字符序列，回读时会被 [unescapeLineText] 当成转义序列还原。
 */
object PhraseCsvImport {

    /** 候选条上短名的最大码点数（超过截断并加省略号）。 */
    private const val LABEL_MAX_CODE_POINTS = 20

    /** 快捷字母长度上限：拼音态组合缓冲 12 位，再长的码打不出来就不导入了。 */
    const val MAX_TRIGGER_LENGTH = 12

    /** 五笔态组合缓冲上限；超过这个长度的快捷字母只能靠前缀命中。 */
    private const val WUBI_MAX_CODE_LENGTH = 4

    /** 导入规则的默认权重：与新建规则的默认值一致，不压过用户手调过的规则。 */
    private const val DEFAULT_WEIGHT = 100

    /** 快捷字母：纯小写字母，长度 1..[MAX_TRIGGER_LENGTH]。 */
    private val TRIGGER_PATTERN = Regex("[a-z]{1,$MAX_TRIGGER_LENGTH}")

    /** 表头别名：不同导出工具的列名都能认（英文大小写 / 空格 / 中文都归一化）。 */
    private val CODE_HEADERS = listOf("input_code", "code", "keyword", "trigger", "mnemonic", "简写", "快捷字母", "编码")
    private val CONTENT_HEADERS = listOf("content", "reply_template", "reply", "text", "内容", "短语内容")
    private val GROUP_HEADERS = listOf("group_name", "group", "category", "分组", "分组名称")
    private val DELETED_HEADERS = listOf("is_delete", "deleted", "is_deleted", "已删除")

    /**
     * 把 CSV 字节解码成文本：先按严格 UTF-8（会抛异常的那种）解，失败再按 GBK 解。
     *
     * 不能直接用 `String(bytes, UTF_8)` 再猜：那样 GBK 中文会被替换成 ``，导入后内容全毁。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            String(body, gbkCharset())
        }
    }

    /** GBK 在 Android / 桌面上都有；极端环境下退回 UTF-8，至少不抛异常。 */
    private fun gbkCharset(): Charset =
        runCatching { Charset.forName("GBK") }.getOrElse { Charsets.UTF_8 }

    /** 解析 CSV 文本；格式不对时 [PhraseCsvImportResult.formatError] 非空。 */
    fun parse(text: String): PhraseCsvImportResult {
        val table = CsvTable.parse(text)
        if (table.isEmpty()) return PhraseCsvImportResult(formatError = "CSV 为空")

        val header = table.first().map(::normalizeHeader)
        val codeIndex = columnOf(header, CODE_HEADERS)
        val contentIndex = columnOf(header, CONTENT_HEADERS)
        if (codeIndex == null || contentIndex == null) {
            val shown = table.first().joinToString(",") { it.trim() }
            return PhraseCsvImportResult(
                formatError = "缺少 INPUT_CODE（快捷字母）或 CONTENT（内容）列（表头：$shown）"
            )
        }
        val groupIndex = columnOf(header, GROUP_HEADERS)
        val deletedIndex = columnOf(header, DELETED_HEADERS)

        var totalRows = 0
        var noCode = 0
        var badCode = 0
        var duplicate = 0
        var deleted = 0
        var emptyContent = 0
        var longCodes = 0
        var singleLetter = 0

        val seenTriggers = HashSet<String>()
        val labelCounter = HashMap<String, Int>()
        val out = ArrayList<QuickEntry>()

        for (row in table.subList(1, table.size)) {
            if (row.all { it.isBlank() }) continue
            totalRows++

            if (isDeleted(cell(row, deletedIndex))) {
                deleted++
                continue
            }
            val content = normalizeContent(cell(row, contentIndex).orEmpty())
            if (content.isEmpty()) {
                emptyContent++
                continue
            }
            val rawCode = cell(row, codeIndex).orEmpty().trim().lowercase()
            if (rawCode.isEmpty()) {
                noCode++
                continue
            }
            if (!TRIGGER_PATTERN.matches(rawCode)) {
                badCode++
                continue
            }
            // 用户规则文件按 trigger 唯一（QuickPhraseCodec.parse 后写覆盖前），
            // 同码只保留 CSV 里靠前的一条（ORDER_TIMESTAMP 倒序，靠前 = 更新）。
            if (!seenTriggers.add(rawCode)) {
                duplicate++
                continue
            }
            val group = cell(row, groupIndex).orEmpty().trim()
            val label = uniqueLabel(labelOf(content, group), labelCounter)
            if (rawCode.length > WUBI_MAX_CODE_LENGTH) longCodes++
            if (rawCode.length == 1) singleLetter++
            out.add(
                QuickEntry(
                    trigger = rawCode,
                    label = label,
                    content = content,
                    weight = DEFAULT_WEIGHT,
                    mode = if (rawCode.length == 1) QuickMatchMode.EXACT else QuickMatchMode.PREFIX,
                    kind = LookupKind.PHRASE,
                )
            )
        }

        return PhraseCsvImportResult(
            entries = out,
            totalRows = totalRows,
            skippedNoCode = noCode,
            skippedBadCode = badCode,
            skippedDuplicateCode = duplicate,
            skippedDeleted = deleted,
            skippedEmptyContent = emptyContent,
            longCodeCount = longCodes,
            singleLetterCount = singleLetter,
        )
    }

    /** 表头归一化：去 BOM / 引号残留、转小写、空格与连字符转下划线。 */
    private fun normalizeHeader(raw: String): String =
        raw.removePrefix("\uFEFF")
            .trim()
            .trim('"')
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')

    /** 按别名表定位列下标；表头顺序不敏感。 */
    private fun columnOf(header: List<String>, aliases: List<String>): Int? {
        for (alias in aliases) {
            val index = header.indexOf(alias)
            if (index >= 0) return index
        }
        return null
    }

    /** 读取单元格；越界（行短于表头）按空串处理。 */
    private fun cell(row: List<String>, index: Int?): String? =
        if (index != null && index >= 0 && index < row.size) row[index] else null

    /** IS_DELETE：0 / false / 空 视为有效，其余（1 / true / y）视为已删除。 */
    private fun isDeleted(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase() ?: return false
        return value.isNotEmpty() && value != "0" && value != "false" && value != "no"
    }

    /**
     * 内容规范化：CRLF / 孤立 CR → `\n`，逐行去行尾空白，去首尾空行，连续空行最多留一行。
     *
     * `\r\r\n`（源文件里的孤立 CR + CRLF）会变成两个换行，正好对应原文的段落间隔。
     */
    internal fun normalizeContent(raw: String): String {
        if (raw.isEmpty()) return ""
        val unified = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = unified.split('\n').map { it.trimEnd(' ', '\t') }
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isBlank()) start++
        while (end > start && lines[end - 1].isBlank()) end--
        val out = StringBuilder()
        var blankRun = 0
        for (i in start until end) {
            val line = lines[i]
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
            } else {
                blankRun = 0
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        // 首行的前导空白对候选 / 上屏都没意义，而且 QuickPhraseCodec.parse 会 trim 字段，
        // 留着它落盘后就再也读不回来（导入不能悄悄改内容）。
        return out.toString().trimStart(' ', '\t')
    }

    /** 候选条短名：内容首个非空行（截断），没有内容行时用分组名兜底。 */
    internal fun labelOf(content: String, group: String = ""): String {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val base = firstLine.ifEmpty { group }
        return truncate(base, LABEL_MAX_CODE_POINTS)
    }

    /** 按码点截断（不能按 UTF-16 char 截，会把 emoji 劈成半个代理对）。 */
    private fun truncate(value: String, maxCodePoints: Int): String {
        if (value.codePointCount(0, value.length) <= maxCodePoints) return value
        val end = value.offsetByCodePoints(0, maxCodePoints)
        return value.substring(0, end) + "…"
    }

    /**
     * 短名去重：同一条内容的首行相同（源系统里常见：同一模板的多个版本）时追加 `·2`、`·3`。
     *
     * 必须保证唯一，否则 `CompositeEngine` 按 `display` 去重会把后来的短语静默丢掉。
     */
    private fun uniqueLabel(base: String, counter: MutableMap<String, Int>): String {
        val label = base.ifEmpty { "短语" }
        val count = (counter[label] ?: 0) + 1
        counter[label] = count
        return if (count == 1) label else "$label·$count"
    }
}

/**
 * 最小 RFC 4180 CSV 解析：支持双引号字段、字段内换行、`""` 转义的双引号。
 *
 * 只在导入路径使用，不做流式解析：235 行 / 61KB 的源文件整读进内存足够，
 * 而「字段内换行」恰恰是这里必须支持的（长文本字段 117 条）。
 */
private object CsvTable {

    fun parse(text: String): List<List<String>> {
        if (text.isEmpty()) return emptyList()
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldStarted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    // 双引号转义："" -> "
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    quoted = false
                    i++
                    continue
                }
                field.append(ch)
                i++
                continue
            }
            when {
                ch == '"' && !fieldStarted -> {
                    quoted = true
                    fieldStarted = true
                    i++
                }
                // 字段中间出现的裸引号：按字面保留，不让一行坏数据吞掉后面整表
                ch == '"' -> {
                    field.append(ch)
                    i++
                }
                ch == ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    fieldStarted = false
                    i++
                }
                ch == '\r' || ch == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    fieldStarted = false
                    rows.add(row)
                    row = ArrayList()
                    i += if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                }
                else -> {
                    field.append(ch)
                    fieldStarted = true
                    i++
                }
            }
        }
        // 文件末尾没有换行时补最后一格
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}