package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ispeech.R

/**
 * 输入法切换面板：列出系统里已启用的输入法，点一下就切过去。
 *
 * 结构：
 * 1. 标题行：[‹ 返回] 「切换输入法」 [共 N 个]
 * 2. 列表：ScrollView（最高 240dp），每行 [输入法名 | 「当前」标签]，点行即切换
 * 3. 操作行：[系统选择器]（走系统自带 picker，作为兜底）
 * 4. 空状态：「没有其他输入法」+ 到系统设置启用的提示
 *
 * 面板本身不做任何系统调用：IME 列表由服务通过 [submit] 灌入，点击回调 [onPick]，
 * 真正的切换动作在服务侧（需要输入法窗口 token）。
 */
class InputMethodPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击某条输入法：回调其 id（ComponentName 扁平串），由服务切换。 */
    var onPick: ((String) -> Unit)? = null

    /** 点击返回 / 关闭。 */
    var onClose: (() -> Unit)? = null

    /** 点击「系统选择器」：交给系统自带的输入法选择器。 */
    var onSystemPicker: (() -> Unit)? = null

    // ── 颜色 ──
    private val panelBg = Color.parseColor("#F4F5F7")
    private val textPrimary = Color.parseColor("#1A1A1A")
    private val textSecondary = Color.parseColor("#777777")
    private val dividerColor = Color.parseColor("#E0E3E8")
    private val rowBg = Color.parseColor("#FFFFFF")
    private val pressedBg = Color.parseColor("#D3D9E3")
    private val primaryColor = Color.parseColor("#5B6CFF")

    // ── 尺寸 ──
    private val maxPanelHeightDp = 240

    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView

    private var items: List<InputMethodItem> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(panelBg)
        build()
    }

    /** 一条可切换的输入法。 */
    data class InputMethodItem(val id: String, val label: String, val current: Boolean)

    /** 数据到达：重建列表（可能从非主线程调用，内部切主线程渲染）。 */
    fun submit(list: List<InputMethodItem>) {
        items = list
        runOnMain { render() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else post { block() }
    }

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
            buildList(),
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
            text = "切换输入法"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setTextColor(textPrimary)
        }
        countLabel = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1
            setTextColor(textSecondary)
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
            addView(
                countLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildList(): View {
        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        return BoundedScrollView(context, dp(maxPanelHeightDp)).apply {
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

    private fun buildActionRow(): View {
        val picker = TextView(context).apply {
            text = "系统选择器"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = "打开系统输入法选择器"
            setOnClickListener { onSystemPicker?.invoke() }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            addView(
                picker,
                LinearLayout.LayoutParams(dp(96), dp(30))
            )
        }
    }

    // ── 渲染 ──

    private fun render() {
        countLabel.text = if (items.isEmpty()) "" else "共 ${items.size} 个"
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(
                createEmptyView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }
        for (item in items) {
            listContainer.addView(createRowView(item), rowParams())
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(3)
        bottomMargin = dp(3)
    }

    /** 单行：输入法名（单行省略）+ 「当前」标签；点行切换（当前项点击不做事）。 */
    private fun createRowView(item: InputMethodItem): View {
        val label = TextView(context).apply {
            text = item.label
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (item.current) primaryColor else textPrimary)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = keySelector(rowBg, pressedBg)
            isClickable = true
            isFocusable = true
            contentDescription = if (item.current) "${item.label}（当前）" else item.label
            setOnClickListener { if (!item.current) onPick?.invoke(item.id) }
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        if (item.current) {
            row.addView(
                createTag("当前"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
            )
        }
        return row
    }

    private fun createTag(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(primaryColor)
        background = roundedFill(primaryColor, dp(4), 0x1A)
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    private fun createEmptyView(): View = TextView(context).apply {
        text = "没有其他输入法\n到系统设置 → 语言与输入法 里启用后，这里就能一键切换"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(textSecondary)
        setPadding(dp(12), dp(18), dp(12), dp(18))
    }

    // ── 局部私有辅助 ──

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun createDividerView(): View =
        View(context).apply { setBackgroundColor(dividerColor) }

    private fun dividerParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))

    private fun solidFill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
    }

    private fun roundedFill(color: Int, radius: Int, alpha: Int = 0xFF): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor((alpha shl 24) or (color and 0x00FFFFFF))
        }

    private fun keySelector(normal: Int, pressed: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedFill(pressed, dp(8)))
            addState(intArrayOf(), roundedFill(normal, dp(8)))
        }

    /** 限高滚动容器：内容不足时按内容高，超过上限则滚动。 */
    private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, spec)
        }
    }
}
