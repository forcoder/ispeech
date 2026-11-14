package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ispeech.R
import com.ispeech.engine.LookupCandidate
import com.ispeech.engine.LookupKind

/**
 * 候选条：[组合码] [候选 1..9（横向滚动）] [翻页器] [齿轮]
 *
 * - 左侧高亮显示当前组合码 / 状态文本
 * - 首选候选带底色高亮，每项右上角带 1-9 数字角标
 * - 非五笔候选（短语 / 快速应用）带来源小标签，便于区分要不要整句上屏
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

    /** 主题色 ispeech_primary */
    private val primaryColor: Int = resolvePrimaryColor()

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

        // 组合码用主题色高亮，底色是同色低透明度
        codeLabel.setTextColor(primaryColor)
        codeLabel.background = roundedFill(primaryColor, RADIUS_CODE_DP, ALPHA_CODE_BG)
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

    /** 单个候选：display 文本 + 可选来源标签 + 右上角数字角标，首选带底色高亮 */
    private fun createItemView(index: Int, candidate: LookupCandidate): View {
        val highlighted = index == 0

        val label = TextView(context).apply {
            text = candidate.display
            textSize = 18f
            // 候选词常规字重：加粗会让候选条整体发沉，也压不住首选与非首选的字重差（VOCA-56）
            typeface = Typeface.DEFAULT
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) primaryColor else TEXT_PRIMARY)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(label)
            if (candidate.kind != LookupKind.WUBI) {
                addView(createKindTag(candidate.kind))
            }
        }

        val badge = TextView(context).apply {
            text = (index + 1).toString()
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = solidFill(primaryColor)
        }

        return FrameLayout(context).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                badge,
                FrameLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP), Gravity.TOP or Gravity.END),
            )
            if (highlighted) {
                background = roundedFill(primaryColor, RADIUS_ITEM_DP, ALPHA_ITEM_BG)
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

    /** 来源小标签（短语 / 应用），提示该候选会整段上屏 */
    private fun createKindTag(kind: LookupKind): TextView = TextView(context).apply {
        text = kind.label
        textSize = 9f
        gravity = Gravity.CENTER
        setTextColor(primaryColor)
        background = roundedFill(primaryColor, RADIUS_TAG_DP, ALPHA_TAG_BG)
        setPadding(dp(4), dp(1), dp(4), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) }
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

    /** 实心圆：数字角标底色 */
    private fun solidFill(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 读取主题色 ispeech_primary；资源缺失时退回默认色，保证候选条始终可用 */
    private fun resolvePrimaryColor(): Int {
        val id = resources.getIdentifier(COLOR_ISPEECH_PRIMARY, "color", context.packageName)
        if (id == 0) return FALLBACK_PRIMARY
        return try {
            resources.getColor(id, context.theme)
        } catch (e: IllegalArgumentException) {
            FALLBACK_PRIMARY
        }
    }

    private companion object {
        private const val COLOR_ISPEECH_PRIMARY = "ispeech_primary"

        private const val RADIUS_CODE_DP = 6
        private const val RADIUS_ITEM_DP = 8
        private const val RADIUS_TAG_DP = 4
        private const val BADGE_DP = 14
        private const val ALPHA_CODE_BG = 38
        private const val ALPHA_ITEM_BG = 51
        private const val ALPHA_TAG_BG = 32
        private const val DISABLED_ALPHA = 0.35f

        /** 兜底主色（主题未提供 ispeech_primary 时使用） */
        private val FALLBACK_PRIMARY = Color.parseColor("#2F6FED")
        private val TEXT_PRIMARY = Color.parseColor("#222222")
    }
}
