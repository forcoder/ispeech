package com.ispeech.engine

import android.content.Context
import android.util.Log
import com.ispeech.data.CellDictStore
import com.ispeech.data.UserDictStore
import com.ispeech.data.UserDictMode
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 快捷规则文件定位：内置 assets 规则、远端同步规则、用户自定义规则。 */
object QuickLocator {
    private const val TAG = "QuickLocator"

    /** 内置规则文件名（assets 目录下）。 */
    const val FILE_NAME = "quick_phrases.txt"

    /** 搜狗拼音内置常用语文件名（assets 目录下，由 tools/import/import_sogou_quick_phrases.py 生成）。 */
    const val SOGOU_PHRASES_FILE_NAME = "sogou_phrases.txt"

    /** 远端同步规则文件名（filesDir 目录下）。 */
    const val REMOTE_FILE_NAME = "quick_phrases_remote.txt"

    /** 用户自定义规则文件名（filesDir 目录下）。 */
    const val USER_FILE_NAME = "quick_phrases_user.txt"

    /** 打开内置快捷规则（assets/quick_phrases.txt），失败返回 null。 */
    fun openLocal(context: Context): InputStream? = try {
        context.assets.open(FILE_NAME)
    } catch (e: Exception) {
        null
    }

    /** 打开搜狗拼音内置常用语（assets/sogou_phrases.txt），失败返回 null。 */
    fun openSogouPhrases(context: Context): InputStream? = try {
        context.assets.open(SOGOU_PHRASES_FILE_NAME)
    } catch (e: Exception) {
        null
    }

    /** 打开远端同步规则（filesDir/quick_phrases_remote.txt），文件不存在或打开失败返回 null。 */
    fun openRemote(context: Context): InputStream? {
        val file = File(context.filesDir, REMOTE_FILE_NAME)
        if (!file.exists()) return null
        return try {
            FileInputStream(file)
        } catch (e: Exception) {
            null
        }
    }

