package com.ispeech.data

import android.util.Log
import java.io.File

/** 分类词库（细胞词库）词条：code 为输入码（五笔码 / 拼音串），text 为上屏词条，mode 为所属输入模式。 */
data class CellDictEntry(val code: String, val text: String, val mode: UserDictMode)

/** 分类词库元信息：id / 显示名 / 分类 / 词条数 / 是否启用。 */
data class CellDictInfo(
    val id: String,
    val name: String,
    val category: String,
    val entryCount: Int,
    val enabled: Boolean,
)

/** 安装分类词库的结果：写入词条数 / 跳过的行数 / 是否覆盖了同一份旧词库。 */
data class CellDictImportResult(
    val id: String,
    val name: String,
    val category: String,
    val entries: Int,
    val skipped: Int,
    val replaced: Boolean,
)

/**
 * 索引行：`id<TAB>显示名<TAB>分类<TAB>启用(1/0)`。
 *
 * 列数不足、id 为空、启用位不是 0/1 的行一律返回 null（当作孤儿行跳过）；
 * 显示名 / 分类复用 [unescapeLineText] 还原转义。
 */
internal fun parseCellDictIndexLine(line: String): CellDictInfo? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val fields = trimmed.split('\t')
    if (fields.size < 4) return null
    val id = fields[0].trim()
    if (id.isEmpty()) return null
    val enabled = when (fields[3].trim()) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    return CellDictInfo(
        id = id,
        name = unescapeLineText(fields[1]).trim(),
        category = unescapeLineText(fields[2]).trim(),
        entryCount = 0,
        enabled = enabled,
    )
}

/**
 * 从词库正文里读分类：认 `# category: 医学` / `#category 医学` 这类注释头，
 * 读不到返回 null（界面回落到「自定义」文案）。
 */
internal fun parseCellDictCategory(text: String): String? {
    for (line in text.split('\n')) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#")) continue
        val body = trimmed.removePrefix("#").trim()
        if (!body.lowercase().startsWith(CATEGORY_KEY)) continue
        val rest = body.substring(CATEGORY_KEY.length).trim().trimStart(':', '：').trim()
        if (rest.isEmpty()) continue
        return rest.take(32)
    }
    return null
}

/**
 * 词库稳定 id：`c1-` + FNV-1a64(显示名 + \u0001 + 正文)。
 *
 * 同一份词库重复安装会落到同一 id（视为覆盖），不同词库几乎不会撞号；
 * 与 [PhraseUsageStore] 的 `p1-` 前缀一样，一眼能看出 id 的类型。
 */
internal fun cellDictId(name: String, content: String): String =
    "c1-" + fnv1a64(name + "\u0001" + content)

private const val CATEGORY_KEY = "category"

private val FNV_OFFSET_BASIS = "cbf29ce484222325".toULong(16)
private val FNV_PRIME = "100000001b3".toULong(16)

/** FNV-1a 64 位散列（UTF-8 字节序），返回 16 位小写十六进制。 */
private fun fnv1a64(text: String): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
    }
    return hash.toString(16).padStart(16, '0')
}

/**
 * 分类词库（细胞词库）存储：`filesDir/cell_dicts/` 下一个词库一个正文文件，
 * `filesDir/cell_dicts/index.txt` 记录「id / 显示名 / 分类 / 启用」。
 *
 * 对齐搜狗 mac 版的 `InitializeWithMode:CateDictArray:` + `pfnAddDict` / `pfnDelDict`：
 * 支持本地文本词库的安装、启停与删除。词库正文行格式与用户词库完全一致
 * （复用 [parseUserDictLine]，`code<TAB>词条[<TAB>次数][<TAB>wb|cn]`），
 * 另认一行可选的 `# category: 分类名` 头；正文原样落盘，便于再导出与手工编辑。
 *
 * 所有 IO 都容错（`runCatching` + `Log.w`），绝不抛异常；公开方法全部 `@Synchronized`。
 */
class CellDictStore private constructor(private val dir: File) {

    private val indexFile = File(dir, INDEX_FILE_NAME)

    /** id -> 元信息，插入顺序即设置页展示顺序。 */
    private val metas = LinkedHashMap<String, CellDictInfo>()

    /** id -> 已解析词条（正文只在安装 / 读盘时解析一次）。 */
    private val entryCache = HashMap<String, List<CellDictEntry>>()

    private var loaded = false

    companion object {
        private const val TAG = "CellDictStore"

        /** 索引文件名（位于 cell_dicts 目录下）。 */
        const val INDEX_FILE_NAME = "index.txt"

        /** 索引文件头注释行。 */
        private const val HEADER = "# 分类词库（细胞词库）索引（自动生成，可在设置页管理）"

        /** 打开（目录不存在则创建，读写均容错，绝不抛异常）。 */
        fun open(dir: File): CellDictStore {
            val store = CellDictStore(dir)
            runCatching { dir.mkdirs() }
                .onFailure { Log.w(TAG, "创建分类词库目录失败: ${dir.path}", it) }
            return store
        }
    }

