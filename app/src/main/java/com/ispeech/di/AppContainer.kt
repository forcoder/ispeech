package com.ispeech.di

import android.content.Context
import com.ispeech.data.CellDictStore
import com.ispeech.data.ClipboardHistory
import com.ispeech.data.CsBabyClient
import com.ispeech.data.PhraseUsageStore
import com.ispeech.data.UserDictStore
import com.ispeech.data.UserDictMode
import com.ispeech.engine.CompositeEngine
import com.ispeech.engine.CellDictEngine
import com.ispeech.engine.LookupEngine
import com.ispeech.engine.LookupKind
import com.ispeech.engine.PinyinDictLoader
import com.ispeech.engine.PinyinEngine
import com.ispeech.engine.PinyinSettings
import com.ispeech.engine.QuickLocator
import com.ispeech.engine.UserDictEngine
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 全局依赖容器：统一提供查询引擎、后端客户端与本地文件路径。
 *
 * - 通过 [init] 在 Application 启动时记录应用级上下文；
 * - 各方法同样接收 Context 参数，方便在 Compose / Service 中直接调用；
 * - 引擎与客户端均做懒加载缓存，保证全局单例。
 */
object AppContainer {

    /** 快捷短语用户自定义文件名（位于 filesDir 下） */
    const val QUICK_PHRASES_USER_FILE = "quick_phrases_user.txt"

    /** 用户词频 / 自造词文件名（位于 filesDir 下） */
    const val USER_DICT_FILE = "user_dict.txt"

    /** 分类词库（细胞词库）目录名（位于 filesDir 下） */
    const val CELL_DICT_DIR_NAME = "cell_dicts"

    /** 剪贴板历史文件名（位于 filesDir 下） */
    const val CLIPBOARD_HISTORY_FILE = "clipboard_history.txt"

    /** 快捷短语使用记录（收藏 / 最近 / 计数）文件名（位于 filesDir 下） */
    const val PHRASE_USAGE_FILE = "phrase_usage.txt"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var compositeEngineCache: CompositeEngine? = null

    /** 九宫格（T9）专用拼音引擎缓存，见 [pinyinEngine] */
    @Volatile
    private var pinyinEngineCache: PinyinEngine? = null

    /** 拼音态查询引擎缓存（用户词库 cn + 拼音词表），见 [pinyinLookupEngine] */
    @Volatile
    private var pinyinLookupCache: CompositeEngine? = null

    /** 引擎代次：每次 [invalidateEngine] 自增，输入法服务据此发现「设置变了」并重取引擎 */
    @Volatile
    private var engineGeneration: Int = 0

    @Volatile
    private var csBabyClientCache: CsBabyClient? = null

    @Volatile
    private var userDictCache: UserDictStore? = null

    @Volatile
    private var cellDictCache: CellDictStore? = null

    @Volatile
    private var clipboardHistoryCache: ClipboardHistory? = null

    @Volatile
    private var phraseUsageCache: PhraseUsageStore? = null

    /** 在 Application.onCreate 中调用一次，记录应用级上下文 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 已记录的应用级上下文；若未初始化则尽早抛出，便于定位问题 */
    fun applicationContext(): Context =
        appContext ?: error("AppContainer 尚未初始化，请在 IspeechApp.onCreate 中调用 init()")

