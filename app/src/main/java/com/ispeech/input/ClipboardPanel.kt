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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 剪贴板面板：标题行 + 操作行 + 历史列表。
 *
 * 结构：
 * 1. 标题行：[‹ 返回] 「剪贴板」
 * 2. 操作行：撤销上屏 / 读取剪贴板 / 清空（回调服务，面板本身不读剪贴板）
 * 3. 列表：ScrollView（最高 240dp），每行 [单行文本 | × 删除]
 * 4. 空状态：「剪贴板为空」
 *
 * 历史内容由服务通过 [submit] 灌入；「×」只做本地 UI 删除，等下次 submit 覆盖。
 */
class ClipboardPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击某条：回调文本，由服务上屏。 */
    var onInsert: ((String) -> Unit)? = null

    /** 点击返回键 / 关闭。 */
    var onClose: (() -> Unit)? = null

    /** 请求服务读取系统剪贴板（服务读完后调用 submit）。 */
    var onRefresh: (() -> Unit)? = null

    /** 撤销上一次上屏。 */
    var onUndo: (() -> Unit)? = null

    /** 清空历史。 */
    var onClear: (() -> Unit)? = null

    // ── 数据（本地副本，id 保证删除后监听不会错位）──
    private var clipItems: MutableList<Entry> = ArrayList()
    private var nextId: Long = 0L

    // ── 颜色 ──
    private val panelBg = Color.parseColor("#F4F5F7")
    private val textPrimary = Color.parseColor("#1A1A1A")
    private val textSecondary = Color.parseColor("#777777")
    private val dividerColor = Color.parseColor("#E0E3E8")
    private val rowBg = Color.parseColor("#FFFFFF")
    private val pressedBg = Color.parseColor("#D3D9E3")

    // ── 尺寸 ──
    private val maxPanelHeightDp = 240
    private val radiusKeyDp = 8

    private lateinit var listContainer: LinearLayout

    /** 剪贴板历史条目，id 用于「×」按条目删除而不依赖列表下标。 */
    private class Entry(val id: Long, val text: String)

    // ── 冻结 API ──

    /** 数据到达：重建列表（可能从非主线程调用，内部切主线程渲染）。 */
    fun submit(items: List<String>) {
        val rebuilt = ArrayList<Entry>(items.size)
        for (text in items) {
            rebuilt.add(Entry(nextId++, text))
        }
        clipItems = rebuilt
        runOnMain { render() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else post { block() }
    }

    // ── 渲染 ──

    private fun render() {
        listContainer.removeAllViews()
        if (clipItems.isEmpty()) {
            listContainer.addView(
                createEmptyView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }
        for (entry in clipItems) {
            listContainer.addView(createRowView(entry), clipRowParams())
        }
    }

    private fun clipRowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(3)
        bottomMargin = dp(3)
    }

    /** 单行：文本（单行省略，换行显示为「⏎」）+ 「×」删除 */
    private fun createRowView(entry: Entry): View {
        val label = TextView(context).apply {
            text = flatten(entry.text)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textPrimary)
            setPadding(dp(10), dp(9), dp(6), dp(9))
        }
        val delete = TextView(context).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            isClickable = true
            isFocusable = true
            background = keySelector(Color.TRANSPARENT, pressedBg)
            contentDescription = "删除这条"
        }

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = keySelector(rowBg, pressedBg)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = flatten(entry.text)
        row.addView(
            label,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        row.addView(
            delete,
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) }
        )
        row.setOnClickListener { onInsert?.invoke(entry.text) }
        delete.setOnClickListener { removeEntry(entry, row) }
        return row
    }

    /** 本地 UI 删除：只改面板，不回调服务；服务下次 submit 会覆盖。 */
    private fun removeEntry(entry: Entry, row: View) {
        clipItems.removeAll { it.id == entry.id }
        (row.parent as? ViewGroup)?.removeView(row)
        if (clipItems.isEmpty()) render()
    }

    private fun createEmptyView(): TextView = TextView(context).apply {
        text = "剪贴板为空"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(textSecondary)
        setPadding(dp(12), dp(28), dp(12), dp(28))
    }

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
        addView(createDividerView(), dividerParams())
        addView(
            buildActionRow(),
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
            text = "剪贴板"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setTextColor(textPrimary)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
            addView(back, LinearLayout.LayoutParams(dp(34), dp(30)))
            addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(6) }
            )
        }
    }

    private fun buildActionRow(): View {
        val undo = createActionView("撤销上屏") { onUndo?.invoke() }
        val refresh = createActionView("读取剪贴板") { onRefresh?.invoke() }
        val clear = createActionView("清空") {
            // 立刻清掉本地副本，界面即时反馈；服务侧清空后会用 submit 覆盖
            clipItems = ArrayList<Entry>()
            render()
            onClear?.invoke()
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
            addView(undo, actionParams())
            addView(refresh, actionParams())
            addView(clear, actionParams())
        }
    }

    private fun createActionView(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(textPrimary)
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = label
            setOnClickListener { action() }
        }

    private fun actionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }

    private fun buildList(): View {
        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        return ClipboardBoundedScrollView(context, dp(maxPanelHeightDp)).apply {
            isVerticalScrollBarEnabled = true
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(
                listContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun createDividerView(): View =
        View(context).apply { setBackgroundColor(dividerColor) }

    private fun dividerParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(1)
    )

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
    // 若在颜色 / 尺寸 / 半径赋值前构建列表，行高与圆角都会退化为 0。
    init {
        orientation = VERTICAL
        setBackgroundDrawable(rounded(panelBg, 0))
        build()
    }
}

/**
 * ScrollView：高度随内容自适应，但不超过 [maxHeightPx]，避免剪贴板历史撑高键盘。
 */
private class ClipboardBoundedScrollView(
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
