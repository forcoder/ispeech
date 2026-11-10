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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AI 参考结果面板：一行一条参考回复。
 *
 * 结构：
 * 1. 标题行：[‹ 返回] 「AI 参考回复」 [共 N 条]
 * 2. 列表：每行只显示前 [PREVIEW_CHARS] 个字，超出用省略号；行高固定，
 *    **超过 [MAX_VISIBLE_ROWS] 条时列表出现滚动条**（ScrollView 限高）。
 * 3. 详情：点某一行即切换到全文视图（可滚动），底部「上屏」「关闭」两个按钮，
 *    「关闭」回到列表，不退出面板。
 *
 * 面板本身不调用后端：结果由服务通过 [submit] 灌入，点击回调 [onPick]，
 * 上屏动作在服务侧（[com.ispeech.input.IspeechImeService.commitAiReply]）。
 */
class AiReplyPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击详情里的「上屏」：回调列表下标。 */
    var onPick: ((Int) -> Unit)? = null

    /** 点击返回 / 关闭：退出面板。 */
    var onClose: (() -> Unit)? = null

    // ── 颜色 ──
    private val panelBg = Color.parseColor("#F2F3F5")
    private val textPrimary = Color.parseColor("#000000")
    private val textSecondary = Color.parseColor("#777777")
    private val rowBg = Color.parseColor("#FFFFFF")
    private val pressedBg = Color.parseColor("#E6E8EB")
    private val primaryColor = Color.parseColor("#5B6CFF")

    // ── 尺寸 ──
    private val rowHeightDp = 38
    private val maxVisibleRows = 10

    private lateinit var titleLabel: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var detailContainer: LinearLayout
    private lateinit var detailScroll: ScrollView
    private lateinit var detailText: TextView
    private lateinit var pickButton: TextView

    private var items: List<String> = emptyList()
    private var detailIndex: Int = -1

    init {
        orientation = VERTICAL
        setBackgroundColor(panelBg)
        build()
    }

    /**
     * 灌入结果：每条一行，超过 [MAX_VISIBLE_ROWS] 条时列表可滚动。
     *
     * @param list 每条参考回复的完整文本（列表只显示前 [PREVIEW_CHARS] 个字）
     */
    fun submit(list: List<String>) {
        items = list
        runOnMain {
            hideDetail()
            renderList()
        }
    }

    /** 标题旁的计数 / 状态文案（生成中、出错等）。 */
    fun setStatus(text: String) {
        runOnMain { titleLabel.text = text }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else post { block() }
    }

    // ──────────────────────────────────────────────
    //  构建
    // ──────────────────────────────────────────────

    private fun build() {
        addView(buildHeader())

        listScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }
        listContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        listScroll.addView(
            listContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(listScroll)

        detailContainer = buildDetail()
        addView(detailContainer)
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))

        addView(textButton("‹") { onClose?.invoke() })

        addView(
            TextView(context).apply {
                text = TITLE
                textSize = 15f
                setTextColor(textPrimary)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)),
        )

        titleLabel = TextView(context).apply {
            text = RESULT_COUNT.format(0)
            textSize = 12f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, dp(32), 1f)
        }
        addView(titleLabel)

        addView(textButton("✕") { onClose?.invoke() })
    }

    /** 详情视图：全文（可滚动）+ 「上屏」「关闭」按钮。默认隐藏。 */
    private fun buildDetail(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = View.GONE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        detailScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(rowHeightDp * maxVisibleRows))
        }
        detailText = TextView(context).apply {
            textSize = 15f
            setTextColor(textPrimary)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRow(rowBg)
        }
        detailScroll.addView(
            detailText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(detailScroll)

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(6), dp(4), dp(6), dp(6))

                pickButton = textButton("上屏", primaryColor) {
                    val index = detailIndex
                    if (index >= 0) onPick?.invoke(index)
                }
                addView(pickButton)
                addView(textButton("关闭") { hideDetail() })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    // ──────────────────────────────────────────────
    //  渲染
    // ──────────────────────────────────────────────

    private fun renderList() {
        listScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        listContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            listContainer.addView(
                TextView(context).apply {
                    text = preview(item)
                    textSize = 15f
                    setTextColor(textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(10), 0)
                    background = rowSelector()
                    isClickable = true
                    isFocusable = true
                    contentDescription = preview(item)
                    setOnClickListener { showDetail(index) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(rowHeightDp)).apply {
                    setMargins(dp(4), dp(1), dp(4), dp(1))
                },
            )
        }
        val rows = items.size.coerceAtMost(maxVisibleRows)
        listScroll.layoutParams = (listScroll.layoutParams as LayoutParams).apply {
            height = dp(rows * rowHeightDp + 4)
        }
        listScroll.isVerticalScrollBarEnabled = items.size > maxVisibleRows
        titleLabel.text = if (items.isEmpty()) TITLE_EMPTY else RESULT_COUNT.format(items.size)
    }

    private fun showDetail(index: Int) {
        val full = items.getOrNull(index) ?: return
        detailIndex = index
        detailText.text = full
        detailScroll.scrollTo(0, 0)
        listScroll.visibility = View.GONE
        detailContainer.visibility = View.VISIBLE
        pickButton.isEnabled = true
        pickButton.alpha = 1f
        titleLabel.text = DETAIL_TITLE
    }

    private fun hideDetail() {
        detailIndex = -1
        detailContainer.visibility = View.GONE
        listScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        titleLabel.text = if (items.isEmpty()) TITLE_EMPTY else RESULT_COUNT.format(items.size)
    }

    /** 列表行只展示前 [PREVIEW_CHARS] 个字，换行折叠成空格。 */
    private fun preview(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (flat.length <= PREVIEW_CHARS) return flat
        return flat.substring(0, PREVIEW_CHARS) + "…"
    }

    // ──────────────────────────────────────────────
    //  小工具
    // ──────────────────────────────────────────────

    private fun textButton(
        label: String,
        color: Int = textSecondary,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        isClickable = true
        isFocusable = true
        background = rowSelector()
        setOnClickListener { onClick() }
    }

    private fun rowSelector(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundedRow(pressedBg))
        addState(intArrayOf(), roundedRow(rowBg))
    }

    private fun roundedRow(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val TITLE = "AI 参考回复"
        const val TITLE_EMPTY = "AI 参考回复 · 无结果"
        const val DETAIL_TITLE = "AI 参考回复 · 详情"
        const val RESULT_COUNT = "共 %d 条"

        /** 列表行预览字数：超出部分用省略号，点行看全文。 */
        const val PREVIEW_CHARS = 12
    }
}