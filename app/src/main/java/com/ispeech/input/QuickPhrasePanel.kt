package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ispeech.R
import com.ispeech.data.PhraseSearch
import com.ispeech.data.PhraseUsageStore
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickSource

/**
 * 快捷短语面板（csBaby 风格）：标题行 + 分类胶囊 + 规则列表。
 *
 * 结构：
 * 1. 标题行：[‹ 返回] 「快捷短语 · csBaby」 [筛选：xx / 共 N 条]
 * 2. 分类行：全部 / ★ 收藏 / 最近 / 内置 / 远端 / 用户 / 快速应用 / 短语（横向滚动胶囊）
 * 3. 列表：ScrollView（最高 300dp），每行 [label + content 预览 | ★ 星标 + 来源 / 类型角标]
 * 4. 空状态：「没有匹配的快捷短语」
 *
 * 数据由服务在 IO 线程读完规则文件后调用 [submit] 灌入；关键词由软键盘字母键经
 * [setKeyword] 实时过滤，分类筛选与关键词筛选叠加，列表最多渲染 200 条。
 * 搜索框持有输入焦点（[requestSearchFocus]），并复用键盘「中/英」键的当前输入状态
 * （[setInputLanguage]）。这里的「焦点」是面板自己的语义（描边 + 自绘光标闪烁），
 * **不调用 `requestFocus()`**：这是 IME 自己的窗口，抢系统焦点会被当成新的输入目标。
 * 搜索框同时维护 `(filterKeyword, cursorIndex)` 双状态，光标位置由 [SearchCaret] 夹紧、
 * 由 [CaretField] 渲染成一条真实位置的竖线（不再把 `|` 写进文本、也不再恒贴末尾）。
 * 关键词匹配走 PhraseSearch：完全 / 前缀 / 包含 / 拼音首字母四档 + 使用次数与收藏加权，
 * 即「智能联想」；无关键词时保持规则文件原始顺序。
 *
 * 收藏 / 最近使用：由服务读盘后经 [submitUsage] 灌入 id 集合，面板只做内存过滤与渲染，
 * **不做任何磁盘 IO**；星标点击只回调 [onToggleStar]，写盘与状态刷新由服务负责。
 */
class QuickPhrasePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击某条短语：回调其 content（快速应用内容），由服务上屏。 */
    var onInsert: ((String) -> Unit)? = null

    /** 点击返回键 / 关闭。 */
    var onClose: (() -> Unit)? = null

    /** 点击行内 ★/☆：只切换收藏（星标是独立子 View，不会触发 [onInsert] 上屏）。 */
    var onToggleStar: ((QuickEntry) -> Unit)? = null

    /** 点击搜索框右侧 ✕：清空当前关键词，服务侧同步清空 panelKeyword。 */
    var onClearKeyword: (() -> Unit)? = null

    // ── 数据（submit 可能来自非主线程）──
    @Volatile
    private var allEntries: List<QuickEntry> = emptyList()

    @Volatile
    private var filterKeyword: String = ""

    /** 已收藏的短语 id 集合；空集合 = 未接线（行为与增强前一致）。 */
    @Volatile
    private var starredIds: Set<String> = emptySet()

    /** 最近使用的短语 id 集合；空集合 = 未接线（「最近」胶囊为空列表）。 */
    @Volatile
    private var recentIds: Set<String> = emptySet()

    /** id → 使用次数；只参与关键词搜索的排序（面板依旧零磁盘 IO）。 */
    @Volatile
    private var usageCounts: Map<String, Int> = emptyMap()

    /**
     * 当前输入语言是否为英文态。
     *
     * 与键盘左下角「中/英」键**共用同一个状态源**（服务侧 `mode`），只影响搜索框状态展示，
     * 不影响关键词匹配（匹配始终按 ASCII 拼音首字母走 [PhraseSearch]）。
     */
    @Volatile
    private var englishInput: Boolean = false

    private var selectedCategory: Int = 0

    // ── 颜色 ──
    private val primary = Color.parseColor("#5B6CFF")
    private val primaryPressed = Color.parseColor("#4657D6")
    private val panelBg = Color.parseColor("#F4F5F7")
    private val textPrimary = Color.parseColor("#1A1A1A")
    private val textSecondary = Color.parseColor("#777777")
    private val dividerColor = Color.parseColor("#E0E3E8")
    private val rowBg = Color.parseColor("#FFFFFF")
    private val pressedBg = Color.parseColor("#D3D9E3")
    private val pillBg = Color.parseColor("#E8EBF0")
    private val pillText = Color.parseColor("#555555")
    private val tagBg = Color.parseColor("#E9ECF4")
    private val tagText = Color.parseColor("#5A6072")

    // ── 尺寸 ──
    private val maxPanelHeightDp = 200
    private val maxRows = 200
    private val radiusKeyDp = 8

    // ── 视图（build() 中创建）──
    private lateinit var infoLabel: TextView
    /** 搜索框文本：EditText 才能让系统在同一行内提供测量 / 省略能力，但它**不可聚焦、不画系统光标**。 */
    private lateinit var searchText: EditText
    private lateinit var searchClear: ImageView

    /**
     * 自绘光标：位置由 [cursorIndex] 决定，跟文本末尾不是一回事。
     *
     * 为什么不用系统光标：`searchText` 刻意 `isCursorVisible = false`（抢焦点会触发输入法切换，
     * 见 [buildSearchBox] 注释），所以闪烁、位置全部由这里负责。
     */
    private lateinit var caret: View

    /** 把文本与自绘光标放在同一坐标系里的承载视图（位置算法见 [CaretField]）。 */
    private lateinit var searchField: CaretField

    /** 光标闪烁周期（与系统默认节奏一致）。 */
    private val caretBlinkMs = 500L
    private val caretHandler = Handler(Looper.getMainLooper())
    private val caretBlink = object : Runnable {
        override fun run() {
            if (!::caret.isInitialized) return
            caret.visibility =
                if (caret.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            caretHandler.postDelayed(this, caretBlinkMs)
        }
    }

    /**
     * 搜索词的光标位置（与 `filterKeyword` 组成消费级 `(keyword, cursor)` 双状态）。
     *
     * 由 [SearchCaret] 的纯函数维护，越界一律夹紧；条目 1 会在服务侧接上移动与写入，
     * 这里只把读取点与渲染面备好（D2-5 需要 `cursor()` 可读）。
     */
    @Volatile
    private var cursorIndex: Int = 0

    /** 搜索框整行（可聚焦容器）：打开面板时把输入焦点移到这里。 */
    private lateinit var searchRow: LinearLayout

    /** 搜索框右侧的「中/英」状态徽标（复用键盘左下角按键的输入状态）。 */
    private lateinit var langBadge: TextView
    private lateinit var pillRow: LinearLayout
    private lateinit var listContainer: LinearLayout

    /** 胶囊的「使用维度」筛选：NONE 表示沿用原有分类筛选。 */
    private enum class UsageFilter { NONE, STARRED, RECENT }

    /** 分类筛选条件：source / kind / usage 全为默认值表示「全部」。 */
    private class Category(
        val title: String,
        val source: String? = null,
        val kind: LookupKind? = null,
        val usage: UsageFilter = UsageFilter.NONE
    )

    /**
     * 分类胶囊顺序：全部 + 收藏 / 最近 + 来源分组（内置 / 远端 / 用户）+ 快速应用 + 短语。
     * 后六项与增强前完全一致，只是整体后移两位。
     */
    private val categories: List<Category> =
        listOf(
            Category("全部"),
            Category("★ 收藏", usage = UsageFilter.STARRED),
            Category("最近", usage = UsageFilter.RECENT)
        ) +
            QuickSource.all.map { Category(it, source = it) } +
            listOf(
                Category(LookupKind.QUICKAPPLY.label, kind = LookupKind.QUICKAPPLY),
                Category(LookupKind.PHRASE.label, kind = LookupKind.PHRASE)
            )

    // ── 冻结 API ──

    /** 数据到达：提交全部规则（可能从非主线程调用，内部切主线程渲染）。 */
    fun submit(entries: List<QuickEntry>) {
        allEntries = ArrayList(entries)
        runOnMain { render() }
    }

    /**
     * 使用数据到达：已收藏 id 集合 + 最近使用 id 集合（可能从非主线程调用，内部切主线程渲染）。
     *
     * 传空集合等价于「没有收藏 / 最近记录」，面板其余行为与增强前完全一致；
     * id 必须由服务用 `PhraseUsageStore.idOf(entry)` 生成。
     *
     * [usageCounts] 为 id → 使用次数，只影响关键词搜索的排序（次数越多越靠前）；
     * 缺省空表时排序退化为匹配档位 + 收藏 + weight。
     */
    fun submitUsage(
        starredIds: Set<String>,
        recentIds: Set<String>,
        usageCounts: Map<String, Int> = emptyMap()
    ) {
        this.starredIds = HashSet(starredIds)
        this.recentIds = HashSet(recentIds)
        this.usageCounts = HashMap(usageCounts)
        runOnMain { render() }
    }

    /** 筛选关键词（来自软键盘字母键）：实时过滤；默认把光标落在文本末尾（与 main 现值一致）。 */
    fun setKeyword(keyword: String) {
        setKeyword(keyword, keyword.length)
    }

    /**
     * 关键词 + 光标位置的写入点（条目 1 的服务侧会走这里）。
     *
     * 光标越界由 [SearchCaret.clamp] 夹紧，所以「移到中间」不需要服务自己算边界，
     * 复用的也是 JVM 测试里那一份算式（[SearchCaretTest]）。
     */
    fun setKeyword(keyword: String, cursor: Int) {
        filterKeyword = keyword
        cursorIndex = SearchCaret.clamp(keyword.length, cursor)
        runOnMain { render() }
    }

    /**
     * 复用键盘左下角「中/英」键的当前输入状态：搜索框右侧徽标与键盘键面同步
     * （当前态的字用主题色、另一态的字变灰）。
     */
    fun setInputLanguage(english: Boolean) {
        if (english == englishInput) return
        englishInput = english
        // render() 内部已含 renderLanguageBadge()，一次重渲染即可同步徽标与面板状态
        runOnMain { render() }
    }

    /**
     * 把输入焦点移到搜索框（打开面板 / 切中英时由服务调用）。
     *
     * 「输入焦点」是面板自己的语义，**不调用 `requestFocus()`**：一旦让系统接管焦点，
     * 它会把它当新的输入目标并触发输入法切换 / 页面跳转（[buildSearchBox] 有完整说明）。
     * 这里只做两件事：整行描焦点边（`isSelected`）+ 自绘光标开始闪烁。
     */
    fun requestSearchFocus() {
        runOnMain {
            if (!::searchText.isInitialized) return@runOnMain
            if (::searchRow.isInitialized) searchRow.isSelected = true
            startCaretBlink()
            renderCaret()
        }
    }

    /** 当前筛选关键词。 */
    fun keyword(): String = filterKeyword

    /**
     * 当前光标位置（D2-5 的读取点）。
     *
     * 面板不抢焦点，系统那套 `selectionStart` 不参与，所以位置必须能从这里读出来，
     * 否则「空搜索框光标在索引 0」在测试里无从判定。
     */
    fun cursor(): Int = SearchCaret.clamp(filterKeyword.length, cursorIndex)

    /** 当前过滤结果第一条的 content（用于「空格上屏第一条」）。 */
    fun firstMatch(): String? = filtered().firstOrNull()?.content

    /**
     * 面板不可见（关闭 / 切到别的面板）或脱离窗口时停掉闪烁。
     *
     * 本单不改 `IspeechImeService`，所以不能指望服务在关闭时回调面板；面板自己收口，
     * 避免关闭后 Handler 还每 500 ms 往一个不可见的 View 上翻转可见性。
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility != View.VISIBLE) stopCaretBlink()
    }

    override fun onDetachedFromWindow() {
        stopCaretBlink()
        super.onDetachedFromWindow()
    }

    private fun stopCaretBlink() {
        caretHandler.removeCallbacks(caretBlink)
        if (::caret.isInitialized) caret.visibility = View.INVISIBLE
    }

    // ── 过滤 ──

    /** 分类筛选 + 关键词筛选叠加后的结果（未截断）。 */
    private fun filtered(): List<QuickEntry> {
        val keyword = filterKeyword
        val category = categories.getOrNull(selectedCategory) ?: categories[0]
        val scoped = ArrayList<QuickEntry>()
        for (entry in allEntries) {
            if (category.source != null && entry.source != category.source) continue
            if (category.kind != null && entry.kind != category.kind) continue
            if (category.usage == UsageFilter.STARRED && !isStarred(entry)) continue
            if (category.usage == UsageFilter.RECENT && !isRecent(entry)) continue
            scoped.add(entry)
        }
        // 无关键词时保持原有顺序（分类筛完的原始次序），避免「打开面板条序变了」
        if (keyword.isEmpty()) return scoped
        // 有关键词时交给 PhraseSearch 做智能联想：完全 > 前缀 > 包含 > 拼音首字母（fp → 发票），
        // 同档再按使用次数 / 收藏 / weight 加权；纯计算，不碰磁盘。
        return PhraseSearch(
            usageCount = { id -> usageCounts[id] ?: 0 },
            starredFlag = { id -> id in starredIds }
        ).searchEntries(scoped, keyword = keyword, limit = maxRows)
    }

    /** 短语稳定标识：与 PhraseUsageStore 的收藏 / 最近记录共用同一算法（纯计算，无 IO）。 */
    private fun idOf(entry: QuickEntry): String = PhraseUsageStore.idOf(entry)

    private fun isStarred(entry: QuickEntry): Boolean = idOf(entry) in starredIds

    private fun isRecent(entry: QuickEntry): Boolean = idOf(entry) in recentIds

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else post { block() }
    }

    // ── 渲染 ──

    private fun render() {
        val keyword = filterKeyword
        val category = categories.getOrNull(selectedCategory) ?: categories[0]
        // 只算一次过滤结果：标题行与列表共用，避免重复计算 id 哈希。
        val results = filtered()
        // 搜索框展示当前关键词（关键词来自软键盘，与服务同屏，搜索框本身不弹键盘）。
        // 光标是独立的 [caret] 视图，位置由 [cursorIndex] 决定，**不再用「|」字符贴在末尾**——
        // 把光标画成文本的一部分正是条目 1 要修的缺陷（AC-1.9）。
        val typing = keyword.isNotEmpty()
        cursorIndex = SearchCaret.clamp(keyword.length, cursorIndex)
        if (searchText.text.toString() != keyword) {
            searchText.setText(keyword)
        }
        searchText.setTextColor(if (typing) textPrimary else textSecondary)
        searchClear.visibility = if (typing) View.VISIBLE else View.GONE
        infoLabel.text = when {
            keyword.isNotEmpty() -> "联想 ${results.size} 条 · $keyword"
            category.usage == UsageFilter.STARRED -> "★ 收藏 ${results.size} 条"
            category.usage == UsageFilter.RECENT -> "最近 ${results.size} 条"
            else -> "共 ${allEntries.size} 条"
        }
        renderPills()
        renderLanguageBadge()
        renderCaret()
        renderList(results)
    }

    /**
     * 把自绘光标摆到 [cursorIndex] 对应的位置。
     *
     * 位置不写进文本：`searchField` 按光标前的文本宽度算出 x 偏移（见 [CaretField]），
     * 所以「光标在中间」和「文本被省略」是两件事，不会互相污染。
     */
    private fun renderCaret() {
        if (!::searchField.isInitialized || !::caret.isInitialized) return
        searchField.caretIndex = SearchCaret.clamp(filterKeyword.length, cursorIndex)
    }

    /** 开始闪烁；重复调用不会叠多个 runnable（先移除再排队）。 */
    private fun startCaretBlink() {
        caretHandler.removeCallbacks(caretBlink)
        if (::caret.isInitialized) caret.visibility = View.VISIBLE
        caretHandler.postDelayed(caretBlink, caretBlinkMs)
    }

    /** 搜索框右侧「中/英」徽标：当前态用主题色，另一态变灰（与键盘键面同一套语义）。 */
    private fun renderLanguageBadge() {
        langBadge.text = SpannableString("中/英").apply {
            setSpan(
                ForegroundColorSpan(if (englishInput) textSecondary else primary),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(ForegroundColorSpan(textSecondary), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(if (englishInput) primary else textSecondary),
                2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        langBadge.contentDescription =
            if (englishInput) "输入状态：英文" else "输入状态：中文"
    }

    private fun renderPills() {
        pillRow.removeAllViews()
        categories.forEachIndexed { index, category ->
            val selected = index == selectedCategory
            val pill = TextView(context).apply {
                text = category.title
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                isFocusable = true
                setTextColor(if (selected) Color.WHITE else pillText)
                background = if (selected) {
                    keySelector(primary, primaryPressed)
                } else {
                    keySelector(pillBg, pressedBg)
                }
                contentDescription = category.title
                setOnClickListener {
                    if (selectedCategory != index) {
                        selectedCategory = index
                        render()
                    }
                }
            }
            pillRow.addView(
                pill,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
    }

    private fun renderList(results: List<QuickEntry>) {
        listContainer.removeAllViews()

        if (results.isEmpty()) {
            listContainer.addView(
                createEmptyView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        val shown = if (results.size > maxRows) results.subList(0, maxRows) else results
        shown.forEachIndexed { index, entry ->
            listContainer.addView(createRowView(entry, idOf(entry)), rowParams())
            if (index < shown.size - 1 || results.size > maxRows) {
                listContainer.addView(createDividerView(), dividerParams())
            }
        }
        if (results.size > maxRows) {
            listContainer.addView(
                createFooterView(results.size),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * 单行规则：左侧 label + content 预览，右侧 ★ 星标 + 来源 / 类型角标，整行点击上屏。
     *
     * [id] 由调用方传入（每次渲染只算一次哈希），与 [isStarred] 用的是同一个 id。
     */
    private fun createRowView(entry: QuickEntry, id: String): View {
        val label = TextView(context).apply {
            text = entry.label
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textPrimary)
        }
        val preview = TextView(context).apply {
            text = flatten(entry.content)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textSecondary)
        }
        // 标题行：名称 + 快捷字母角标（例：ycsf），让用户一眼看到该短语对应的触发码
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (entry.trigger.isNotBlank()) {
                addView(createTagView(entry.trigger), tagParams())
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(
                titleRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                preview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
        }
        val tagColumn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createStarView(entry, id), tagParams())
            addView(createTagView(entry.source), tagParams())
            addView(createTagView(entry.kind.label), tagParams())
        }

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = entry.label
            addView(
                textColumn,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(
                tagColumn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setOnClickListener { onInsert?.invoke(entry.content) }
        }
    }

    /**
     * 行内 ★/☆ 星标：只切换收藏。
     *
     * 触摸不会被行「上屏」抢走：星标是独立的可点击子 View，ViewGroup 默认不拦截子 View 命中的
     * 触摸序列，星标自己消费 DOWN/UP 并触发 [onToggleStar]，父行的 OnClickListener 不会执行。
     * 触摸目标做到 36dp × 36dp，降低「想点星标却点到行、导致误上屏」的概率。
     */
    private fun createStarView(entry: QuickEntry, id: String): ImageView {
        val starred = id in starredIds
        return ImageView(context).apply {
            setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            minimumWidth = dp(36)
            minimumHeight = dp(36)
            isClickable = true
            // 不抢焦点：软键盘面板内没有方向键导航需求，避免出现焦点框。
            isFocusable = false
            background = keySelector(rowBg, pressedBg)
            contentDescription = if (starred) "取消收藏 ${entry.label}" else "收藏 ${entry.label}"
            setOnClickListener { onToggleStar?.invoke(entry) }
        }
    }

    /** 来源 / 类型小角标 */
    private fun createTagView(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 10f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(tagText)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = rounded(tagBg, 6)
    }

    private fun createEmptyView(): TextView = TextView(context).apply {
        text = "没有匹配的快捷短语"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(textSecondary)
        setPadding(dp(12), dp(32), dp(12), dp(32))
    }

    private fun createFooterView(total: Int): TextView = TextView(context).apply {
        text = "仅显示前 $maxRows 条（共 $total 条）"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textSecondary)
        setPadding(dp(12), dp(12), dp(12), dp(16))
    }

    private fun createDividerView(): View =
        View(context).apply { setBackgroundColor(dividerColor) }

    private fun dividerParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(1)
    )

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun tagParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(6) }

    /** 预览文本里的换行折叠成「⏎」，保持单行展示 */
    private fun flatten(text: String): String =
        text.replace("\r\n", "\n").replace("\n", "⏎")

    // ── 构建 ──

    private fun build() {
        addView(
            buildTitleRow(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            buildSearchBox(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(2)
                bottomMargin = dp(6)
            }
        )
        addView(
            createDividerView(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        )
        addView(
            buildPillRow(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            buildList(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        render()
    }

    private fun buildTitleRow(): View {
        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = "返回"
            setOnClickListener { onClose?.invoke() }
        }
        val title = TextView(context).apply {
            text = "快捷短语 · csBaby"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textPrimary)
        }
        infoLabel = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textSecondary)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
            addView(
                back,
                LinearLayout.LayoutParams(dp(34), dp(30))
            )
            addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(6) }
            )
            addView(
                infoLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * 搜索栏：`🔍` + 关键词（无关键词时显示占位文案）+ 自绘光标 + `中/英` 状态徽标 + `✕` 清空按钮。
     *
     * 与软键盘同屏：关键词由软键盘字母键经 [setKeyword] 流入并即时过滤
     * （完全 / 前缀 / 包含 / 拼音首字母四档智能联想），所以这里不弹系统键盘。
     * 整行是**可聚焦容器**：打开面板时服务调用 [requestSearchFocus] 把输入焦点落在搜索框，
     * 获得焦点时描一圈主题色（见 [searchBoxBackground]），让「焦点在哪」可见。
     * 右侧 `✕` 是独立可点子 View，只回调 [onClearKeyword]（服务清空关键词），不会触发上屏。
     *
     * 光标是独立的 [caret] 视图，位置按 [cursorIndex] 在文本里的宽度算（见 [CaretField]）；
     * 它既不抢焦点、也不写进文本，所以「光标在中间」既不会被系统光标覆盖，
     * 也不会退化成「末尾多一个 `|` 字符」。
     */
    private fun buildSearchBox(): View {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            scaleType = ImageView.ScaleType.CENTER
        }
        searchText = EditText(context).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false
            setTextColor(textSecondary)
            setHintTextColor(textSecondary)
            hint = context.getString(R.string.quick_panel_search_hint)
            // 交互标志必须放在 `isSingleLine` / `inputType` **之后**：这两行都会走
            // `TextView.setInputType → setKeyListener → fixFocusableAndClickableSettings()`，
            // 而 EditText 的 `mKeyListener` 恒非空（`ArrowKeyMovementMethod`），该函数会把
            // `clickable` 与 `longClickable` **强制改回 true**、并把 focusable 改成 FOCUSABLE。
            // 写在前面等于白写：控件变成可点 → `View.onTouchEvent` 把 ACTION_DOWN 吃掉并
            // `return true`，父行 [searchRow] 的「点搜索框回到输入态」在文本区（weight=1f，
            // 占整行大部分）整段失效，这是相对 main（普通 TextView 不吞事件）的退步。
            pinDisplayOnly()
        }
        caret = View(context).apply {
            setBackgroundColor(primary)
            visibility = View.INVISIBLE
        }
        searchField = CaretField(context, searchText, caret)
        langBadge = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(6), 0, dp(2), 0)
        }
        searchClear = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(30)
            minimumHeight = dp(28)
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            contentDescription = context.getString(R.string.btn_close)
            setOnClickListener {
                onClearKeyword?.invoke()
                requestSearchFocus()
            }
        }
        searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            background = searchBoxBackground()
            // 「输入焦点」是面板自己的语义（描边 + 自绘光标），**不交给系统**：
            // 两个 focusable 都关掉，避免它成为新的输入目标（见 searchText 同段说明）。
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            contentDescription = "快捷短语搜索框"
            setOnClickListener { requestSearchFocus() }
            addView(
                icon,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                searchField,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                }
            )
            addView(
                langBadge,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                searchClear,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        renderLanguageBadge()
        return searchRow
    }

    /** 搜索框背景：面板处于输入态（[searchRow] `isSelected`）时描一圈主题色边框，其余时候是普通胶囊底色。 */
    private fun searchBoxBackground(): StateListDrawable {
        val focused = rounded(pillBg, radiusKeyDp).apply { setStroke(dp(1), primary) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), rounded(pillBg, radiusKeyDp))
        }
    }

    private fun buildPillRow(): View {
        pillRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                pillRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildList(): View {
        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        return BoundedScrollView(context, dp(maxPanelHeightDp)).apply {
            isVerticalScrollBarEnabled = true
            addView(
                listContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // ── 局部私有辅助（不跨文件共享）──

    /**
     * 把搜索框钉成「纯展示控件」：不聚焦、不点、不响应长按。
     *
     * 必须晚于任何会触发 `TextView.setInputType / setKeyListener` 的调用（`isSingleLine`、
     * `inputType`）。那条路径最终会走 `fixFocusableAndClickableSettings()`，在 `mMovement != null`
     * 时把 `focusable / clickable / longClickable` **全部改回 true**——也就是把这里关掉的东西又打开。
     *
     * `longClickable` 单独关掉的两个原因：
     * 1. `View.onTouchEvent` 把 `LONG_CLICKABLE` 也算作可点，命中就吞掉 `ACTION_DOWN` 并 `return true`，
     *    父行 [searchRow] 的点击回调在文本区（`weight = 1f`，占整行大部分）失效；
     * 2. 防 OEM 长按在 EditText 上弹系统选区手柄 / 文本工具栏。
     */
    private fun TextView.pinDisplayOnly() {
        // 先摘移动方法：`setMovementMethod` 自己会走一遍 `fixFocusableAndClickableSettings()`，
        // 反过来把下面几个标志改回 true。放在最前面，后面设的值才是最终值。
        movementMethod = null
        isCursorVisible = false
        // 「抢焦点」是硬约束：系统一旦接管就会把它当新的输入目标，触发输入法切换 / 页面跳转。
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun keySelector(normalColor: Int, pressedColor: Int): StateListDrawable {
        val normal = rounded(normalColor, radiusKeyDp)
        val pressed = rounded(pressedColor, radiusKeyDp)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_selected), pressed)
            addState(intArrayOf(), normal)
        }
    }

    // init 必须位于所有属性声明之后：Kotlin 按声明顺序初始化，
    // 若在颜色 / 尺寸 / categories 赋值前渲染，列表与胶囊都会退化为空。
    init {
        orientation = VERTICAL
        setBackgroundDrawable(rounded(panelBg, 0))
        build()
    }
}

/**
 * 搜索框的「文本 + 自绘光标」承载视图。
 *
 * 为什么不把 caret 直接塞进 LinearLayout 里做兄弟节点：光标要落在**某个字符之后**，
 * 位置取决于光标前那段文本的实际宽度（中文 / 英文 / 省略号宽度都不同），线性布局做不到。
 * 这里把文本控件与 2dp 光标的**左偏移**都算在同一坐标系里：
 *
 * 1. 文本单行、超出宽度时按 [TextUtils.TruncateAt.END] 省略（`PANEL_SEED_MAX_CHARS = 20`
 *    的上限下，中文 14sp 通常在宽度内；省略只影响极端窄屏，不影响光标索引本身）；
 * 2. 光标 x = 光标前子串的实测宽度，再夹到可视区内（极端窄屏时不越出控件边界），
 *    所以「光标移到中间」不用改文本、也不用 `setSelection`（`setSelection` 把光标钉在末尾
 *    正是条目 1 要修的缺陷）；
 * 3. 光标是 2dp 细条、高度 18dp，垂直居中对齐文本基线。
 *
 * 纯测量 + 布局，不持有数据、不回调服务：数据仍由 [QuickPhrasePanel] 持有。
 */
private class CaretField(
    context: Context,
    private val textView: TextView,
    private val caret: View
) : ViewGroup(context) {

    /** 光标索引（字符位置，0 = 文本最前）。越界由调用方夹紧，这里只负责摆放。 */
    var caretIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val caretWidthPx = dp(2)
    private val caretHeightPx = dp(18)
    private val caretGapPx = dp(1)

    init {
        // ViewGroup 基类的 generateDefaultLayoutParams() 返回 null，必须显式给参数。
        addView(
            textView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            caret,
            LayoutParams(caretWidthPx, caretHeightPx)
        )
        // 文本控件自身不再拦截触摸：整行由父容器负责「点搜索框 = 回到输入态」。
        textView.isClickable = false
        textView.isFocusable = false
        textView.isFocusableInTouchMode = false
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        // 文本宽度固定占满（减去光标与间隙）：宽度用 EXACTLY 而不是 AT_MOST，
        // 这样 `measuredWidth` 就是可用宽度，光标贴文本末尾时不会被误夹到文本中间；
        // 超宽由 `singleLine + ellipsize=END` 自己省略。高度按内容，父容器给 AT_MOST / EXACTLY 都收。
        val textWidth = (width - caretWidthPx - caretGapPx).coerceAtLeast(0)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = maxOf(textView.measuredHeight, caretHeightPx)
        val measuredHeight = when (heightMode) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(heightMeasureSpec)
            else -> contentHeight
        }
        caret.measure(
            View.MeasureSpec.makeMeasureSpec(caretWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(caretHeightPx, View.MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val textLeft = 0
        val textTop = (height - textView.measuredHeight) / 2
        textView.layout(textLeft, textTop, textLeft + textView.measuredWidth, textTop + textView.measuredHeight)
        // 光标 x：光标前子串的实测宽度（`TextView.paint` 与控件用同一 Typeface / textSize）
        val prefix = textView.text.toString().take(caretIndex.coerceIn(0, textView.text.length))
        val prefixWidth = textView.paint.measureText(prefix).toInt()
        val maxCaretLeft = (textView.measuredWidth - caretWidthPx).coerceAtLeast(0)
        val caretLeft = (textLeft + prefixWidth + caretGapPx).coerceIn(0, maxCaretLeft)
        val caretTop = (height - caretHeightPx) / 2
        caret.layout(caretLeft, caretTop, caretLeft + caretWidthPx, caretTop + caretHeightPx)
    }
}

/**
 * ScrollView：高度随内容自适应，但不超过 [maxHeightPx]，用于把面板列表压在安全高度内。
 */
private class BoundedScrollView(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == View.MeasureSpec.UNSPECIFIED) {
            maxHeightPx
        } else {
            minOf(maxHeightPx, size)
        }
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
        )
    }
}
