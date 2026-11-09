package com.ispeech.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.ispeech.R
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
 * 1. 工具栏（8 键，窄屏可横向滚动）：输入法切换、符号、表情、快捷短语、剪贴板、AI、语音、设置
 * 2. 页码条：仅当当前布局多于 1 页时显示（如「符号 1/2」），点按翻页
 * 3. 布局行：由 [KeyboardLayouts] 提供，按键按 weight 分配宽度
 *
 * 交互：按键按下显示放大预览；长按带 alt 的键弹出候选气泡；长按退格连删；
 * 字母页 Shift 键单击切一次大写、双击锁定大写（中英切换由底行「中/英」键承担）。
 * 视图不解释按键语义，
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

    /** 是否拼音态（中文态下工具栏「输入法切换」键面显示「拼」，五笔态显示「五」）。 */
    var pinyinMode: Boolean = true
        private set

    /** Shift 是否开启（一次性大写） */
    var shiftOn: Boolean = false
        private set

    /** Caps Lock 是否锁定 */
    var capsLock: Boolean = false
        private set

    private var pages: List<KeyboardPage> = emptyList()
    private var pageIndex: Int = 0

    private var shiftKey: ImageView? = null

    private var previewPopup: PopupWindow? = null
    private var altPopup: PopupWindow? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private var lastShiftClickAt: Long = 0L

    // ── 颜色（搜狗风格：白键 + 浅灰功能键 + 柔和阴影） ──
    private val keyBg = Color.parseColor("#FFFFFF")
    private val keyBgPressed = Color.parseColor("#E6E8EB")
    private val specialBg = Color.parseColor("#CDD1D8")
    private val specialBgPressed = Color.parseColor("#BFC4CC")
    private val accentBg = Color.parseColor("#FFFFFF")
    private val accentBgPressed = Color.parseColor("#DCE1E8")
    private val dangerBg = Color.parseColor("#FFFFFF")
    private val dangerBgPressed = Color.parseColor("#DCE1E8")
    private val primary = Color.parseColor("#4C7DF0")
    private val primaryPressed = Color.parseColor("#3A66D0")
    private val textColor = Color.parseColor("#000000")
    private val dangerText = Color.parseColor("#C0392B")
    private val hintColor = Color.parseColor("#9AA0A6")
    private val pageBarBg = Color.parseColor("#CDD1D8")

    // ── 尺寸 ──
    private val keyMargin = 4
    private val keyRadius = 10

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

    /** 当前中文输入法状态：影响「中/英」键面文案与工具栏状态键面（EN / 拼 / 五）。 */
    fun setPinyinMode(pinyin: Boolean) {
        if (pinyin == pinyinMode) return
        pinyinMode = pinyin
        rebuild()
    }

    /** 工具栏状态键面：英文态 EN，中文态按拼音 / 五笔分别是「拼」/「五」。 */
    private fun imeSwitchLabel(): String = when {
        englishMode -> "EN"
        pinyinMode -> "拼"
        else -> "五"
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
     * 工具栏（8 键）：输入法切换 / 符号 / 表情 / 短语 / 剪贴板 / AI / 语音 / 设置。
     *
     * 中英切换不在这里：它由字母页左下角的 Shift 键承担（见 [createKey]）。
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
            setPadding(dpInt(3), dpInt(4), dpInt(3), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    /**
     * 工具栏 8 键。
     *
     * [KeyDef.label] 保留原文（无障碍朗读 + 按下预览气泡文案），键面外观改由
     * [KeyDef.iconRes] 的矢量图标承担 —— 图标按几何复刻搜狗 Android 版
     * `assets/theme/theme_default/1080/res/black/ime_function_*.png`（见 `docs/icon-replication.md` §8）。
     *
     * 两处**刻意保持文字**：
     * - 「符」：搜狗侧没有对应的符号键图标（`space_symbol` 是空格键上的提示、`more_shortcuts_symbols`
     *   是「＋自定义」），硬造一个图形是失真，故仍是文字；
     * - 「中/英」在字母页底行、不在工具栏，且搜狗侧同为文字（见 [modeSwitchLabel]）。
     */
    private fun toolbarKeys(): List<KeyDef> = listOf(
        KeyDef.fn(
            KeyAction.PANEL_QUICK_PHRASE,
            "短语",
            weight = 1.3f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_phrase,
        ),
        KeyDef.fn(
            KeyAction.AI,
            "AI",
            weight = 1.1f,
            style = KeyStyle.SPECIAL,
        ),
        KeyDef.fn(
            KeyAction.SWITCH_IME,
            "输",
            weight = 1f,
            style = KeyStyle.SPECIAL,
        ),
        // 第 4 键显示「当前输入态」：拼音 拼 / 英文 EN / 五笔 五，点击在中文 / 英文之间切换
        KeyDef.fn(
            KeyAction.MODE_SWITCH,
            "中/英",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            statusLabel = true,
        ),
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
            iconRes = R.drawable.ic_emoji,
        ),
        KeyDef.fn(
            KeyAction.PANEL_CLIPBOARD,
            "剪贴",
            weight = 1.2f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_clipboard,
        ),
        KeyDef.fn(
            KeyAction.VOICE,
            "语音",
            weight = 1.1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_voice,
        ),
        KeyDef.fn(
            KeyAction.SETTINGS,
            "⚙",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_settings_gear,
        ),
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
            textSize = 12f
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
        KeyStyle.SPACE -> 16f
        KeyStyle.SPECIAL, KeyStyle.PRIMARY, KeyStyle.ACCENT, KeyStyle.DANGER -> 18f
        // 标点 / 符号字号比字母键小一号：全角标点（，。？！）按字母字号 23sp 渲染会显得过大
        KeyStyle.PUNCT -> 20f
        KeyStyle.LETTER -> if (key.weight > 1.4f) 18f else 23f
    }

    // ──────────────────────────────────────────────
    //  按键
    // ──────────────────────────────────────────────

    /**
     * 创建一个按键 View。
     *
     * 中文态（[englishMode] 为 false）下，字母页左下角的 Shift 键按搜狗习惯改造成「中/英」：
     * 仅改 action / label / style，点击上报 [KeyAction.MODE_SWITCH]（服务侧 `handleSoftKey`
     * 已有 `MODE_SWITCH -> toggleMode()` 分支，无需改服务）；英文态保持 SHIFT 行为与外观。
     *
     * 「中/英」键面按当前输入态分色：当前态的那个字用正常色，**另一态的字变灰**
     * （见 [modeSwitchLabel]），一眼能看出现在打的是中文还是英文。
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
    ): View {
        // 中英切换已按搜狗五笔布局移到字母页底行的「中/英」键，Shift 键不再被改造成中/英：
        // 三种大写状态（未开 / 单击 / 锁定）在中文态与英文态下外观一致。
        val effectiveKey = key

        // 键面矢量图标：Shift 键用三态箭头（ic_shift / ic_shift_active / ic_shift_lock），
        // 其余带 [KeyDef.iconRes] 的键（工具栏、符号页光标键）用各自图标。改用矢量而不画字形
        // 的原因同 Shift：不同 ROM 把 ⌨ ☺ 🎤 ⚙ ⬅ ➡ 这些字形画得粗细不一，当文字渲染容易显得粗糙。
        // 图标按几何复刻搜狗 Android 版的 `ime_function_*.png`（见 docs/icon-replication.md §8）。
        //
        // 「中/英」键不走此分支：它是文字键（搜狗侧同样是文字），由下面的 TextView 分色渲染。
        val iconView: ImageView? = if (
            effectiveKey.action == KeyAction.SHIFT || effectiveKey.iconRes != 0
        ) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                // [keyLabel] 仍是无障碍朗读与按下预览的文案来源（图标本身没有可读文字）
                contentDescription = if (effectiveKey.action == KeyAction.SHIFT) {
                    "大写"
                } else {
                    keyLabel(effectiveKey)
                }
                isClickable = true
                isFocusable = true
                background = backgroundFor(effectiveKey.style, effectiveKey.action, effectiveKey.layout)
                if (effectiveKey.iconRes != 0) {
                    setImageResource(effectiveKey.iconRes)
                    // 图标随键样式着色，并把按下态高亮出来 —— 等价于搜狗成对提供的 x / x_p 两张图。
                    // Shift 不参与：它三态各有一张自带配色的 drawable，由 [updateShiftVisual] 切换。
                    imageTintList = context.getColorStateList(
                        if (effectiveKey.style == KeyStyle.ACCENT) {
                            R.color.key_icon_tint_accent
                        } else {
                            R.color.key_icon_tint
                        },
                    )
                }
                if (fillWidth) elevation = dp(1)
                // 图标键的宽度来自 drawable 固有尺寸（24dp），必须补上最小可点面积，
                // 否则工具栏图标键会比同排文字键窄一圈，低于 40dp 触控规范。
                if (minWidthPx > 0) minimumWidth = minWidthPx
                layoutParams = LayoutParams(
                    if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                    heightPx,
                    effectiveKey.weight,
                ).apply {
                    setMargins(dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin))
                }
            }
        } else {
            null
        }

        val view: View = iconView ?: TextView(context).apply {
            // 「中/英」键面走分色渲染；其余键面仍是纯文本（[keyLabel] 负责大小写）
            text = when {
                // 工具栏第 4 键：单色显示当前输入态（拼 / EN / 五）
                effectiveKey.action == KeyAction.MODE_SWITCH && effectiveKey.statusLabel ->
                    imeSwitchLabel()

                // 字母页底行「中/英」：两态分色
                effectiveKey.action == KeyAction.MODE_SWITCH -> modeSwitchLabel()
                else -> keyLabel(effectiveKey)
            }
            this.textSize = textSize
            setTextColor(textColorOf(effectiveKey.style))
            if (effectiveKey.style == KeyStyle.ACCENT || effectiveKey.style == KeyStyle.PRIMARY) {
                typeface = Typeface.DEFAULT_BOLD
            }
            gravity = Gravity.CENTER
            maxLines = 1
            isClickable = true
            isFocusable = true
            background = backgroundFor(effectiveKey.style, effectiveKey.action, effectiveKey.layout)
            // 布局行按键加一点柔和投影，形成「白键浮在灰底上」的搜狗观感
            if (fillWidth) elevation = dp(1)
            if (minWidthPx > 0) minWidth = minWidthPx
            layoutParams = LayoutParams(
                if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                heightPx,
                effectiveKey.weight,
            ).apply {
                setMargins(dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin))
            }
        }

        // 为字母键添加 hint（数字/符号提示，搜狗风格）
        val container: View = if (effectiveKey.style == KeyStyle.LETTER && effectiveKey.hint.isNotEmpty()) {
            // 提示键外层用 FrameLayout 叠一行小字：外层必须承担布局权重 / 高度 / 边距，
            // 内层主键与提示各自 MATCH_PARENT。绝不能给外层套 MATCH_PARENT ——
            // 水平 LinearLayout 里十个 MATCH_PARENT 子键会互相挤压到不可见（整行字母消失）。
            FrameLayout(context).apply {
                // elevation 抬「容器」而不是「内层主键」：内层主键若单独带 elevation，
                // 在 FrameLayout 里按 Z 序会盖住提示小字（hint 的 elevation 为 0），提示就看不见了。
                if (fillWidth) elevation = dp(1)
                layoutParams = LayoutParams(
                    if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                    heightPx,
                    effectiveKey.weight,
                ).apply {
                    setMargins(dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin))
                }
                view.elevation = 0f
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    TextView(context).apply {
                        text = effectiveKey.hint
                        this.textSize = 11f
                        setTextColor(hintColor)
                        gravity = Gravity.TOP or Gravity.END
                        setPadding(0, dpInt(2), dpInt(2), 0)
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                    },
                )
            }
        } else {
            view
        }

        if (effectiveKey.action == KeyAction.SHIFT) {
            shiftKey = iconView
            updateShiftVisual()
        }

        val targetView = if (container is TextView) container else view
        targetView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Shift 键面是矢量箭头图标，按下再弹一个 ⇧ 字形反而突兀，故不显示放大预览
                    if (effectiveKey.action != KeyAction.SHIFT) showPreview(v, keyLabel(effectiveKey))
                    if (effectiveKey.action == KeyAction.BACKSPACE) startRepeatDelete()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dismissPreview()
                    if (effectiveKey.action == KeyAction.BACKSPACE) stopRepeatDelete()
                }
            }
            false
        }

        if (effectiveKey.alt.isNotEmpty()) {
            targetView.setOnLongClickListener { v ->
                haptic(v)
                showAltPopup(v, effectiveKey.alt)
                true
            }
        }

        targetView.setOnClickListener { v ->
            haptic(v)
            onKey?.invoke(effectiveKey)
        }

        // 语音键改走「按住说话」手势，覆盖上面的点击回调
        // （touch 返回 true 后系统不再判定 click，onClick 不会再触发）
        if (effectiveKey.action == KeyAction.VOICE) {
            installVoiceGesture(targetView)
        }
        // 空格键：短按走上面的点击回调（正常上屏空格），按住 ≥1s 改走语音
        if (effectiveKey.action == KeyAction.SPACE) {
            installSpaceGesture(targetView, effectiveKey)
        }
        return container
    }

    /**
     * 空格键「按住 ≥1s 说话」手势（搜狗语义）。
     *
     * 短按（不足 [getSpaceLongPressMs()]）抬手时照常派发 [onKey]，空格上屏行为不变；
     *
     * @see getSpaceLongPressMs
     * 长按到点先派发 [onSpaceLongPress] 进入语音，抬手再派发 [onSpaceLongPressEnd] 收尾上屏。
     *
     * 必须自己接管 touch：软键盘是自绘 View，按键事件不经过
     * `InputMethodService.onKeyUp`，服务侧拿不到「松手」时机。
     */
    private fun installSpaceGesture(target: View, key: KeyDef) {
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showPreview(v, keyLabel(key))
                    spaceLongPressFired = false
                    spaceLongPressRunnable?.let { spaceHandler.removeCallbacks(it) }
                    val runnable = Runnable {
                        spaceLongPressFired = true
                        haptic(v)
                        onSpaceLongPress?.invoke()
                    }
                    spaceLongPressRunnable = runnable
                    spaceHandler.postDelayed(runnable, spaceLongPressMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dismissPreview()
                    spaceLongPressRunnable?.let { spaceHandler.removeCallbacks(it) }
                    spaceLongPressRunnable = null
                    if (spaceLongPressFired) {
                        spaceLongPressFired = false
                        onSpaceLongPressEnd?.invoke()
                    } else {
                        haptic(v)
                        onKey?.invoke(key)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被打断：语音已开始就收尾上屏，否则什么都不做
                    dismissPreview()
                    spaceLongPressRunnable?.let { spaceHandler.removeCallbacks(it) }
                    spaceLongPressRunnable = null
                    if (spaceLongPressFired) {
                        spaceLongPressFired = false
                        onSpaceLongPressEnd?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 键面文字：字母键在 shift / caps 开启时显示大写 */
    private fun keyLabel(key: KeyDef): String {
        val label = key.label.ifEmpty { key.output }
        if (key.action != KeyAction.CHAR || label.length != 1) return label
        val ch = label[0]
        if (ch !in 'a'..'z' && ch !in 'A'..'Z') return label
        // 中文态（拼音 / 五笔）键面固定大写，与搜狗一致：Shift 只影响英文态的大小写
        if (!englishMode) return ch.uppercaseChar().toString()
        return if (shiftOn || capsLock) ch.uppercaseChar().toString() else ch.lowercaseChar().toString()
    }

    /**
     * 「中/英」键面：**当前输入态的那个字用正常色，另一态的字变灰**，中间分隔符保持弱色。
     *
     * 状态源就是 [englishMode]（由服务侧 `toggleMode()` 经 [setEnglishMode] 驱动），
     * 切一次中英会重建键盘，键面配色随之刷新。
     * [keyLabel] 仍返回纯文本，供按键预览气泡与无障碍朗读使用。
     */
    private fun modeSwitchLabel(): CharSequence {
        // 中文态统一显示「中」，英文态固定「中/英」；不再用「拼」区分拼音输入
        val zh = "中"
        return SpannableString("$zh/英").apply {
            val zhColor = if (englishMode) hintColor else textColor
            val enColor = if (englishMode) textColor else hintColor
            setSpan(ForegroundColorSpan(zhColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(hintColor), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(enColor), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 语音键按下(true) / 抬起(false)：服务侧据此开始 / 结束收音。 */
    var onVoicePress: ((Boolean) -> Unit)? = null

    /** 语音键上滑进入(true) / 离开(false)「取消区」：服务侧据此切换面板提示。 */
    var onVoiceCancelArmed: ((Boolean) -> Unit)? = null

    /** 当前是否处于上滑取消区（抬手时据此决定丢弃还是上屏）。 */
    private var voiceCancelArmed = false

    /** 空格键按住 ≥1s：服务侧据此进入语音输入（搜狗「长按空格说话」）。 */
    var onSpaceLongPress: (() -> Unit)? = null

    /** 空格键按住进入语音的阈值（毫秒），由服务侧按设置推入（0.5s / 1s）。 */
    var spaceLongPressMs: Long = DEFAULT_SPACE_LONG_PRESS_MS

    /** 长按空格进入语音后抬手：服务侧据此收尾上屏。 */
    var onSpaceLongPressEnd: (() -> Unit)? = null

    private val spaceHandler = Handler(Looper.getMainLooper())

    /** 长按计时器：到点才置位，抬手时据此决定「上屏空格」还是「结束语音」。 */
    private var spaceLongPressRunnable: Runnable? = null

    private var spaceLongPressFired = false

    /**
     * 语音键的「按住说话」手势（搜狗语义）：按下开始收音、抬起结束上屏、
     * 上滑到按键上方一定距离进入「取消区」（抬手即丢弃）。
     *
     * 事件一律消费（返回 true），因此语音键不会走 [KeyAction] 的点击路径：
     * 收音的开始 / 结束完全由按下 / 抬起决定，不需要服务侧再区分点击。
     *
     * [MotionEvent.ACTION_CANCEL] 按「松手结束」处理而不是「取消」：手势被打断
     * （窗口切换 / 键盘重建）时若什么都不做，服务侧会一直停在「收音中」，下一次
     * 按下反而因为「已在收音」而不重新开始，语音就再也出不来了。
     */
    private fun installVoiceGesture(target: View) {
        voiceCancelArmed = false
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    voiceCancelArmed = false
                    onVoiceCancelArmed?.invoke(false)
                    onVoicePress?.invoke(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手指上滑超过阈值即进入取消区（抬手前可以反悔滑回来）
                    val armed = event.y < -dpInt(VOICE_CANCEL_THRESHOLD_DP)
                    if (armed != voiceCancelArmed) {
                        voiceCancelArmed = armed
                        onVoiceCancelArmed?.invoke(armed)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    onVoicePress?.invoke(false)
                    true
                }
                else -> false
            }
        }
        target.setOnClickListener(null)
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
        // 用矢量图标表示三种状态：未开（描边箭头）/ 单击大写（实心箭头）/ 锁定（箭头 + 横杠）
        key.setImageResource(
            when {
                capsLock -> R.drawable.ic_shift_lock
                shiftOn -> R.drawable.ic_shift_active
                else -> R.drawable.ic_shift
            }
        )
    }

    private fun textColorOf(style: KeyStyle): Int = when (style) {
        KeyStyle.ACCENT -> primary
        KeyStyle.PRIMARY -> Color.WHITE
        KeyStyle.DANGER -> dangerText
        else -> textColor
    }

    private fun backgroundFor(
        style: KeyStyle,
        action: KeyAction,
        layout: KeyboardLayoutKind?,
    ): StateListDrawable = when (style) {
        KeyStyle.SPECIAL -> if (
            action == KeyAction.PANEL_QUICK_PHRASE ||
            action == KeyAction.AI ||
            action == KeyAction.SWITCH_IME ||
            action == KeyAction.MODE_SWITCH ||
            (action == KeyAction.LAYOUT && (layout == KeyboardLayoutKind.SYMBOL || layout == KeyboardLayoutKind.EMOJI)) ||
            action == KeyAction.PANEL_CLIPBOARD ||
            action == KeyAction.VOICE ||
            action == KeyAction.SETTINGS
        ) {
            toolbarKeySelector()
        } else {
            keySelector(specialBg, specialBgPressed)
        }
        KeyStyle.SPACE -> keySelector(keyBg, keyBgPressed)
        KeyStyle.ACCENT -> keySelector(accentBg, accentBgPressed)
        KeyStyle.PRIMARY -> keySelector(primary, primaryPressed)
        KeyStyle.DANGER -> keySelector(dangerBg, dangerBgPressed)
        KeyStyle.LETTER, KeyStyle.PUNCT -> keySelector(keyBg, keyBgPressed)
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

    private fun toolbarKeySelector(): StateListDrawable {
        val normal = ColorDrawable(Color.TRANSPARENT)
        val selected = roundedRect(primaryPressed, keyRadius)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }
    }

    private fun roundedRect(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private companion object {

        /** 语音键上滑取消的触发距离（dp）：手指滑出按键上方这么远即视为「松开取消」。 */
        const val VOICE_CANCEL_THRESHOLD_DP = 60

        /** 空格长按进入语音的默认阈值（毫秒）；实际值由服务侧按设置推入 [spaceLongPressMs]。 */
        const val DEFAULT_SPACE_LONG_PRESS_MS = 500L
        const val DEFAULT_KEY_HEIGHT_DP = 52
        const val MIN_KEY_HEIGHT_DP = 40
        const val MAX_KEY_HEIGHT_DP = 72
        const val TOOLBAR_HEIGHT_DP = 34

        /** 工具栏字号：8 键 + 40dp 最小宽度下，「⌨」「短语」「🎤」都放得下。 */
        const val TOOLBAR_TEXT_SIZE = 12f

        /**
         * 工具栏单键最小宽度（dp）。
         *
         * 8 键在 393dp 屏上约 42dp/键没问题，但 320dp 窄屏会被压到 40dp 以下，
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
            setColor(Color.parseColor("#F2F3F5"))
        }
        pages = KeyboardLayouts.pages(layoutKind, englishMode)
        rebuild()
    }
}