    /**
     * 组合查询引擎（五笔 + 快捷短语 + 快速应用），全局懒加载缓存。
     *
     * 首次调用时读取 DataStore 设置项：是否启用快捷短语、是否启用远程规则、
     * 是否单字母不触发（控制 minTriggerLength）。
     */
    fun compositeEngine(context: Context): CompositeEngine {
        val cached = compositeEngineCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = compositeEngineCache
            if (existing != null) return existing
            val created = buildEngineFromSettings(context.applicationContext)
            compositeEngineCache = created
            return created
        }
    }

    /** 根据当前设置项构建引擎（私有）。 */
    private fun buildEngineFromSettings(ctx: Context): CompositeEngine {
        val (enableQuick, enableRemote, singleNoTrigger) = runBlocking {
            val q = SettingsStore.enableQuickPhrasesFlow(ctx).first()
            val r = SettingsStore.enableRemoteRulesFlow(ctx).first()
            val s = SettingsStore.singleLetterNoTriggerFlow(ctx).first()
            Triple(q, r, s)
        }
        val keyboard = SettingsStore.keyboardSettings(ctx)
        return QuickLocator.buildComposite(
            ctx,
            includeLocal = enableQuick,
            includeRemote = enableRemote,
            includeUser = true,
            minTriggerLength = if (singleNoTrigger) 2 else 1,
            userDict = userDict(ctx),
            cellDict = cellDict(ctx),
            pinyin = keyboard.pinyinMixed,
            fuzzyPinyin = keyboard.fuzzyPinyin,
        )
    }

    /**
     * 九宫格（T9）专用拼音引擎：数字串先展开成字母组合，再按拼音查词。
     *
     * 与 [compositeEngine] 里可选的拼音引擎分开缓存，因为九宫格语义本身就是「数字 → 拼音」，
     * 不该跟着「拼音混输」开关走（关了混输，九宫格仍然应该是拼音）。
     * 首个九宫格查询时调用（调用点在后台线程），约 1 MB 词表 + 索引只建一次。
     */
    fun pinyinEngine(context: Context): PinyinEngine {
        val cached = pinyinEngineCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = pinyinEngineCache
            if (existing != null) return existing
            val appCtx = context.applicationContext
            val created = PinyinEngine(
                PinyinDictLoader.load(appCtx),
                PinyinSettings(
                    fuzzy = SettingsStore.keyboardSettings(appCtx).fuzzyPinyin,
                    kind = LookupKind.PINYIN,
                ),
            )
            pinyinEngineCache = created
            return created
        }
    }

    /**
     * 拼音态查询引擎：用户词库（cn 模式）+ 拼音词表 + 快捷短语规则。
     *
     * 拼音态原本直接走 [pinyinEngine]，用户词库完全够不着——自造词与词频学习在拼音态写了也没用，
     * 所以输入法服务干脆禁掉了拼音态学习。搜狗把用户词库按输入模式拆成 `wb_dict_user` /
     * `cn_dict_user`，这里同理：cn 模式的用户词与拼音词表合成一个复合引擎，用户词带 +10 万权重，
     * 与拼音词同档（[LookupKind.PINYIN]）竞争时排到前面。
     *
     * 已启用的分类词库里 `cn` 码的词条同样并入（权重 8 万，排在用户词之后、拼音词表之前）。
     *
     * 快捷短语也必须并入：输入法默认是拼音态，若只在五笔态挂快捷规则，用户设置的
     * 4 位快捷字母（如 `ytyc` → 雨天泳池）在拼音态会完全没有候选。
     */
    fun pinyinLookupEngine(context: Context): CompositeEngine {
        val cached = pinyinLookupCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = pinyinLookupCache
            if (existing != null) return existing
            val appCtx = context.applicationContext
            val engines = ArrayList<LookupEngine>()
            engines.add(UserDictEngine(userDict(appCtx), UserDictMode.PINYIN))
            val cells = cellDict(appCtx).entries(enabledOnly = true)
            if (cells.isNotEmpty()) engines.add(CellDictEngine(cells, UserDictMode.PINYIN))
            val dict = PinyinDictLoader.load(appCtx)
            if (!dict.isEmpty) {
                engines.add(
                    PinyinEngine(
                        dict,
                        PinyinSettings(
                            fuzzy = SettingsStore.keyboardSettings(appCtx).fuzzyPinyin,
                            kind = LookupKind.PINYIN,
                        ),
                    )
                )
            }
            // 快捷短语 / 快速应用与五笔态共用同一批规则：拼音态（默认态）也必须能命中
            // 用户设置的快捷字母，否则输入 `ytyc` 只会在拼音词表里查无结果。
            val (enableQuick, enableRemote, singleNoTrigger) = runBlocking {
                val q = SettingsStore.enableQuickPhrasesFlow(appCtx).first()
                val r = SettingsStore.enableRemoteRulesFlow(appCtx).first()
                val s = SettingsStore.singleLetterNoTriggerFlow(appCtx).first()
                Triple(q, r, s)
            }
            engines.addAll(
                QuickLocator.buildQuickEngines(
                    appCtx,
                    includeLocal = enableQuick,
                    includeRemote = enableRemote,
                    includeUser = true,
                    minTriggerLength = if (singleNoTrigger) 2 else 1,
                )
            )
            val created = CompositeEngine(engines)
            pinyinLookupCache = created
            return created
        }
    }

    /** 设置项变更后调用，使下次 compositeEngine() 调用重建引擎。 */
    fun invalidateEngine() {
        synchronized(this) {
            compositeEngineCache = null
            pinyinEngineCache = null
            pinyinLookupCache = null
            engineGeneration++
        }
    }

    /** 当前引擎代次（只增不减），与 [invalidateEngine] 配对使用 */
    fun engineGeneration(): Int = engineGeneration

    /** csBaby 后端客户端，全局懒加载缓存 */
    fun csBabyClient(context: Context): CsBabyClient {
        val cached = csBabyClientCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = csBabyClientCache
            if (existing != null) return existing
            val created = CsBabyClient(context.applicationContext)
            csBabyClientCache = created
            return created
        }
    }

    /** 快捷短语用户文件：filesDir/quick_phrases_user.txt */
    fun quickPhrasesUserFile(context: Context): File =
        File(context.filesDir, QUICK_PHRASES_USER_FILE)

    /** 快捷短语远端规则文件：filesDir/quick_phrases_remote.txt（同步拉取产物，只读展示） */
    fun quickPhrasesRemoteFile(context: Context): File =
        File(context.filesDir, QuickLocator.REMOTE_FILE_NAME)

    /** 用户词典存储：filesDir/user_dict.txt，全局懒加载缓存 */
    fun userDict(context: Context): UserDictStore {
        val cached = userDictCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = userDictCache
            if (existing != null) return existing
            val created = UserDictStore.open(File(context.filesDir, USER_DICT_FILE))
            userDictCache = created
            return created
        }
    }

    /** 清空用户词库后调用：丢弃缓存实例，下次 userDict() 重新读盘。 */
    fun invalidateUserDict() {
        synchronized(this) {
            userDictCache = null
        }
    }

    /** 分类词库（细胞词库）：filesDir/cell_dicts/，全局懒加载缓存 */
    fun cellDict(context: Context): CellDictStore {
        val cached = cellDictCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = cellDictCache
            if (existing != null) return existing
            val created = CellDictStore.open(File(context.filesDir, CELL_DICT_DIR_NAME))
            cellDictCache = created
            return created
        }
    }

    /** 安装 / 删除 / 启停分类词库后调用：丢弃缓存实例并让引擎重建（下次查询用上新词库）。 */
    fun invalidateCellDict() {
        synchronized(this) {
            cellDictCache = null
        }
        invalidateEngine()
    }

    /** 剪贴板历史：filesDir/clipboard_history.txt，全局懒加载缓存 */
    fun clipboardHistory(context: Context): ClipboardHistory {
        val cached = clipboardHistoryCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = clipboardHistoryCache
            if (existing != null) return existing
            val created = ClipboardHistory.open(File(context.filesDir, CLIPBOARD_HISTORY_FILE))
            clipboardHistoryCache = created
            return created
        }
    }

    /** 清空剪贴板历史后调用：丢弃缓存实例，下次 clipboardHistory() 重新读盘。 */
    fun invalidateClipboardHistory() {
        synchronized(this) {
            clipboardHistoryCache = null
        }
    }

    /**
     * 快捷短语使用记录（收藏 / 最近 / 计数）：filesDir/phrase_usage.txt。
     *
     * 必须全局单例：该 store 用「整文件重写 + 原子 rename」落盘，
     * 多个实例指向同一文件会互相覆盖。
     */
    fun phraseUsage(context: Context): PhraseUsageStore {
        val cached = phraseUsageCache
        if (cached != null) return cached
        synchronized(this) {
            val existing = phraseUsageCache
            if (existing != null) return existing
            val created = PhraseUsageStore.open(File(context.filesDir, PHRASE_USAGE_FILE))
            phraseUsageCache = created
            return created
        }
    }

    /** 清空短语使用记录后调用：丢弃缓存实例，下次 phraseUsage() 重新读盘。 */
    fun invalidatePhraseUsage() {
        synchronized(this) {
            phraseUsageCache = null
        }
    }
}
