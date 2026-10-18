package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.ispeech.R
import com.ispeech.settings.store.AsrEngine
import com.ispeech.settings.store.AsrLanguage
import com.ispeech.settings.store.AsrSettings
import com.ispeech.settings.store.KeyboardSettings

/**
 * 输入法内置「配置面板」（搜狗风格的功能设置）。
 *
 * 形态与 [InputMethodPanel] 一致：标题行 + 限高滚动列表 + 底部「更多设置」入口。
 * 面板本身不做任何持久化，也不改任何状态：开关 / 引擎变更都以回调上抛，
 * 由 [IspeechImeService] 写 DataStore 并重建引擎（与其它面板「视图不管系统调用」的分工一致）。
 */
class SettingsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击返回 / 关闭。 */
    var onClose: (() -> Unit)? = null

    /** 点击「更多设置」：拉起完整设置页（词典 / 短语 / 账号 / 主题等）。 */
    var onOpenFullSettings: (() -> Unit)? = null

    /** 键盘布尔开关变更：(键名, 新值)。 */
    var onToggle: ((String, Boolean) -> Unit)? = null

    /** 语音识别设置变更（引擎 / 语言）。 */
    var onAsrChange: ((AsrSettings) -> Unit)? = null

    // ── 颜色（与 InputMethodPanel 保持同一套）──
    private val panelBg = Color.parseColor("#F4F5F7")
    private val textPrimary = Color.parseColor("#1A1A1A")
    private val textSecondary = Color.parseColor("#777777")
    private val dividerColor = Color.parseColor("#E0E3E8")
    private val rowBg = Color.parseColor("#FFFFFF")
    private val pressedBg = Color.parseColor("#D3D9E3")
    private val primaryColor = Color.parseColor("#5B6CFF")

    private val maxPanelHeightDp = 260

    private val listContainer: LinearLayout = LinearLayout(context).apply { orientation = VERTICAL }
    private var keyboard: KeyboardSettings = KeyboardSettings()
    private var asr: AsrSettings = AsrSettings()

    /** 渲染期间置位，避免 Switch 复原时反触发 onCheckedChange。 */
    private var rendering = false

    init {
        orientation = VERTICAL
        setBackgroundColor(panelBg)
        build()
    }

    /** 一条开关项：持久化键 + 文案 + 当前值。 */
    private data class ToggleSpec(val key: String, val labelRes: Int, val checked: Boolean)

    /** 灌入当前设置并重建列表（服务侧在打开面板 / 设置变化时调用）。 */
    fun submit(keyboard: KeyboardSettings, asr: AsrSettings) {
        this.keyboard = keyboard
        this.asr = asr
        render()
    }

    // ── 构建 ──

    private fun build() {
        addView(buildTitleRow(), matchWrap())
        addView(createDividerView(), dividerParams())
        addView(buildList(), matchWrap())
        addView(createDividerView(), dividerParams())
        addView(buildActionRow(), matchWrap())
        render()
    }

    private fun buildTitleRow(): View {
        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = context.getString(R.string.btn_close)
            setOnClickListener { onClose?.invoke() }
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.settings_panel_title)
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
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(6) },
            )
        }
    }

    private fun buildList(): View {
        return BoundedScrollView(context, dp(maxPanelHeightDp)).apply {
            isVerticalScrollBarEnabled = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(
                listContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun buildActionRow(): View {
        val more = TextView(context).apply {
            text = context.getString(R.string.settings_panel_more)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
            isClickable = true
            isFocusable = true
            background = keySelector(rowBg, pressedBg)
            contentDescription = context.getString(R.string.settings_panel_more)
            setOnClickListener { onOpenFullSettings?.invoke() }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            addView(more, LinearLayout.LayoutParams(dp(120), dp(30)))
        }
    }

    // ── 渲染 ──

    private fun render() {
        rendering = true
        listContainer.removeAllViews()

        toggles().forEach { spec -> listContainer.addView(buildToggleRow(spec), rowParams()) }

        listContainer.addView(createDividerView(), dividerParams())
        listContainer.addView(
            buildChoiceRow(
                label = context.getString(R.string.asr_engine_label),
                value = engineLabel(asr.engine),
                onClick = {
                    val next = if (asr.engine == AsrEngine.SYSTEM) AsrEngine.CUSTOM else AsrEngine.SYSTEM
                    onAsrChange?.invoke(asr.copy(engine = next))
                },
            ),
            rowParams(),
        )
        listContainer.addView(
            buildChoiceRow(
                label = context.getString(R.string.asr_language_label),
                value = asr.language.label,
                onClick = {
                    val entries = AsrLanguage.entries
                    val next = entries[(entries.indexOf(asr.language) + 1) % entries.size]
                    onAsrChange?.invoke(asr.copy(language = next))
                },
            ),
            rowParams(),
        )

        rendering = false
    }

    private fun toggles(): List<ToggleSpec> = listOf(
        ToggleSpec("chinesePunctuation", R.string.panel_toggle_chinese_punct, keyboard.chinesePunctuation),
        ToggleSpec("fullWidth", R.string.panel_toggle_full_width, keyboard.fullWidth),
        ToggleSpec("association", R.string.panel_toggle_association, keyboard.association),
        ToggleSpec("learning", R.string.panel_toggle_learning, keyboard.learning),
        ToggleSpec("pinyinMixed", R.string.panel_toggle_pinyin_mixed, keyboard.pinyinMixed),
        ToggleSpec("fuzzyPinyin", R.string.panel_toggle_fuzzy_pinyin, keyboard.fuzzyPinyin),
        ToggleSpec("traditionalOutput", R.string.panel_toggle_traditional, keyboard.traditionalOutput),
        ToggleSpec("vibrate", R.string.panel_toggle_vibrate, keyboard.vibrate),
    )

    private fun buildToggleRow(spec: ToggleSpec): View {
        val label = TextView(context).apply {
            text = context.getString(spec.labelRes)
            textSize = 14f
            setTextColor(textPrimary)
            setPadding(dp(10), dp(4), dp(4), dp(4))
        }
        val toggle = Switch(context).apply {
            isChecked = spec.checked
            contentDescription = context.getString(spec.labelRes)
            setOnCheckedChangeListener { _, value ->
                if (!rendering) onToggle?.invoke(spec.key, value)
            }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = keySelector(rowBg, pressedBg)
            setPadding(dp(2), dp(2), dp(8), dp(2))
            addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** 只读展示行：左侧标题，右侧当前值（点击切换下一项）。 */
    private fun buildChoiceRow(label: String, value: String, onClick: () -> Unit): View {
        val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(textPrimary)
        }
        val valueView = TextView(context).apply {
            text = "$value ›"
            textSize = 13f
            setTextColor(primaryColor)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = keySelector(rowBg, pressedBg)
            isClickable = true
            isFocusable = true
            contentDescription = "$label：$value"
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onClick() }
            addView(
                labelView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                valueView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun engineLabel(engine: AsrEngine): String = context.getString(
        when (engine) {
            AsrEngine.SYSTEM -> R.string.asr_engine_system
            AsrEngine.CUSTOM -> R.string.asr_engine_custom
        },
    )

    // ── 局部私有辅助 ──

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun rowParams(): LinearLayout.LayoutParams = matchWrap().apply {
        topMargin = dp(3)
        bottomMargin = dp(3)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun createDividerView(): View =
        View(context).apply { setBackgroundColor(dividerColor) }

    private fun dividerParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))

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
