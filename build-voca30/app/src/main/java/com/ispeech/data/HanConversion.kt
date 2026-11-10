package com.ispeech.data

import android.content.Context
import android.util.Log
import com.ispeech.di.AppContainer

/**
 * 简繁字符转换（字符级，一对一查表）。
 *
 * 数据来自上游 OpenCC 的字符表，**裁剪后随包内置**，不手写任何汉字映射：
 * - `han_s2t.txt`：源自 STCharacters.txt（简体 → 繁体）
 * - `han_t2s.txt`：源自 TSCharacters.txt（繁体 → 简体）
 *
 * 来源 / 许可 / sha256 / 裁剪口径见 `docs/stats-and-hanconversion.md` 与资产文件头部注释，
 * 生成脚本：`tools/gen/build_han_assets.py`。
 *
 * 设计要点：
 * - 懒装载：首次转换时才读 assets（主线程上也只读一次，两个文件约 40 KB）；
 * - 失败降级：[install] 读不到资产时只打日志，转换**原样返回**，绝不抛异常打崩输入法；
 * - 只做字符级映射：不做短语 / 分词歧义处理（如「乾隆」会被转成「干隆」），
 *   与上游字符表行为一致，短语消歧属于后续可选的升级项。
 *
 * 用法：
 * ```
 * HanConversion.install(context)          // 可选：显式装载（幂等）
 * val t = toTraditional("发展")           // 发 展 -> 發 展
 * val s = toSimplified("發展")            // 發 展 -> 发 展
 * ```
 */
object HanConversion {

    /** 简体 → 繁体 资产名（app/src/main/assets/）。 */
    const val ASSET_S2T = "han_s2t.txt"

    /** 繁体 → 简体 资产名（app/src/main/assets/）。 */
    const val ASSET_T2S = "han_t2s.txt"

    private const val TAG = "HanConversion"

    private val lock = Any()

    /** 装载状态：无论成功失败都只装载一次，避免每次转换都重试读盘。 */
    @Volatile
    private var ready = false

    @Volatile
    private var s2tMap: Map<Char, String> = emptyMap()

    @Volatile
    private var t2sMap: Map<Char, String> = emptyMap()

    /** 是否已装载完成（含装载失败的情形）。 */
    val isReady: Boolean get() = ready

    /** 已装载的映射条目数：`简体→繁体` / `繁体→简体`（装载失败时为 0 / 0）。 */
    val entryCounts: Pair<Int, Int> get() = s2tMap.size to t2sMap.size

    /**
     * 装载映射（幂等，线程安全）。
     *
     * @param context 任意上下文（内部转 applicationContext）
     */
    fun install(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val app = context.applicationContext ?: context
            s2tMap = load(app, ASSET_S2T)
            t2sMap = load(app, ASSET_T2S)
            ready = true
            if (s2tMap.isEmpty() && t2sMap.isEmpty()) {
                Log.w(TAG, "简繁映射未装载（assets 缺失或损坏），转换将原样返回")
            } else {
                Log.i(TAG, "简繁映射已装载：s2t=${s2tMap.size} t2s=${t2sMap.size}")
            }
        }
    }

    /** 简体 → 繁体；未装载 / 无映射 / 装载失败时原样返回。 */
    fun toTraditional(text: String): String {
        if (!ensureInstalled()) return text
        // 注意先装载再取 map：Kotlin 实参按从左到右求值，写成 convert(text, s2tMap, ensureInstalled())
        // 会先读到空的 s2tMap，导致惰性装载后的第一次转换静默失效。
        val map = s2tMap
        return if (map.isEmpty()) text else convert(text, map)
    }

    /** 繁体 → 简体；未装载 / 无映射 / 装载失败时原样返回。 */
    fun toSimplified(text: String): String {
        if (!ensureInstalled()) return text
        val map = t2sMap
        return if (map.isEmpty()) text else convert(text, map)
    }

    /**
     * 惰性装载：未显式 [install] 时用 [AppContainer] 里的应用上下文兜底，
     * 这样输入法服务与设置页都不需要额外接线（容器在 IspeechApp.onCreate 已初始化）。
     */
    private fun ensureInstalled(): Boolean {
        if (ready) return true
        val context = runCatching { AppContainer.applicationContext() }.getOrNull() ?: return false
        install(context)
        return true
    }

    /**
     * 字符级转换：逐码点查表，未命中原样保留；代理对（emoji 等）整对拷贝不拆分。
     * 结果与原文一致时返回原字符串实例，避免多余分配。
     */
    private fun convert(text: String, map: Map<Char, String>): String {
        if (map.isEmpty() || text.isEmpty()) return text
        var changed = false
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (i + 1 < text.length && ch.isHighSurrogate() && text[i + 1].isLowSurrogate()) {
                out.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            val mapped = map[ch]
            if (mapped == null) {
                out.append(ch)
            } else {
                out.append(mapped)
                changed = true
            }
            i += 1
        }
        return if (changed) out.toString() else text
    }

    /** 读取一行一条的映射资产；格式 `key<TAB>value1 value2 ...`（取第一项），`#` 为注释。 */
    private fun load(context: Context, assetName: String): Map<Char, String> {
        val map = HashMap<Char, String>(4096)
        runCatching {
            context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    val line = raw.trim('\r')
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val key = line.substring(0, tab)
                    if (key.length != 1) continue
                    val primary = line.substring(tab + 1).split(' ').firstOrNull { it.isNotEmpty() }
                    if (primary.isNullOrEmpty()) continue
                    map[key[0]] = primary
                }
            }
        }.onFailure { Log.w(TAG, "读取简繁映射失败: $assetName", it) }
        return map
    }
}

/**
 * 简体 → 繁体（顶层函数，便于在需要简单调用的地方直接 `import com.ispeech.data.toTraditional`）。
 * 未装载或无映射时原样返回，不抛异常。
 */
fun toTraditional(text: String): String = HanConversion.toTraditional(text)

/**
 * 繁体 → 简体（顶层函数）。
 * 未装载或无映射时原样返回，不抛异常。
 */
fun toSimplified(text: String): String = HanConversion.toSimplified(text)

/** Char 是否为高 / 低代理项（不依赖 java.lang.Character，便于纯逻辑测试）。 */
private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'

private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