    /** 已安装词库列表（安装顺序），词条数按正文统计。 */
    @Synchronized
    fun list(): List<CellDictInfo> {
        ensureLoaded()
        return metas.values.map { it.copy(entryCount = entriesOf(it.id).size) }
    }

    /** 全部词条；[enabledOnly] 为真时只返回已启用词库里的词条。 */
    @Synchronized
    fun entries(enabledOnly: Boolean = true): List<CellDictEntry> {
        ensureLoaded()
        val out = ArrayList<CellDictEntry>()
        for (meta in metas.values) {
            if (enabledOnly && !meta.enabled) continue
            out.addAll(entriesOf(meta.id))
        }
        return out
    }

    /**
     * 安装一份词库正文：正文原样写入 `<id>.txt`，索引里记显示名 / 分类 / 启用位。
     *
     * 同名同正文重复安装命中同一 id，按覆盖处理（保留原有启用状态）。
     */
    @Synchronized
    fun add(name: String, content: String): CellDictImportResult {
        ensureLoaded()
        val parsed = parseCellDictEntries(content)
        val base = sanitizeName(name)
        val id = cellDictId(base, content)
        val cleanName = base.ifEmpty { "dict-" + id.substringAfter('-').take(8) }
        val category = parseCellDictCategory(content).orEmpty()
        val previous = metas[id]
        runCatching { fileOf(id).writeText(content, Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "写入分类词库失败: ${fileOf(id).path}", it) }
        metas[id] = CellDictInfo(
            id = id,
            name = cleanName,
            category = category,
            entryCount = parsed.first.size,
            enabled = previous?.enabled ?: true,
        )
        entryCache[id] = parsed.first
        persist()
        return CellDictImportResult(id, cleanName, category, parsed.first.size, parsed.second, previous != null)
    }

    /** 删除词库（正文 + 索引项），返回是否删除成功。 */
    @Synchronized
    fun remove(id: String): Boolean {
        ensureLoaded()
        if (metas.remove(id) == null) return false
        entryCache.remove(id)
        runCatching { fileOf(id).delete() }
        persist()
        return true
    }

    /** 启用 / 停用词库，返回是否命中该词库。 */
    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        ensureLoaded()
        val meta = metas[id] ?: return false
        if (meta.enabled != enabled) {
            metas[id] = meta.copy(enabled = enabled)
            persist()
        }
        return true
    }

    /** 正文 → 词条（复用用户词库的行解析），返回词条 + 跳过行数。 */
    private fun parseCellDictEntries(content: String): Pair<List<CellDictEntry>, Int> {
        val out = ArrayList<CellDictEntry>()
        var skipped = 0
        for (rawLine in content.split('\n')) {
            val parsed = parseUserDictLine(rawLine, UserDictMode.WUBI)
            if (parsed == null) {
                if (rawLine.isNotBlank() && !rawLine.trim().startsWith("#")) skipped++
                continue
            }
            out.add(CellDictEntry(parsed.code, parsed.text, parsed.mode))
        }
        return out to skipped
    }

    /** 按 id 读词条，正文缺失 / 损坏一律当空，结果缓存在内存里。 */
    private fun entriesOf(id: String): List<CellDictEntry> {
        entryCache[id]?.let { return it }
        val parsed = runCatching {
            val file = fileOf(id)
            if (!file.exists()) {
                emptyList<CellDictEntry>()
            } else {
                parseCellDictEntries(file.readText(Charsets.UTF_8)).first
            }
        }.getOrDefault(emptyList())
        entryCache[id] = parsed
        return parsed
    }

    /** 懒加载：索引文件缺失 / 损坏一律当空，不抛异常。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            dir.mkdirs()
            if (!indexFile.exists()) return
            for (rawLine in indexFile.readText(Charsets.UTF_8).split('\n')) {
                val info = parseCellDictIndexLine(rawLine) ?: continue
                metas[info.id] = info
            }
        }.onFailure { Log.w(TAG, "读取分类词库索引失败: ${indexFile.path}", it) }
    }

    /** 写回索引：IO 异常吞掉并打日志。 */
    private fun persist() {
        runCatching {
            dir.mkdirs()
            val sb = StringBuilder()
            sb.append(HEADER).append('\n')
            for (meta in metas.values) {
                sb.append(meta.id).append('\t')
                    .append(escapeLineText(meta.name)).append('\t')
                    .append(escapeLineText(meta.category)).append('\t')
                    .append(if (meta.enabled) "1" else "0").append('\n')
            }
            indexFile.writeText(sb.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写入分类词库索引失败: ${indexFile.path}", it) }
    }

    /** 正文文件路径：`<id>.txt`（id 只含 ASCII 字母 / 数字 / 连字符，可安全当文件名）。 */
    private fun fileOf(id: String): File = File(dir, "$id.txt")

    /** 显示名清洗：去首尾空白、压平制表符 / 换行、截断 64 字符。 */
    private fun sanitizeName(raw: String): String =
        raw.trim().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(64).trim()
}
