package com.ispeech.input

import android.content.Context
import android.content.res.ColorStateList
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
import android.view.ViewOutlineProvider
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

    /** 是否拼音态（中文态下「中/英」键面首字显示「拼」，见 [modeSwitchLabel]） */
    var pinyinMode: Boolean = false
        private set

    /** Shift 是否开启（一次性大写） */
    var shiftOn: Boolean = false
        private set

    /** Caps Lock 是否锁定 */
    var capsLock: Boolean = false
        private set

    /** 空格键按住到点：服务侧据此进入语音输入（搜狗「长按空格说话」）。 */
    var onSpaceLongPress: (() -> Unit)? = null

    /** 空格键按住进入语音的阈值（毫秒），由服务侧按设置推入（0.5s / 1s）。 */
    var spaceLongPressMs: Long = DEFAULT_SPACE_LONG_PRESS_MS

    /** 长按空格进入语音后抬手：服务侧据此收尾上屏。 */
    var onSpaceLongPressEnd: (() -> Unit)? = null

    private val spaceHandler = Handler(Looper.getMainLooper())

    /** 长按计时器：到点才置位，抬手时据此决定「上屏空格」还是「结束语音」。 */
    private var spaceLongPressRunnable: Runnable? = null

    private var spaceLongPressFired = false

    private var pages: List<KeyboardPage> = emptyList()
    private var pageIndex: Int = 0

    private var shiftKey: ImageView? = null

    private var previewPopup: PopupWindow? = null
    private var altPopup: PopupWindow? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private var lastShiftClickAt: Long = 0L

    // ── 颜色（对齐搜狗默认皮肤：白键 + 浅灰功能键 + 自上而下的键盘底色渐变） ──
    private val keyBg = Color.parseColor("#FFFFFF")
    private val keyBgPressed = Color.parseColor("#E7E9ED")
    private val specialBg = Color.parseColor("#C9CDD5")
    private val specialBgPressed = Color.parseColor("#BCC1CA")
    private val accentBg = Color.parseColor("#FFFFFF")
    private val accentBgPressed = Color.parseColor("#DDE4EF")
    private val dangerBg = Color.parseColor("#FFFFFF")
    private val dangerBgPressed = Color.parseColor("#DDE4EF")
    private val primary = Color.parseColor("#4C7DF0")
    private val primaryPressed = Color.parseColor("#3A66D0")
    private val textColor = Color.parseColor("#14171C")
    private val dangerText = Color.parseColor("#C0392B")
    private val hintColor = Color.parseColor("#A2A8B2")
    private val spaceTextColor = Color.parseColor("#7C838D")
    private val pageBarBg = Color.parseColor("#D5D9E0")
    private val toolbarPressedBg = Color.parseColor("#E1E5EC")

    // 键盘底色：搜狗默认皮肤是一条自上而下的浅灰渐变（同一台真机上实测 #F2F3F6 → #D6D8E0），
    // 这里取同向但更柔和的渐变：保留「白键浮在浅灰上」的层次，又不至于在底部压暗。
    private val keyboardTop = Color.parseColor("#F4F5F8")
    private val keyboardBottom = Color.parseColor("#E3E6EC")

    // 「换行」键：浅主色胶囊（浅蓝渐变底 + 1dp 主色描边 + 主色 ↵ 图标 / 小字标签），见 docs/keyboard-ui.md §4。
    // 旧版是整块纯蓝 + 18sp 粗体白字：键宽 134px 里字面就占 120px（左右各只剩 2.5dp），
    // 看起来像被字糊满；而且整块高饱和蓝是键盘上唯一的深色大色块，比字母键还抢眼。
    private val enterTop = Color.parseColor("#EFF4FF")
    private val enterBottom = Color.parseColor("#E1EAFE")
    private val enterTopPressed = Color.parseColor("#E4EDFF")
    private val enterBottomPressed = Color.parseColor("#D3E1FA")
    private val enterStroke = Color.parseColor("#C5D7F8")
    private val enterAccent = Color.parseColor("#2E5FD8")

    // ── 尺寸 ──
    // keyMargin 是「非字母页」的默认键缝，取值受 tools/verify/check_keyboard_ui.py 断言保护
    // （VOCA-30 规格 3dp），不要为了 VOCA-184 的加宽去改它；字母页走 KEY_MARGIN_DP=1dp。
    private val keyMargin = 3
    private val keyRadius = 6

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

    /** 拼音 / 五笔切换：只影响「中/英」键面首字文案，字母页与五笔态共用中文布局。 */
    fun setPinyinMode(pinyin: Boolean) {
        if (pinyin == pinyinMode) return
        pinyinMode = pinyin
        if (layoutKind == KeyboardLayoutKind.LETTER) rebuild()
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
            val rowPadding = dpInt(ROW_PADDING_DP)
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
            // 键框底部投影会探出行下沿：放开容器裁剪，否则投影被切掉
            clipChildren = false
            clipToPadding = false
        }
        for (key in toolbarKeys()) {
            row.addView(
                createKey(
                    key = key,
                    textSize = TOOLBAR_TEXT_SIZE,
                    heightPx = dpInt(TOOLBAR_HEIGHT_DP),
                    minWidthPx = dpInt(TOOLBAR_KEY_MIN_WIDTH_DP),
                    fillWidth = false,
                    inToolbar = true,
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
            KeyAction.SWITCH_IME,
            "⌨",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_ime_switch,
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
            KeyAction.PANEL_QUICK_PHRASE,
            "短语",
            weight = 1.3f,
            style = KeyStyle.ACCENT,
            iconRes = R.drawable.ic_phrase,
        ),
        KeyDef.fn(
            KeyAction.PANEL_CLIPBOARD,
            "剪贴",
            weight = 1.2f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_clipboard,
        ),
        KeyDef.fn(
            KeyAction.AI,
            "AI",
            weight = 1.1f,
            style = KeyStyle.ACCENT,
        ),
        KeyDef.fn(
            KeyAction.VOICE,
            "🎤",
            weight = 1.1f,
            style = KeyStyle.ACCENT,
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
            // 键框底部投影会探出键框下沿：放开行容器裁剪，否则投影被切掉
            clipChildren = false
            clipToPadding = false
            setPadding(padding, 0, padding, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        // 字母页按 10 个宽度单位排栅格：第 2 行只有 9 键（asdfghjkl），左右各补半个空位，
        // 让三行字母键上下对齐（搜狗同款居中排布）。不补时这一行每键比其它行宽 8px，行间错位。
        val missing = if (layoutKind == KeyboardLayoutKind.LETTER) {
            LETTER_ROW_UNITS - row.fold(0f) { sum, key -> sum + key.weight }
        } else {
            0f
        }
        val center = missing > 0.01f
        if (center) line.addView(buildRowSpacer(missing / 2f))
        for (key in row) {
            line.addView(createKey(key, keyTextSize(key), dpInt(keyHeightDp)))
        }
        if (center) line.addView(buildRowSpacer(missing / 2f))
        // 字母页第 2 行左右各有一个透明补位，子 View 下标与 row 下标会错开：
        // 必须把「补位个数」传进去，否则缝隙兜底会把事件路由到错误的键。
        installRowGutterHit(line, row, if (center) 1 else 0)
        return line
    }

    /**
     * 行级「零死区」兜底：键与键之间的窄缝、行内左右留白（以及圆角外露的角点）没有
     * 子 View 接收事件，落到本行容器上。这里用 [KeyHitTester]（缝中线归属 + 扩展上限）
     * 把事件路由到相邻键，模拟一次点击，保证缝隙不出现「点了没反应」。
     *
     * 键框内（含四角 / 四边 / hint 小字区）的事件仍由各键自己的触控逻辑处理，
     * 本监听器只在空隙兜底，不接管键内手势（语音 / 空格长按 / 退格连删 / alt 长按）。
     *
     * @param firstKeyIndex 行首键在子 View 里的下标（字母页第 2 行前面有补位，为 1）
     */
    private fun installRowGutterHit(row: LinearLayout, keys: List<KeyDef>, firstKeyIndex: Int) {
        if (keys.isEmpty()) return
        var pending = -1
        val rects = ArrayList<KeyHitRect>(keys.size)
        row.setOnTouchListener { _, event ->
            rects.clear()
            for (i in keys.indices) {
                val c = row.getChildAt(firstKeyIndex + i) ?: continue
                rects.add(
                    KeyHitRect(
                        i.toString(),
                        c.left.toFloat(),
                        c.top.toFloat(),
                        c.right.toFloat(),
                        c.bottom.toFloat(),
                    ),
                )
            }
            val maxExpand = dp(KEY_HIT_MAX_EXPAND_DP)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pending = KeyHitTester.hit(rects, event.x, event.y, maxExpand)
                    if (pending in keys.indices) {
                        val k = keys[pending]
                        if (k.action != KeyAction.SHIFT) {
                            showPreview(row.getChildAt(firstKeyIndex + pending), keyLabel(k))
                        }
                    }
                    pending >= 0
                }
                MotionEvent.ACTION_UP -> {
                    dismissPreview()
                    val idx = pending
                    pending = -1
                    if (idx >= 0 && KeyHitTester.hit(rects, event.x, event.y, maxExpand) == idx) {
                        haptic(row.getChildAt(firstKeyIndex + idx))
                        onKey?.invoke(keys[idx])
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dismissPreview()
                    pending = -1
                    true
                }
                else -> pending >= 0
            }
        }
    }

    /**
     * 行内补位：透明不可点，只占 weight + 与字母键相同的边距（字母页第 2 行居中用）。
     *
     * 边距必须跟字母键一样用 KEY_MARGIN_DP：补位只出现在字母页，若它沿默认 keyMargin(3dp)，
     * 第 2 行的键就会比其它两行多出一圈边距而错位。
     */
    private fun buildRowSpacer(weight: Float): View = View(context).apply {
        layoutParams = LayoutParams(0, 1, weight).apply {
            setMargins(dpInt(KEY_MARGIN_DP), 0, dpInt(KEY_MARGIN_DP), 0)
        }
    }

    /** 底部留白：避免最后一行贴到输入法窗口下沿 */
    private fun buildBottomPadding(): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpInt(4))
    }

    /**
     * 键面字号（sp）。
     *
     * 基准是同一台真机上搜狗五笔的实测值（1260×2720 / density 520 / fontScale 1.15）：
     * 搜狗字母键的字形高 48–53px（≈ 18sp），而旧版 23sp 的字形高 63px —— 在 98px 宽的键里
     * 左右只剩 2.5dp，这就是「字母太大」的来源。其余样式按同一比例收一档。
     *
     * 九宫格（T9）不参与这次收缩：它的键宽是 26 键字母键的 2–3 倍，沿用旧字号才不会显得空。
     */
    private fun keyTextSize(key: KeyDef): Float {
        if (layoutKind == KeyboardLayoutKind.T9) {
            return when (key.style) {
                KeyStyle.SPACE -> 16f
                KeyStyle.SPECIAL, KeyStyle.ACCENT, KeyStyle.PRIMARY, KeyStyle.DANGER -> 18f
                KeyStyle.PUNCT -> 20f
                KeyStyle.LETTER -> if (key.weight > 1.4f) 18f else 23f
            }
        }
        return when (key.style) {
            KeyStyle.SPACE -> 13f
            KeyStyle.SPECIAL, KeyStyle.ACCENT, KeyStyle.PRIMARY, KeyStyle.DANGER -> 15f
            // 标点 / 符号字号比字母键小一号：全角标点（，。？！）按字母字号渲染会显得过大。
            // 符号页的键是字母键的 2.4 倍宽（4 列 + 右侧控制列），单个符号用底行标点的字号会显空，
            // 所以符号页保留旧版的 20sp（分类条文案跟着一起大一号，和符号键同排看着才整齐）
            KeyStyle.PUNCT -> if (layoutKind == KeyboardLayoutKind.SYMBOL) 20f else 16f
            KeyStyle.LETTER -> when {
                // 长文案键（颜文字 / 更多符号，weight 2f）用小一号，长条目才放得下
                key.weight > 1.4f -> 15f
                else -> 18f
            }
        }
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
     * @param inToolbar 工具栏键：背景取透明的 [toolbarKeySelector]；行内键（含字母页底行的「符」/「中/英」）
     *   一律是灰底功能键 —— 搜狗底行的「符」「中/英」也是灰键，旧版把这两键错当工具栏键画成透明了。
     */
    private fun createKey(
        key: KeyDef,
        textSize: Float,
        heightPx: Int,
        minWidthPx: Int = 0,
        fillWidth: Boolean = true,
        inToolbar: Boolean = false,
    ): View {
        // 中英切换已按搜狗五笔布局移到字母页底行的「中/英」键，Shift 键不再被改造成中/英：
        // 三种大写状态（未开 / 单击 / 锁定）在中文态与英文态下外观一致。
        val effectiveKey = key

        // 字母页的字母键横向留白收紧到 KEY_MARGIN_DP(1dp)，键框可见宽度变大（AC1/AC5）；
        // 其余键与页面（工具栏 / 底行功能键 / 空格 / 数字符号表情）保持 keyMargin(3dp) 的
        // 既有比例，不动 VOCA-30 规格。
        val hMarginDp = if (
            layoutKind == KeyboardLayoutKind.LETTER && effectiveKey.style == KeyStyle.LETTER
        ) {
            KEY_MARGIN_DP
        } else {
            keyMargin
        }

        // 「换行」键单独画：浅主色胶囊 + ↵ 图标 + 小字标签（见 [buildEnterKey]）
        val enterView: View? = if (effectiveKey.action == KeyAction.ENTER) {
            buildEnterKey(effectiveKey, heightPx)
        } else {
            null
        }

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
                background = backgroundFor(effectiveKey.style, inToolbar)
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
                applyKeyElevation(this, fillWidth, inToolbar)
                // 图标键的宽度来自 drawable 固有尺寸（24dp），必须补上最小可点面积，
                // 否则工具栏图标键会比同排文字键窄一圈，低于 40dp 触控规范。
                if (minWidthPx > 0) minimumWidth = minWidthPx
                layoutParams = LayoutParams(
                    if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                    heightPx,
                    effectiveKey.weight,
                ).apply {
                    setMargins(dpInt(hMarginDp), dpInt(keyMargin), dpInt(hMarginDp), dpInt(keyMargin))
                }
            }
        } else {
            null
        }

        val view: View = enterView ?: iconView ?: TextView(context).apply {
            // 「中/英」键面走分色渲染；其余键面仍是纯文本（[keyLabel] 负责大小写）
            text = if (effectiveKey.action == KeyAction.MODE_SWITCH) {
                modeSwitchLabel()
            } else {
                keyLabel(effectiveKey)
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
            background = backgroundFor(effectiveKey.style, inToolbar)
            // 布局行按键加一点柔和投影，形成「白键浮在灰底上」的搜狗观感
            applyKeyElevation(this, fillWidth, inToolbar)
            if (minWidthPx > 0) minWidth = minWidthPx
            layoutParams = LayoutParams(
                if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                heightPx,
                effectiveKey.weight,
            ).apply {
                setMargins(dpInt(hMarginDp), dpInt(keyMargin), dpInt(hMarginDp), dpInt(keyMargin))
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
                applyKeyElevation(this, fillWidth, inToolbar)
                layoutParams = LayoutParams(
                    if (fillWidth) 0 else LayoutParams.WRAP_CONTENT,
                    heightPx,
                    effectiveKey.weight,
                ).apply {
                    setMargins(dpInt(hMarginDp), dpInt(keyMargin), dpInt(hMarginDp), dpInt(keyMargin))
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
                        this.textSize = HINT_TEXT_SIZE
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
        // 空格键：短按仍走上面的点击回调（正常上屏空格），按住 ≥ spaceLongPressMs 改走语音
        if (effectiveKey.action == KeyAction.SPACE) {
            installSpaceGesture(targetView, effectiveKey)
        }
        return container
    }

    /**
     * 「换行」键面：浅主色胶囊（垂直渐变底 + 1dp 描边）+ 主色 `↵` 图标。两种形态（docs/keyboard-ui.md §4）：
     *
     * - 26 键字母页 / 数字页：键面文案是「换行」→ `↵` 图标 15dp + 「换行」小字 11sp；
     * - 九宫格 / 标点 / 颜文字底行：键面是 `↵` 字形（不是「换行」文案）→ 只画 24dp 图标。
     *   把 `↵` 当文字渲染时，它在 300px 宽的键面里只有 26px，比旁边的 ⌫ / ☺ 小一大截；
     *   而且「图标 ↵ + 文字 ↵」会重复。
     */
    private fun buildEnterKey(key: KeyDef, heightPx: Int): View {
        val label = keyLabel(key)
        val textKey = label.any { it.isLetterOrDigit() }
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            // 子 View（图标 / 文字）不接点击，触控落到容器上，才能统一挂预览 / 回调
            isClickable = true
            isFocusable = true
            contentDescription = if (textKey) label else "换行"
            background = enterKeySelector()
            elevation = dp(1)
            layoutParams = LayoutParams(0, heightPx, key.weight).apply {
                setMargins(dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin), dpInt(keyMargin))
            }
        }
        val iconSize = dpInt(if (textKey) ENTER_ICON_DP else ENTER_GLYPH_ICON_DP)
        container.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_enter)
                imageTintList = ColorStateList.valueOf(enterAccent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(iconSize, iconSize)
            },
        )
        if (!textKey) return container
        container.addView(
            TextView(context).apply {
                text = label
                textSize = ENTER_LABEL_TEXT_SIZE
                setTextColor(enterAccent)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 1
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpInt(ENTER_ICON_GAP_DP)
            },
        )
        return container
    }

    /**
     * 空格键「按住说话」手势（搜狗语义）。
     *
     * 短按（不足 [spaceLongPressMs]）抬手时照常派发 [onKey]，空格上屏行为不变；
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
        // 中文态首字随当前中文输入法变化：五笔「中」/ 拼音「拼」；英文态固定「中/英」
        val zh = if (pinyinMode) "拼" else "中"
        return SpannableString("$zh/英").apply {
            val zhColor = if (englishMode) hintColor else textColor
            val enColor = if (englishMode) textColor else hintColor
            setSpan(ForegroundColorSpan(zhColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(hintColor), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(enColor), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
            textSize = 20f
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
                    textSize = 18f
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
        // PRIMARY（换行）由 [buildEnterKey] 单独渲染：文案键面用主色小字，
        // 这里保留主色，避免将来回退成 TextView 时变回白字
        KeyStyle.PRIMARY -> enterAccent
        KeyStyle.SPACE -> spaceTextColor
        KeyStyle.DANGER -> dangerText
        else -> textColor
    }

    /**
     * 按键背景。
     *
     * [inToolbar] 之前的版本靠「action / layout 白名单」猜哪些键在工具栏里，
     * 结果字母页底行的「符」（LAYOUT→SYMBOL）和「中/英」（MODE_SWITCH）也被当成工具栏键
     * 画成了透明底，与旁边灰底的 `123` 不一致（搜狗底行这两个键都是灰键）。
     */
    /**
     * 键框底部柔和投影（AC2）：有可见键底的键用 elevation 抬出「浮在灰底上」的观感，
     * 正常态与按下态都在（elevation 与按下态无关，按下只换背景色）。
     *
     * 是否算「有可见键底」直接用 [inToolbar] 判断，而不是再按 action / layout 白名单猜一遍：
     * `backgroundFor` 已经按同一判据决定键底是透明（toolbarKeySelector）还是灰键，
     * 两处判据必须同源，否则会出现「透明键却带一圈阴影」或反之。
     */
    private fun applyKeyElevation(view: View, fillWidth: Boolean, inToolbar: Boolean) {
        if (!fillWidth || inToolbar) {
            view.elevation = 0f
            return
        }
        view.elevation = dp(KEY_ELEVATION_DP)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun backgroundFor(style: KeyStyle, inToolbar: Boolean): StateListDrawable = when (style) {
        KeyStyle.SPECIAL -> if (inToolbar) toolbarKeySelector() else keySelector(specialBg, specialBgPressed)
        KeyStyle.SPACE -> keySelector(keyBg, keyBgPressed)
        KeyStyle.ACCENT -> keySelector(accentBg, accentBgPressed)
        KeyStyle.PRIMARY -> enterKeySelector()
        KeyStyle.DANGER -> keySelector(dangerBg, dangerBgPressed)
        KeyStyle.LETTER, KeyStyle.PUNCT -> keySelector(keyBg, keyBgPressed)
    }

    /**
     * 「换行」键背景：浅主色胶囊。
     *
     * 用渐变而不是平铺色，是为了和旁边白键的柔和阴影同一层次；
     * 1dp 主色描边负责把「主操作」这件事说清楚（纯浅色底会显得像禁用键）。
     */
    private fun enterKeySelector(): StateListDrawable {
        val normal = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(enterTop, enterBottom),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(keyRadius)
            setStroke(dpInt(1), enterStroke)
        }
        val pressed = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(enterTopPressed, enterBottomPressed),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(keyRadius)
            setStroke(dpInt(1), enterStroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_selected), pressed)
            addState(intArrayOf(), normal)
        }
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
        val pressed = roundedRect(toolbarPressedBg, keyRadius)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selected)
            // 工具栏键没有实体键底，按下时补一层浅灰回馈，否则只有选中态看不出来
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
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

        /** 空格长按进入语音的默认阈值（毫秒）；实际值由服务侧按设置推入 [spaceLongPressMs]。 */
        const val DEFAULT_SPACE_LONG_PRESS_MS = 500L
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

        /** 「换行」键图标边长 / 图标与文字的间距（dp）。 */
        const val ENTER_ICON_DP = 15
        const val ENTER_ICON_GAP_DP = 2

        /** 「换行」键小字字号（sp）/ 九宫格页只有 `↵` 图标时的边长（dp）。 */
        const val ENTER_LABEL_TEXT_SIZE = 11f
        const val ENTER_GLYPH_ICON_DP = 24

        // ── 键框视觉与命中（issue VOCA-184：字母键框加宽 / 底部阴影 / 整框可点） ──

        /**
         * 字母页字母键的横向留白（dp）：1dp，键框可见宽度变大（相邻键可见缝 = 2dp）。
         *
         * 只有字母页的 [KeyStyle.LETTER] 用这个值；其余键仍用 `keyMargin`(3dp)，
         * 后者受 `tools/verify/check_keyboard_ui.py` 的 VOCA-30 断言保护，不能改。
         */
        const val KEY_MARGIN_DP = 1

        /** 布局行左右留白（dp）：1dp，让键面顶到行内可用宽度。 */
        const val ROW_PADDING_DP = 1

        /** 键框底部投影的抬升高度（dp）：柔和阴影，正常态与按下态都保留。 */
        const val KEY_ELEVATION_DP = 2

        /** 键缝命中扩展上限（dp）：位于键框外的触点，最远归入该距离内的相邻键。 */
        const val KEY_HIT_MAX_EXPAND_DP = 6

        /** Shift 双击判定窗口（毫秒） */
        const val DOUBLE_TAP_MS = 320L

        const val REPEAT_START_DELAY_MS = 380L
        const val REPEAT_SLOW_MS = 90L
        const val REPEAT_FAST_MS = 45L
        const val REPEAT_FAST_AFTER = 6

        /** 字母页栅格宽度单位：第 1 行 10 键 = 10f（第 2 行补位居中靠它对齐）。 */
        const val LETTER_ROW_UNITS = 10f

        /** 键面右上角提示（数字 / 符号）字号：搜狗同机型实测字形高 21px，约合 8sp。 */
        const val HINT_TEXT_SIZE = 8f
    }

    // init 必须位于所有属性声明之后：Kotlin 按声明顺序初始化，
    // 若在 keyHeightDp / 颜色 / pages 赋值前构建键盘，按键会退化为 0 高度。
    init {
        orientation = VERTICAL
        // 键盘底色：搜狗默认皮肤是自上而下的浅灰渐变，平铺色会让键盘看着「焊死」在白键下
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(keyboardTop, keyboardBottom),
        ).apply {
            shape = GradientDrawable.RECTANGLE
        }
        pages = KeyboardLayouts.pages(layoutKind, englishMode)
        rebuild()
    }
}
