package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ispeech.R
import com.ispeech.engine.LookupCandidate

/**
 * 候选条：[组合码] [候选 1..9（横向滚动）] [翻页器] [齿轮]
 *
 * - 左侧高亮显示当前组合码 / 状态文本
 * - 首选候选用搜狗橙 + 浅橙底，其余候选纯黑；候选词一律常规字重（不加粗，VOCA-56）
 * - 候选只显示词条文本，不再渲染「拼」「应用」「短语」等来源标签
 * - 右侧翻页器显示「1/3」，支持上一页 / 下一页（候选多于一页时出现）；
 *   翻页箭头是复刻搜狗五笔的矢量图（`ic_page_prev` / `ic_page_next`），不再是 ‹ › 字符
 * - 点击候选回调 [onCandidatePicked]，由服务 pickCandidate 上屏
 * - 长按候选回调 [onCandidateLongPressed]，由服务 rememberCandidate「记住这个词」（自造词）
 *
 * 静态骨架在 res/layout/candidate_view.xml 中声明，候选项按查询结果动态创建。
 */
class CandidateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击第 index 个候选 */
    var onCandidatePicked: ((Int) -> Unit)? = null

    /** 长按第 index 个候选：记住这个词（自造词，对齐搜狗 `AddPhrase:Pinyin:`） */
    var onCandidateLongPressed: ((Int) -> Unit)? = null

    /** 点击齿轮（设置入口） */
    var onSettingsClick: (() -> Unit)? = null

    /** 候选上一页 */
    var onPagePrev: (() -> Unit)? = null

    /** 候选下一页 */
    var onPageNext: (() -> Unit)? = null

    private lateinit var codeLabel: TextView
    private lateinit var scrollView: HorizontalScrollView
    private lateinit var itemContainer: LinearLayout
    private lateinit var settingsButton: ImageView
    private lateinit var pager: LinearLayout
    private lateinit var pagePrev: ImageView
    private lateinit var pageIndicator: TextView
    private lateinit var pageNext: ImageView

    override fun onFinishInflate() {
        super.onFinishInflate()
        codeLabel = findViewById(R.id.candidate_code)
        scrollView = findViewById(R.id.candidate_scroll)
        itemContainer = findViewById(R.id.candidate_items)
        settingsButton = findViewById(R.id.candidate_settings)
        pager = findViewById(R.id.candidate_pager)
        pagePrev = findViewById(R.id.candidate_page_prev)
        pageIndicator = findViewById(R.id.candidate_page_indicator)
        pageNext = findViewById(R.id.candidate_page_next)

        // 组合码：参考图是纯黑常规字，无底色
        codeLabel.setTextColor(CODE_TEXT)
        settingsButton.setOnClickListener { onSettingsClick?.invoke() }
        pagePrev.setOnClickListener { onPagePrev?.invoke() }
        pageNext.setOnClickListener { onPageNext?.invoke() }
        pageIndicator.setOnClickListener { onPageNext?.invoke() }
        pager.visibility = View.GONE
    }

    /**
     * 刷新候选条。
     *
     * @param code 当前组合码（a-z）/ 状态文本，为空时隐藏左侧编码
     * @param list 当前页候选列表（服务侧最多 9 条，对应数字键 1-9）
     * @param page 当前页下标（0 起）
     * @param pageCount 总页数
     */
    fun update(
        code: String,
        list: List<LookupCandidate>,
        page: Int = 0,
        pageCount: Int = 1,
    ) {
        codeLabel.text = code
        codeLabel.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE

        itemContainer.removeAllViews()
        list.forEachIndexed { index, candidate ->
            itemContainer.addView(createItemView(index, candidate), itemParams())
        }

        if (list.isEmpty()) {
            scrollView.visibility = View.GONE
        } else {
            scrollView.visibility = View.VISIBLE
            // 新一批候选从最左侧开始展示
            scrollView.post { scrollView.scrollTo(0, 0) }
        }

        // 翻页器：只有多于一页时才出现
        if (pageCount > 1) {
            pager.visibility = View.VISIBLE
            pageIndicator.text = "${page + 1}/$pageCount"
            pagePrev.alpha = if (page > 0) 1f else DISABLED_ALPHA
            pageNext.alpha = if (page < pageCount - 1) 1f else DISABLED_ALPHA
        } else {
            pager.visibility = View.GONE
        }
    }

    /** 单个候选：display 文本，首选带底色高亮（不再绘制右上角数字角标）。 */
    private fun createItemView(index: Int, candidate: LookupCandidate): View {
        val highlighted = index == 0

        val label = TextView(context).apply {
            text = candidate.display
            textSize = 22f
            // 候选词常规字重：加粗会让候选条整体发沉，也压不住首选与非首选的字重差（VOCA-56）
            typeface = Typeface.DEFAULT
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) PICKED_TEXT else TEXT_PRIMARY)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(label)
            if (highlighted) {
                background = roundedFill(PICKED_TEXT, RADIUS_ITEM_DP, ALPHA_ITEM_BG)
            }
            isClickable = true
            isFocusable = true
            contentDescription = candidate.display
            setOnClickListener { onCandidatePicked?.invoke(index) }
            setOnLongClickListener {
                val handler = onCandidateLongPressed ?: return@setOnLongClickListener false
                handler(index)
                true
            }
        }
    }

    private fun itemParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    /** 圆角填充：主色按 [bgAlpha] 降低不透明度后作为底色 */
    private fun roundedFill(color: Int, radiusDp: Int, bgAlpha: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(bgAlpha, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private companion object {
        private const val RADIUS_ITEM_DP = 8
        private const val ALPHA_ITEM_BG = 38
        private const val DISABLED_ALPHA = 0.35f

        /** 首选候选：搜狗橙 */
        private val PICKED_TEXT = Color.parseColor("#FF6A00")

        /** 普通候选 / 组合码：纯黑 */
        private val TEXT_PRIMARY = Color.parseColor("#000000")
        private val CODE_TEXT = Color.parseColor("#000000")
    }
}
