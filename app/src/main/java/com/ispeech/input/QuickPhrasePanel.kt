package com.ispeech.input

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
 * （[setInputLanguage]）。
 * 关键词匹配走 PhraseSearch：完全 / 前缀 / 包含 / 拼音首字母四档 + 使用次数与收藏加权，
 * 即「智能联想」；中文关键词按字面包含匹配（见 PhraseSearch），英文关键词走拼音首字母。
 * 无关键词时保持规则文件原始顺序。
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
     * 不影响关键词匹配（中文关键词走字面包含，英文关键词走拼音首字母 [PhraseSearch]）。
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

    /** 默认态图标灰色，与 [SoftKeyboardView] 的 hintColor 保持一致。 */
    private val iconDefault = Color.parseColor("#8A9099")

    // ── 尺寸 ──
    private val maxPanelHeightDp = 200
    private val maxRows = 200
    private val radiusKeyDp = 8

    // ── 视图（build() 中创建）──
    private lateinit var infoLabel: TextView

    /** 搜索框：EditText 才能在获得焦点后绘制可见光标（TextView 不会）。 */
    private lateinit var searchText: EditText

    /** 自绘光标：请示输入焦点时闪烁，代替会被系统接管的 EditText 光标。 */
    private lateinit var caret: View

    /** 光标闪烁间隔（毫秒） */
    private val caretBlinkMs = 500L

    /** 光标闪烁任务 */
    private val caretHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val caretRunnable = object : Runnable {
        override fun run() {
            if (!::caret.isInitialized) return
            caret.visibility = if (caret.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            caretHandler.postDelayed(this, caretBlinkMs)
        }
    }
    private lateinit var searchClear: ImageView

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
     * 分类胶囊：只有 全部 / ★ 收藏 / 最近。
     *
     * 原先还有来源分组（内置 / 远端 / 用户）与类型（应用 / 短语）胶囊，信息价值低于占地，
     * 已按需求移除；来源不再是用户需要感知的维度，搜索结果统一按相关性排序。
     */
    private val categories: List<Category> =
        listOf(
            Category("全部"),
            Category("★ 收藏", usage = UsageFilter.STARRED),
            Category("最近", usage = UsageFilter.RECENT)
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

    /** 筛选关键词（来自软键盘字母键）：实时过滤。 */
    fun setKeyword(keyword: String) {
        filterKeyword = keyword
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
     * 搜索框不弹系统键盘（输入来自同屏软键盘，`showSoftInputOnFocus = false`），但需要持有焦点：
     * EditText 获得焦点后才会绘制并闪烁光标，搜索框也会显示焦点描边（见 [searchBoxBackground]）。
     */
    fun requestSearchFocus() {
        runOnMain {
            if (!::searchText.isInitialized) return@runOnMain
            // 只标记「面板处于输入态」：描边 + 自绘光标闪烁，**不调用 requestFocus()**
            if (::searchRow.isInitialized) searchRow.isSelected = true
            caret.visibility = View.VISIBLE
            caretHandler.removeCallbacks(caretRunnable)
            caretHandler.postDelayed(caretRunnable, caretBlinkMs)
        }
    }

    /** 退出输入态：停止光标闪烁（关闭面板时由服务调用）。 */
    fun clearSearchFocus() {
        runOnMain {
            caretHandler.removeCallbacks(caretRunnable)
            if (::caret.isInitialized) caret.visibility = View.INVISIBLE
            if (::searchRow.isInitialized) searchRow.isSelected = false
        }
    }

    /** 当前筛选关键词。 */
    fun keyword(): String = filterKeyword

    /** 当前过滤结果第一条的 content（用于「空格上屏第一条」）。 */
    fun firstMatch(): String? = filtered().firstOrNull()?.content

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
        // EditText 进入编辑态后由系统绘制闪烁光标，不再用「|」字符模拟；有关键词时显示 ✕ 清空按钮。
        val typing = keyword.isNotEmpty()
        // 只在内容变化时写回：EditText 持有焦点时反复 setText 会把光标重置、打断输入
        if (searchText.text.toString() != keyword) {
            searchText.setText(keyword)
            searchText.setSelection(searchText.text.length)
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
        renderList(results)
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
            // 只有默认态（未收藏）的描边星标统一变灰；已收藏的实心星标保留原有高亮色
            if (!starred) tintDefaultIcon(this)
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
            tintDefaultIcon(this)
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
     * 搜索栏：`🔍` + 关键词（无关键词时显示占位文案）+ `中/英` 状态徽标 + `✕` 清空按钮。
     *
     * 与软键盘同屏：关键词由软键盘字母键经 [setKeyword] 流入并即时过滤
     * （完全 / 前缀 / 包含 / 拼音首字母四档智能联想），所以这里不弹系统键盘。
     * 整行是**可聚焦容器**：打开面板时服务调用 [requestSearchFocus] 把输入焦点落在搜索框，
     * 获得焦点时描一圈主题色（见 [searchBoxBackground]），让「焦点在哪」可见。
     * 右侧 `✕` 是独立可点子 View，只回调 [onClearKeyword]（服务清空关键词），不会触发上屏。
     */
    private fun buildSearchBox(): View {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            scaleType = ImageView.ScaleType.CENTER
            tintDefaultIcon(this)
        }
        searchText = EditText(context).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            background = null
            isSingleLine = true
            // **不抢 Android 焦点**：这是 IME 自己窗口里的控件，一旦 requestFocus 系统会把
            // 它当成新的输入目标，触发输入法切换 / 页面跳转，且按键会被它吞掉，
            // 导致「只能打英文、打着打着跳到别的页面」。输入统一由服务侧的
            // handleSoftKey 路由，这里只负责显示文本，光标由 [caret] 自绘。
            isCursorVisible = false
            isFocusable = false
            isFocusableInTouchMode = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false
            setTextColor(textSecondary)
            setHintTextColor(textSecondary)
            hint = context.getString(R.string.quick_panel_search_hint)
        }
        caret = View(context).apply {
            setBackgroundColor(primary)
            visibility = View.INVISIBLE
        }
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
            tintDefaultIcon(this)
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
            // 输入焦点落在内部 EditText（而不是整行）：键盘按键经服务进入面板关键词，
            // EditText 负责绘制光标，整行只做点击命中与焦点描边
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
                searchText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
            )
            addView(
                caret,
                LinearLayout.LayoutParams(dp(2), dp(18)).apply { marginStart = dp(1) }
            )
            addView(
                View(context),
                LinearLayout.LayoutParams(0, 1, 1f)
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

    /** 搜索框背景：内部 EditText 获得输入焦点时描一圈主题色边框，其余时候是普通胶囊底色。 */
    private fun searchBoxBackground(): StateListDrawable {
        val focused = rounded(pillBg, radiusKeyDp).apply { setStroke(dp(1), primary) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            // 焦点实际在内部 EditText 上，由它的 focus listener 把整行置为 selected
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

    /** 默认态图标统一着灰色（与 SoftKeyboardView 的 hintColor 一致）；激活态不调用本方法。 */
    private fun tintDefaultIcon(icon: ImageView) {
        icon.imageTintList = ColorStateList.valueOf(iconDefault)
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
