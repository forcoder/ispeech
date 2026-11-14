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
import android.widget.LinearLayout
import android.widget.TextView
import com.ispeech.R
import com.ispeech.engine.LookupCandidate

/**
 * 候选条：[组合码] [候选（横向滑动）]
 *
 * - 左侧高亮显示当前组合码 / 状态文本
 * - 首选候选用搜狗橙加粗 + 浅橙底，其余候选纯黑加粗
 * - 候选只显示词条文本，不渲染「拼」「应用」「短语」等来源标签
 * - 候选一条连续横排，左右拖动即可浏览全部候选：不显示翻页箭头、页码器与设置齿轮，
 *   把横向空间全部留给候选词（设置入口在键盘工具栏的齿轮键，见 SoftKeyboardView）
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

    private lateinit var codeLabel: TextView
    private lateinit var scrollView: HorizontalScrollView
    private lateinit var itemContainer: LinearLayout

    override fun onFinishInflate() {
        super.onFinishInflate()
        codeLabel = findViewById(R.id.candidate_code)
        scrollView = findViewById(R.id.candidate_scroll)
        itemContainer = findViewById(R.id.candidate_items)

        // 组合码：参考图是纯黑常规字，无底色
        codeLabel.setTextColor(CODE_TEXT)
    }

    /**
     * 刷新候选条。
     *
     * @param code 当前组合码（a-z）/ 状态文本，为空时隐藏左侧编码
     * @param list 候选列表（服务侧按设置条数上限取回，横向滑动浏览）
     */
    fun update(code: String, list: List<LookupCandidate>) {
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
    }

    /**
     * 横向滚动候选条，[steps] 为正向右（看后面的候选）、为负向左。
     *
     * 触屏上直接拖动候选条就能滚动，这里是给硬件键盘 `-` `=` 留的等价入口。
     */
    fun scrollCandidates(steps: Int) {
        if (steps == 0) return
        scrollView.smoothScrollBy(steps * dp(SCROLL_STEP_DP), 0)
    }

    /** 单个候选：display 文本，首选带底色高亮（不再绘制右上角数字角标）。 */
    private fun createItemView(index: Int, candidate: LookupCandidate): View {
        val highlighted = index == 0

        val label = TextView(context).apply {
            text = candidate.display
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
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

        /** 硬件键盘 `-` `=` 一次滚动的距离（约一个候选的宽度） */
        private const val SCROLL_STEP_DP = 72

        /** 首选候选：搜狗橙 */
        private val PICKED_TEXT = Color.parseColor("#FF6A00")

        /** 普通候选 / 组合码：纯黑 */
        private val TEXT_PRIMARY = Color.parseColor("#000000")
        private val CODE_TEXT = Color.parseColor("#000000")
    }
}
