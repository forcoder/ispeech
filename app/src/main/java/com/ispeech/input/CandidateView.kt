package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.ispeech.R
import com.ispeech.engine.LookupCandidate

/**
 * 候选条：[组合码] [候选（横向滑动）] [麦克风授权按钮（仅未授权提示态）]
 *
 * - 左侧高亮显示当前组合码 / 状态文本；「联想」「语音」等来源状态不再渲染到候选条
 * - 首选候选带底色高亮，每项右上角带 1-9 数字角标
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

    /**
     * 点击麦克风授权按钮（VOCA-166）。
     *
     * 只由指针点击触发：不接空格 / 回车 / 数字键（那些走上屏首选候选的语义，
     * 会把授权动作变成「打字途中被踢出输入场景」）。
     */
    var onMicActionClicked: (() -> Unit)? = null

    /** 键盘震动开关（与 SoftKeyboardView 同源，由服务侧推入）：授权按钮按下要有可感知反馈 */
    var vibrate = true

    private lateinit var codeLabel: TextView
    private lateinit var scrollView: HorizontalScrollView
    private lateinit var itemContainer: LinearLayout
    private lateinit var micAction: TextView

    /** 主题色 ispeech_primary */
    private val primaryColor: Int = resolvePrimaryColor()

    override fun onFinishInflate() {
        super.onFinishInflate()
        codeLabel = findViewById(R.id.candidate_code)
        scrollView = findViewById(R.id.candidate_scroll)
        itemContainer = findViewById(R.id.candidate_items)
        micAction = findViewById(R.id.candidate_mic_action)

        // 组合码用主题色高亮，底色是同色低透明度
        codeLabel.setTextColor(primaryColor)
        codeLabel.background = roundedFill(primaryColor, RADIUS_CODE_DP, ALPHA_CODE_BG)

        // 授权按钮：近黑文字（底色 #F4F5F7 上 15:1，达 WCAG AA；首选橙只有 2.59:1，不可用），
        // 底色用同色低透明度圆角，与其它控件一致；不依赖颜色表达动作语义（文字即动作）
        micAction.setTextColor(ACTION_TEXT)
        micAction.background = roundedFill(ACTION_TEXT, RADIUS_ITEM_DP, ALPHA_ACTION_BG)
        micAction.isClickable = true
        micAction.isFocusable = true
        micAction.setOnClickListener {
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onMicActionClicked?.invoke()
        }
    }

    /**
     * 刷新候选条。
     *
     * @param code 当前组合码（a-z）/ 状态文本，为空时隐藏左侧编码
     * @param list 候选列表（服务侧按「每屏候选数 × 倍数」取回，横向滑动浏览）
     * @param actionLabel 麦克风授权按钮文案；非空即显示按钮，null 即隐藏。
     *   **不由 [code] 是否为空推断**：常态第 1 行是用户正在打的组合码，不能被当成授权态。
     */
    fun update(code: String, list: List<LookupCandidate>, actionLabel: String? = null) {
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

        renderMicAction(actionLabel)
    }

    /**
     * 授权按钮的显示与置灰。
     *
     * 跳转在途时置灰（`isEnabled = false`）：连点两次会弹两个授权框，
     * 下一次 [update] 会按服务侧的实时状态重新决定文案与可用性。
     */
    fun setMicActionEnabled(enabled: Boolean) {
        micAction.isEnabled = enabled
        micAction.alpha = if (enabled) 1f else ALPHA_DISABLED
    }

    private fun renderMicAction(actionLabel: String?) {
        if (actionLabel == null) {
            micAction.visibility = View.GONE
            // 按钮隐藏时组合码恢复按内容宽度，把横向空间让回候选区
            setCodeLabelWeight(weighted = false)
            return
        }
        micAction.text = actionLabel
        micAction.visibility = View.VISIBLE
        setMicActionEnabled(true)
        // 「请到系统设置…」这类长路径指引要能换行读完整，被裁成半句等于没给指引，
        // 所以提示文本占满剩余宽度（不截断），按钮按内容宽度靠右，不会被挤出屏幕
        setCodeLabelWeight(weighted = true)
    }

    private fun setCodeLabelWeight(weighted: Boolean) {
        val params = codeLabel.layoutParams as LayoutParams
        params.width = if (weighted) 0 else LayoutParams.WRAP_CONTENT
        params.weight = if (weighted) 1f else 0f
        codeLabel.layoutParams = params
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

    /** 单个候选：display 文本 + 右上角数字角标，首选带底色高亮（不渲染来源标签）。 */
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
        private const val BADGE_DP = 14
        private const val ALPHA_CODE_BG = 38
        private const val ALPHA_ITEM_BG = 51

        /** 授权按钮底色透明度：比候选项更浅，避免抢走首选候选的视觉权重 */
        private const val ALPHA_ACTION_BG = 26

        /** 跳转在途时按钮置灰的透明度 */
        private const val ALPHA_DISABLED = 0.4f

        /** 硬件键盘 `-` `=` 一次滚动的距离（约一个候选的宽度） */
        private const val SCROLL_STEP_DP = 72

        /** 兜底主色（主题未提供 ispeech_primary 时使用） */
        private val FALLBACK_PRIMARY = Color.parseColor("#2F6FED")
        private val TEXT_PRIMARY = Color.parseColor("#222222")

        /** 动作文字色：候选条底 #F4F5F7 上对比度 15:1（首选橙只有 2.59:1，不达 WCAG AA） */
        private val ACTION_TEXT = Color.parseColor("#1A1A1A")
    }
}