    /** 打开用户自定义规则（filesDir/quick_phrases_user.txt），文件不存在或打开失败返回 null。 */
    fun openUser(context: Context): InputStream? {
        val file = File(context.filesDir, USER_FILE_NAME)
        if (!file.exists()) return null
        return try {
            FileInputStream(file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建复合引擎：五笔 + 内置快捷规则 + 远端同步规则 + 用户自定义规则。
     *
     * 各引擎通过开关控制是否并入；minTriggerLength 控制单字母防误触阈值。
     *
     * [userDict] 非空时把用户词典引擎放在最前：它同时是联想源，且候选同权重时
     * 优先于系统词库（CompositeEngine 按 display 去重，先入者胜）。
     * [userDictMode] 决定读用户词库的哪一半（五笔态 `wb` / 拼音态 `cn`）。
     *
     * [pinyin] 为真时把拼音引擎挂在最后：拼音候选在 [LookupKind.PINYIN] 档位，
     * 排在五笔单字 / 词组之后，保证五笔永远优先，拼音只做「打不出五笔码」时的兜底。
     *
     * [cellDict] 非空时把已启用的分类词库（细胞词库）并进候选：它是「装进来的词」，
     * 权重 8 万——排在用户学到的词之后、系统词库之前；模式同样由 [userDictMode] 决定
     * （五笔态取 wb 码词条、拼音态取 cn 码词条）。
     */
    fun buildComposite(
        context: Context,
        includeLocal: Boolean = true,
        includeRemote: Boolean = true,
        includeUser: Boolean = true,
        minTriggerLength: Int = 2,
        userDict: UserDictStore? = null,
        userDictMode: UserDictMode = UserDictMode.WUBI,
        pinyin: Boolean = false,
        fuzzyPinyin: Boolean = false,
        includeCellDicts: Boolean = true,
        cellDict: CellDictStore? = null,
    ): CompositeEngine {
        val engines = ArrayList<LookupEngine>()

        if (userDict != null) {
            engines.add(UserDictEngine(userDict, userDictMode))
        }

        if (includeCellDicts && cellDict != null) {
            val cells = cellDict.entries(enabledOnly = true)
            if (cells.isNotEmpty()) engines.add(CellDictEngine(cells, userDictMode))
        }

        WubiLocator.openDict(context)?.use { input ->
            val wubi = WubiEngine(input.readTextUtf8())
            if (wubi.entryCount > 0) engines.add(wubi)
        }

        if (pinyin) {
            val dict = PinyinDictLoader.load(context)
            if (!dict.isEmpty) {
                engines.add(
                    PinyinEngine(dict, PinyinSettings(fuzzy = fuzzyPinyin, kind = LookupKind.PINYIN))
                )
            }
        }

        engines.addAll(
            buildQuickEngines(
                context,
                includeLocal = includeLocal,
                includeRemote = includeRemote,
                includeUser = includeUser,
                minTriggerLength = minTriggerLength,
            )
        )

        return CompositeEngine(engines)
    }

    /**
     * 构建快捷短语 / 快速应用引擎列表（内置 → 搜狗内置常用语 → 远端 → 用户）。
     *
     * 五笔态与拼音态必须共用同一份快捷规则：拼音态若只挂拼音词表，用户设置的
     * 「快捷字母」会在默认的拼音态下静默失效（输入 `ytyc` 永远没有候选）。
     */
    fun buildQuickEngines(
        context: Context,
        includeLocal: Boolean = true,
        includeRemote: Boolean = true,
        includeUser: Boolean = true,
        minTriggerLength: Int = 2,
    ): List<LookupEngine> {
        val engines = ArrayList<LookupEngine>(4)
        if (includeLocal) {
            openLocal(context)?.use { input ->
                QuickPhraseEngine.fromText(input.readTextUtf8(), minTriggerLength = minTriggerLength)
                    ?.let { engines.add(it) }
            }
            // 搜狗拼音内置常用语（诗词 / 网络常用语 + 快捷触发码）与内置规则同属内置档
            openSogouPhrases(context)?.use { input ->
                QuickPhraseEngine.fromText(input.readTextUtf8(), minTriggerLength = minTriggerLength)
                    ?.let { engines.add(it) }
            }
        }

        if (includeRemote) {
            openRemote(context)?.use { input ->
                QuickPhraseEngine.fromText(input.readTextUtf8(), minTriggerLength = minTriggerLength)
                    ?.let { engines.add(it) }
            }
        }

        if (includeUser) {
            openUser(context)?.use { input ->
                QuickPhraseEngine.fromText(input.readTextUtf8(), minTriggerLength = minTriggerLength)
                    ?.let { engines.add(it) }
            }
        }
        return engines
    }

    /**
     * 读取内置 / 远端 / 用户三个规则文件，解析后给每条 [QuickEntry] 打上来源标签
     * （[QuickSource.BUILT_IN] / [QuickSource.REMOTE] / [QuickSource.USER]）并合并返回。
     *
     * 单个文件缺失、读取失败或解析异常都直接跳过，不抛异常；顺序为内置 → 远端 → 用户。
     */
    fun loadAll(
        context: Context,
        includeLocal: Boolean = true,
        includeRemote: Boolean = true,
        includeUser: Boolean = true,
        minTriggerLength: Int = 2,
    ): List<QuickEntry> {
        val out = ArrayList<QuickEntry>()
        if (includeLocal) {
            loadInto(out, openLocal(context), QuickSource.BUILT_IN, minTriggerLength)
            loadInto(out, openSogouPhrases(context), QuickSource.BUILT_IN, minTriggerLength)
        }
        if (includeRemote) {
            loadInto(out, openRemote(context), QuickSource.REMOTE, minTriggerLength)
        }
        if (includeUser) {
            loadInto(out, openUser(context), QuickSource.USER, minTriggerLength)
        }
        return out
    }

    /** 解析单个规则流并打上来源标签，追加到 [out]；失败静默跳过。 */
    private fun loadInto(
        out: MutableList<QuickEntry>,
        input: InputStream?,
        source: String,
        minTriggerLength: Int,
    ): Unit {
        if (input == null) return
        runCatching {
            input.use { stream ->
                val engine = QuickPhraseEngine.fromText(
                    stream.readTextUtf8(),
                    minTriggerLength = minTriggerLength,
                ) ?: return@use
                for (entry in engine.entries) {
                    out.add(entry.copy(source = source))
                }
            }
        }.onFailure { Log.w(TAG, "读取快捷规则失败: $source", it) }
    }
}

/** 以 UTF-8 读入整个输入流的文本。 */
private fun InputStream.readTextUtf8(): String = readBytes().toString(Charsets.UTF_8)
