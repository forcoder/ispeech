package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private val starActive = Color.parseColor("#F5A623")

    // ── 尺寸 ──
    private val maxPanelHeightDp = 300
    private val maxRows = 200
    private val radiusKeyDp = 8

    // ── 视图（build() 中创建）──
    private lateinit var infoLabel: TextView
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
     */
    fun submitUsage(starredIds: Set<String>, recentIds: Set<String>) {
        this.starredIds = HashSet(starredIds)
        this.recentIds = HashSet(recentIds)
        runOnMain { render() }
    }

    /** 筛选关键词（来自软键盘字母键）：实时过滤。 */
    fun setKeyword(keyword: String) {
        filterKeyword = keyword
        runOnMain { render() }
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
        val out = ArrayList<QuickEntry>()
        for (entry in allEntries) {
            if (category.source != null && entry.source != category.source) continue
            if (category.kind != null && entry.kind != category.kind) continue
            if (category.usage == UsageFilter.STARRED && !isStarred(entry)) continue
            if (category.usage == UsageFilter.RECENT && !isRecent(entry)) continue
            if (keyword.isNotEmpty() && !matchesKeyword(entry, keyword)) continue
            out.add(entry)
        }
        return out
    }

    private fun matchesKeyword(entry: QuickEntry, keyword: String): Boolean =
        entry.trigger.contains(keyword, ignoreCase = true) ||
            entry.label.contains(keyword, ignoreCase = true) ||
            entry.content.contains(keyword, ignoreCase = true)

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
        infoLabel.text = when {
            keyword.isNotEmpty() -> "筛选：$keyword"
            category.usage == UsageFilter.STARRED -> "★ 收藏 ${results.size} 条"
            category.usage == UsageFilter.RECENT -> "最近 ${results.size} 条"
            else -> "共 ${allEntries.size} 条"
        }
        renderPills()
        renderList(results)
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
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(
                label,
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
    private fun createStarView(entry: QuickEntry, id: String): TextView {
        val starred = id in starredIds
        return TextView(context).apply {
            text = if (starred) "★" else "☆"
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(if (starred) starActive else textSecondary)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            minWidth = dp(36)
            minHeight = dp(36)
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
        val back = TextView(context).apply {
            text = "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
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
