package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.ispeech.input.keyboard.KeyAction
import com.ispeech.input.keyboard.KeyDef
import com.ispeech.input.keyboard.KeyStyle
import com.ispeech.input.keyboard.KeyboardLayoutKind
import com.ispeech.input.keyboard.KeyboardLayouts
import com.ispeech.input.keyboard.KeyboardPage

/**
 * 软键盘面板：搜狗风格多页键盘（字母 / 数字 / 符号 / 表情 / 九宫格）。
 *
 * 结构（自上而下）：
 * 1. 工具栏（8 键，窄屏可横向滚动）：输入法切换、中英切换、符号、表情、快捷短语、剪贴板、AI、设置
 * 2. 页码条：仅当当前布局多于 1 页时显示（如「符号 1/2」），点按翻页
 * 3. 布局行：由 [KeyboardLayouts] 提供，按键按 weight 分配宽度
 *
 * 交互：按键按下显示放大预览；长按带 alt 的键弹出候选气泡；长按退格连删；
 * Shift 单击切一次大写、双击锁定大写。视图不解释按键语义，
 * 一律通过 [onKey] 回调交给 IspeechImeService。
 */
class SoftKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 按键回调：只上报按键定义，语义由服务决定 */
    var onKey: ((KeyDef) -> Unit)? = null

    /** 键盘高度（dp），由设置页控制 */
    var keyHeightDp: Int = DEFAULT_KEY_HEIGHT_DP
        set(value) {
            val clamped = value.coerceIn(MIN_KEY_HEIGHT_DP, MAX_KEY_HEIGHT_DP)
            if (clamped == field) return
            field = clamped
            rebuild()
        }

    /** 震动反馈开关 */
    var vibrate: Boolean = true

    /** 当前布局种类 */
    var layoutKind: KeyboardLayoutKind = KeyboardLayoutKind.LETTER
        private set

    /** 是否英文态（字母页切换为英文标点行） */
    var englishMode: Boolean = false
        private set

    /** Shift 是否开启（一次性大写） */
    var shiftOn: Boolean = false
        private set

    /** Caps Lock 是否锁定 */
    var capsLock: Boolean = false
        private set

    private var pages: List<KeyboardPage> = emptyList()
    private var pageIndex: Int = 0

    private var shiftKey: TextView? = null

    private var previewPopup: PopupWindow? = null
    private var altPopup: PopupWindow? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private var lastShiftClickAt: Long = 0L

    // ── 颜色 ──
    private val keyBg = Color.parseColor("#FFFFFF")
    private val keyBgPressed = Color.parseColor("#D3D9E3")
    private val specialBg = Color.parseColor("#C4CAD5")
    private val specialBgPressed = Color.parseColor("#AAB2C0")
    private val accentBg = Color.parseColor("#E4E7FF")
    private val accentBgPressed = Color.parseColor("#C9CFFF")
    private val dangerBg = Color.parseColor("#FFE3E3")
    private val dangerBgPressed = Color.parseColor("#FFC9C9")
    private val primary = Color.parseColor("#5B6CFF")
    private val primaryPressed = Color.parseColor("#4657D6")
    private val textColor = Color.parseColor("#1A1A1A")
    private val dangerText = Color.parseColor("#C0392B")
    private val toolbarBg = Color.parseColor("#EEF0F4")
    private val pageBarBg = Color.parseColor("#E3E6EC")

    // ── 尺寸 ──
    private val keyMargin = 3
    private val keyRadius = 8

    private fun dp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics,
    )

    private fun dpInt(v: Int): Int = dp(v).toInt()

    // ──────────────────────────────────────────────
    //  对外 API
    // ──────────────────────────────────────────────

    /**
     * 切换布局（如 数字 / 符号 / 表情 / 九宫格），[page] 为目标页（越界自动收敛）。
     *
     * 服务侧把 [KeyDef.pageOffset] 透传进来即可「直达某页」（九宫格的 `#` / `☺` 与工具栏的「颜」
     * 都靠它跳页）；不传 [page] 时行为与旧版完全一致（第 0 页）。
     */
    fun setLayout(kind: KeyboardLayoutKind, page: Int = 0) {
        layoutKind = kind
        pages = KeyboardLayouts.pages(kind, englishMode)
        pageIndex = if (pages.isEmpty()) 0 else page.coerceIn(0, pages.size - 1)
        rebuild()
    }

    /** 中英切换：字母页内容随语言变化，其他布局保持当前页 */
    fun setEnglishMode(english: Boolean) {
        if (english == englishMode) return
        englishMode = english
        if (layoutKind == KeyboardLayoutKind.LETTER) {
            setLayout(KeyboardLayoutKind.LETTER, pageIndex)
        } else {
            rebuild()
        }
    }

    /** 可见页数 */
    fun pageCount(): Int = pages.size

    /** 当前页下标（0 起） */
    fun currentPage(): Int = pageIndex

    /** 翻页：[offset] 为步长，越界时循环到另一端 */
    fun movePage(offset: Int) {
        if (pages.size <= 1) return
        val next = pageIndex + offset
        pageIndex = when {
            next < 0 -> pages.size - 1
            next >= pages.size -> 0
            else -> next
        }
        rebuild()
    }

    /** Shift 单击切一次大写；双击锁定 / 解锁大写 */
    fun toggleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftClickAt <= DOUBLE_TAP_MS) {
            capsLock = !capsLock
            shiftOn = capsLock
        } else {
            shiftOn = !shiftOn
            if (!shiftOn) capsLock = false
        }
        lastShiftClickAt = now
        rebuild()
    }

    /** 重置大写状态（英文上屏后由服务调用） */
    fun resetShift() {
        if (!shiftOn && !capsLock) return
        shiftOn = false
        capsLock = false
        lastShiftClickAt = 0L
        rebuild()
    }

    override fun onDetachedFromWindow() {
        stopRepeatDelete()
        dismissPreview()
        dismissAltPopup()
        super.onDetachedFromWindow()
    }

    // ──────────────────────────────────────────────
    //  构建
    // ──────────────────────────────────────────────

    private fun rebuild() {
        // 重建会移除所有按键 View：此时触摸中的退格键收不到 ACTION_UP/CANCEL，
        // 必须先停掉连删回调，否则重复删除会一直跑下去（多指触摸时尤其危险）。
        stopRepeatDelete()
        removeAllViews()
        shiftKey = null

        addView(buildToolbar())

        if (pages.size > 1) {
            addView(buildPageBar())
        }

        val page = pages.getOrNull(pageIndex)
        if (page != null) {
            val rowPadding = dpInt(3)
            for (row in page.rows) {
                addView(buildRow(row, rowPadding))
            }
        }
        addView(buildBottomPadding())
    }

    /**
     * 工具栏（8 键）：输入法切换 / 中英 / 符号 / 表情 / 短语 / 剪贴板 / AI / 设置。
     *
     * 8 键在 393dp 屏上每个约 42dp，但在 320dp 窄屏上会掉到 40dp 以下，所以外面套一层
     * [HorizontalScrollView]（不换行、不加第二行）：
     * - 放得下时 `isFillViewport = true`，权重把整行铺满，与旧版视觉一致；
     * - 放不下时横向滚动，每个键仍有 [TOOLBAR_KEY_MIN_WIDTH_DP] = 40dp 的可点宽度。
     *
     * 九宫格入口不在这里：它在数字页底行（见 `KeyboardLayouts.numbers()`），
     * 颜文字跟在表情页末尾（见 `KeyboardLayouts.emoji()`），页码条可以翻到。
     */
    private fun buildToolbar(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        for (key in toolbarKeys()) {
            row.addView(
                createKey(
                    key = key,
                    textSize = TOOLBAR_TEXT_SIZE,
                    heightPx = dpInt(TOOLBAR_HEIGHT_DP),
                    minWidthPx = dpInt(TOOLBAR_KEY_MIN_WIDTH_DP),
                    fillWidth = false,
                ),
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(dpInt(3), dpInt(3), dpInt(3), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(toolbarBg)
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    private fun toolbarKeys(): List<KeyDef> = listOf(
        KeyDef.fn(
            KeyAction.SWITCH_IME,
            "⌨输入法",
            weight = 1.6f,
            style = KeyStyle.SPECIAL,
        ),
        KeyDef.fn(KeyAction.MODE_SWITCH, "中/英", weight = 1.3f, style = KeyStyle.ACCENT),
        KeyDef.fn(
            KeyAction.LAYOUT,
            "符",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            layout = KeyboardLayoutKind.SYMBOL,
        ),
        KeyDef.fn(
            KeyAction.LAYOUT,
            "☺",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            layout = KeyboardLayoutKind.EMOJI,
        ),
        KeyDef.fn(KeyAction.PANEL_QUICK_PHRASE, "短语", weight = 1.3f, style = KeyStyle.ACCENT),
        KeyDef.fn(KeyAction.PANEL_CLIPBOARD, "剪贴", weight = 1.2f, style = KeyStyle.SPECIAL),
        KeyDef.fn(KeyAction.AI, "AI", weight = 1.1f, style = KeyStyle.ACCENT),
        KeyDef.fn(KeyAction.SETTINGS, "⚙", weight = 1f, style = KeyStyle.SPECIAL),
    )

    /** 页码条：仅多页布局显示，点按翻到下一页 */
    private fun buildPageBar(): View {
        val page = pages.getOrNull(pageIndex)
        return TextView(context).apply {
            text = buildString {
                append(page?.title ?: layoutKind.label)
                append("  ")
                append(pageIndex + 1)
                append('/')
                append(pages.size)
                append("  点此翻页")
            }
            textSize = 11f
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = keySelector(pageBarBg, specialBgPressed)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpInt(PAGE_BAR_HEIGHT_DP)).apply {
                setMargins(dpInt(3), dpInt(3), dpInt(3), 0)
            }
            setOnClickListener {
                haptic(it)
                movePage(1)
            }
        }
    }

    private fun buildRow(row: List<KeyDef>, padding: Int): View {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(padding, 0, padding, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for (key in row) {
            line.addView(createKey(key, keyTextSize(key), dpInt(keyHeightDp)))
        }
        return line
    }

    /** 底部留白：避免最后一行贴到输入法窗口下沿 */
    private fun buildBottomPadding(): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpInt(4))
    }

    private fun keyTextSize(key: KeyDef): Float = when (key.style) {
        KeyStyle.SPACE -> 15f
        KeyStyle.SPECIAL, KeyStyle.PRIMARY, KeyStyle.ACCENT, KeyStyle.DANGER -> 17f
        KeyStyle.LETTER -> if (key.weight > 1.4f) 16f else 21f
    }

    // ──────────────────────────────────────────────
    //  按键
    // ──────────────────────────────────────────────

    /**
     * 创建一个按键 View。
     *
     * @param minWidthPx 最小宽度（工具栏用 [TOOLBAR_KEY_MIN_WIDTH_DP] 兜底可点面积；0 = 不额外限制）
     * @param fillWidth true = 宽度 0dp + weight 由布局行均分；false = wrap_content + weight
     *   （工具栏专用：放得下时靠 [HorizontalScrollView] 的 isFillViewport 铺满，放不下时滚动）
     */
    private fun createKey(
        key: KeyDef,
        textSize: Float,
        heightPx: Int,
        minWidthPx: Int = 0,
        fillWidth: Boolean = true,
    ): TextView {
        val view = TextView(context).apply {
            text = keyLabel(key)
            this.textSize = textSize
            setTextColor(textColorOf(key.style))
            if (key.style == KeyStyle.ACCENT || key.style == KeyStyle.PRIMARY) {
                typeface = Typeface.DEFAULT_BOLD
            }
            gravity = Gravity.CENTER
            maxLines = 1
            isClickable = true
            isFocusable = true
            background = backgroundFor(key.style)
            if (minWidthPx > 0) minWidth = minWidthPx
            layoutParams = LayoutParams(
                if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                heightPx,
                key.weight,
            ).apply {
                setMargins(dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin))
            }
        }

        if (key.action == KeyAction.SHIFT) {
            shiftKey = view
            updateShiftVisual()
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showPreview(v, keyLabel(key))
                    if (key.action == KeyAction.BACKSPACE) startRepeatDelete()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dismissPreview()
                    if (key.action == KeyAction.BACKSPACE) stopRepeatDelete()
                }
            }
            false
        }

        if (key.alt.isNotEmpty()) {
            view.setOnLongClickListener { v ->
                haptic(v)
                showAltPopup(v, key.alt)
                true
            }
        }

        view.setOnClickListener { v ->
            haptic(v)
            onKey?.invoke(key)
        }
        return view
    }

    /** 键面文字：字母键在 shift / caps 开启时显示大写 */
    private fun keyLabel(key: KeyDef): String {
        val label = key.label.ifEmpty { key.output }
        if (key.action != KeyAction.CHAR || label.length != 1) return label
        val ch = label[0]
        if (ch !in 'a'..'z' && ch !in 'A'..'Z') return label
        return if (shiftOn || capsLock) ch.uppercaseChar().toString() else ch.lowercaseChar().toString()
    }

    private fun haptic(view: View) {
        if (vibrate) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // ── 长按连删 ──

    private fun startRepeatDelete() {
        if (repeatRunnable != null) return
        val runnable = object : Runnable {
            private var ticks = 0

            override fun run() {
                ticks++
                onKey?.invoke(KeyDef.fn(KeyAction.BACKSPACE, "⌫"))
                val delay = if (ticks < REPEAT_FAST_AFTER) REPEAT_SLOW_MS else REPEAT_FAST_MS
                repeatHandler.postDelayed(this, delay)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, REPEAT_START_DELAY_MS)
    }

    private fun stopRepeatDelete() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ── 按键预览气泡 ──

    private fun showPreview(anchor: View, label: String) {
        dismissPreview()
        val content = TextView(context).apply {
            text = label
            textSize = 22f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            background = roundedRect(keyBg, keyRadius)
            minWidth = dpInt(38)
            setPadding(dpInt(8), dpInt(4), dpInt(8), dpInt(4))
        }
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dpInt(PREVIEW_HEIGHT_DP),
            false,
        )
        popup.isOutsideTouchable = false
        previewPopup = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val y = (location[1] - dpInt(PREVIEW_HEIGHT_DP)).coerceAtLeast(0)
        runCatching { popup.showAtLocation(anchor, Gravity.NO_GRAVITY, location[0], y) }
            .onFailure { previewPopup = null }
    }

    private fun dismissPreview() {
        previewPopup?.let { runCatching { it.dismiss() } }
        previewPopup = null
    }

    // ── 长按候选气泡（alt） ──

    private fun showAltPopup(anchor: View, alternatives: List<String>) {
        dismissAltPopup()
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dpInt(4), dpInt(4), dpInt(4), dpInt(4))
            background = roundedRect(Color.WHITE, keyRadius)
        }
        for (alt in alternatives) {
            strip.addView(
                TextView(context).apply {
                    text = alt
                    textSize = 20f
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    background = keySelector(accentBg, accentBgPressed)
                    layoutParams = LayoutParams(dpInt(ALT_KEY_WIDTH_DP), dpInt(ALT_KEY_HEIGHT_DP)).apply {
                        setMargins(dpInt(2), dpInt(2), dpInt(2), dpInt(2))
                    }
                    setOnClickListener { v ->
                        haptic(v)
                        dismissAltPopup()
                        onKey?.invoke(KeyDef.char(alt))
                    }
                },
            )
        }
        val popup = PopupWindow(
            strip,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        popup.setOnDismissListener { altPopup = null }
        altPopup = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val y = (location[1] - dpInt(ALT_KEY_HEIGHT_DP + 10)).coerceAtLeast(0)
        runCatching { popup.showAtLocation(anchor, Gravity.NO_GRAVITY, location[0], y) }
            .onFailure { altPopup = null }
    }

    private fun dismissAltPopup() {
        altPopup?.let { runCatching { it.dismiss() } }
        altPopup = null
    }

    // ── 视觉 ──

    private fun updateShiftVisual() {
        val key = shiftKey ?: return
        val on = shiftOn || capsLock
        key.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedRect(primaryPressed, keyRadius))
            addState(intArrayOf(), roundedRect(if (on) primary else specialBg, keyRadius))
        }
        key.setTextColor(if (on) Color.WHITE else textColor)
        key.text = if (capsLock) "⇪" else "⇧"
    }

    private fun textColorOf(style: KeyStyle): Int = when (style) {
        KeyStyle.ACCENT -> primary
        KeyStyle.PRIMARY -> Color.WHITE
        KeyStyle.DANGER -> dangerText
        else -> textColor
    }

    private fun backgroundFor(style: KeyStyle): StateListDrawable = when (style) {
        KeyStyle.SPECIAL -> keySelector(specialBg, specialBgPressed)
        KeyStyle.SPACE -> keySelector(keyBg, keyBgPressed)
        KeyStyle.ACCENT -> keySelector(accentBg, accentBgPressed)
        KeyStyle.PRIMARY -> keySelector(primary, primaryPressed)
        KeyStyle.DANGER -> keySelector(dangerBg, dangerBgPressed)
        KeyStyle.LETTER -> keySelector(keyBg, keyBgPressed)
    }

    private fun keySelector(normalColor: Int, pressedColor: Int): StateListDrawable {
        val normal = roundedRect(normalColor, keyRadius)
        val pressed = roundedRect(pressedColor, keyRadius)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_selected), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun roundedRect(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private companion object {
        const val DEFAULT_KEY_HEIGHT_DP = 52
        const val MIN_KEY_HEIGHT_DP = 40
        const val MAX_KEY_HEIGHT_DP = 72
        const val TOOLBAR_HEIGHT_DP = 34

        /** 工具栏字号：8 键 + 40dp 最小宽度下，「⌨输入法」「中/英」「短语」都放得下。 */
        const val TOOLBAR_TEXT_SIZE = 12f

        /**
         * 工具栏单键最小宽度（dp）。
         *
         * 8 键在 393dp 屏上约 42dp/键没问题，但 320dp 窄屏会被压到 33dp，
         * 所以用这个下限 + 工具栏外层 [HorizontalScrollView] 兜底：每键始终 >= 40dp，放不下就横滑。
         */
        const val TOOLBAR_KEY_MIN_WIDTH_DP = 40
        const val PAGE_BAR_HEIGHT_DP = 22
        const val PREVIEW_HEIGHT_DP = 40
        const val ALT_KEY_WIDTH_DP = 40
        const val ALT_KEY_HEIGHT_DP = 40

        /** Shift 双击判定窗口（毫秒） */
        const val DOUBLE_TAP_MS = 320L

        const val REPEAT_START_DELAY_MS = 380L
        const val REPEAT_SLOW_MS = 90L
        const val REPEAT_FAST_MS = 45L
        const val REPEAT_FAST_AFTER = 6
    }

    // init 必须位于所有属性声明之后：Kotlin 按声明顺序初始化，
    // 若在 keyHeightDp / 颜色 / pages 赋值前构建键盘，按键会退化为 0 高度。
    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#E8EBF0"))
        }
        pages = KeyboardLayouts.pages(layoutKind, englishMode)
        rebuild()
    }
}
