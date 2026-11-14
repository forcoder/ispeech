package com.ispeech.input.keyboard

import com.ispeech.R

/**
 * 内置键盘布局：字母 / 数字 / 符号 / 表情 / 九宫格（T9）。
 *
 * 这里只描述「键位长什么样」（键面文字、输出字符、宽度、长按候选），不含任何行为逻辑；
 * 按键语义由宿主按 [KeyDef.action] 分发。所有方法都是无状态纯函数，可反复调用、方便预览。
 *
 * 九宫格键位在 [T9Layouts]；符号页末尾追加「更多符号」、表情页末尾追加「颜文字」，
 * 追加位置必须保持在末尾，因为 [T9Layouts.moreSymbolFirstPage] / [T9Layouts.kaomojiFirstPage]
 * 靠「总页数 - 本组页数」反推首页下标。
 */
object KeyboardLayouts {

    /** 表情页每行表情个数 */
    private const val EMOJI_PER_ROW = 8

    // ── 构造辅助 ──

    private fun rows(vararg rows: List<KeyDef>): List<List<KeyDef>> = rows.toList()

    private fun row(vararg keys: KeyDef): List<KeyDef> = keys.toList()

    /** 字母键：output 为小写字母，键面用大写；[hints] 为键面顶部的数字 / 符号提示（搜狗风格）。 */
    private fun letterKeys(letters: String, hints: String = ""): List<KeyDef> =
        letters.mapIndexed { index, c ->
            KeyDef.char(
                output = c.toString(),
                label = c.uppercaseChar().toString(),
                hint = hints.getOrNull(index)?.toString().orEmpty(),
            )
        }

    /** 数字键：长按弹出全角 / 带圈 / 括号形式。 */
    private fun digitKeys(): List<KeyDef> = DIGIT_ORDER.map { d ->
        KeyDef.char(output = d, label = d, alt = digitAlt(d))
    }

    /** 切到数字页的 `123` 键。 */
    private fun numberSwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "123", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.NUMBER)

    /** 切到字母页的 `ABC` 键。 */
    private fun letterSwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "ABC", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.LETTER)

    /** 切到符号页的 `符` 键（搜狗五笔字母页底行最左）。 */
    private fun symbolSwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "符", 1.6f, KeyStyle.SPECIAL, KeyboardLayoutKind.SYMBOL)

    /**
     * 切到九宫格（T9）的 `9键` 键。
     *
     * 单独放在数字页底行，和 `ABC` / 空格 / `↵` 并排 —— 这样不动数字页任何一个数字键，
     * 也不占工具栏（工具栏留给「输入法 / 符号 / 表情 / 短语 / 剪贴 / AI / 设置」7 键；
     * 中英切换由字母页左下角 Shift 键承担，见 `SoftKeyboardView.createKey`）。
     */
    private fun t9SwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "9键", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.T9)

    /** 空格键。 */
    private fun spaceKey(weight: Float = 4f): KeyDef =
        KeyDef.fn(KeyAction.SPACE, "空格", weight, KeyStyle.SPACE)

    /** 回车 / 换行键（搜狗五笔底行最右，键面文案为「换行」）。 */
    private fun enterKey(weight: Float = 1.5f): KeyDef =
        KeyDef.fn(KeyAction.ENTER, "换行", weight, KeyStyle.PRIMARY)

    /** 退格键。 */
    private fun backspaceKey(weight: Float = 1.5f): KeyDef =
        KeyDef.fn(KeyAction.BACKSPACE, "⌫", weight, KeyStyle.SPECIAL)

    /** 数字 / 符号页共用的底行：123 | ABC | 空格 | ↵ */
    private fun bottomRow(): List<KeyDef> = row(
        numberSwitchKey(),
        letterSwitchKey(),
        spaceKey(),
        enterKey(),
    )

    // ── 字母页 ──

    /**
     * 中文态字母页：第 4 行标点为全角中文标点，
     * 实际上屏由标点设置决定（见 `com.ispeech.data.PunctuationConverter`）。
     *
     * `,` / `.` 长按候选为全角中文标点（搜狗五笔习惯），长按项直接上屏、不再转换。
     */
    fun letterZh(): KeyboardPage = letterPage(
        "字母", ",", ".", "，", "。",
        commaAlt = listOf("、", "；", "：", "？", "！"),
        periodAlt = listOf("…", "—", "《", "》", "【", "】"),
    )

    /**
     * 英文态字母页：第 4 行标点用半角逗号 / 句点。
     *
     * `,` / `.` 长按候选为半角符号，长按项直接上屏、不再转换。
     */
    fun letterEn(): KeyboardPage = letterPage(
        "英文", ",", ".", ",", ".",
        commaAlt = listOf(";", ":", "?", "!"),
        periodAlt = listOf("'", "\"", "…", "-"),
    )

    /**
     * 构造字母页。底行照搬搜狗五笔 26 键：`符` / `中/英` / `，` / 空格 / `。` / `123` / `换行`。
     *
     * 底行各键的宽度权重按同一台真机上搜狗底行的实测像素比例取值（实测 1260px 屏：
     * 符 169 / 123 134 / ，102 / 空格 309 / 。102 / 中英 134 / 换行 168）：
     * `符` 在搜狗侧是宽键（1.66 单位），旧版写成 1f 后比 `123` 还窄，底行看着一头重一头轻。
     *
     * @param commaAlt `,` 键长按候选（直接上屏，不经过中文标点转换）
     * @param periodAlt `.` 键长按候选（同上）
     */
    private fun letterPage(
        title: String,
        commaOutput: String,
        periodOutput: String,
        commaLabel: String,
        periodLabel: String,
        commaAlt: List<String>,
        periodAlt: List<String>,
    ): KeyboardPage = KeyboardPage(
        title = title,
        rows = rows(
            letterKeys("qwertyuiop", "1234567890"),
            letterKeys("asdfghjkl", "~!@#%“”*?"),
            row(KeyDef.fn(KeyAction.SHIFT, "⇧", 1.5f)) +
                letterKeys("zxcvbnm", "()-—:;") +
                row(backspaceKey()),
            listOf(
                symbolSwitchKey(),
                numberSwitchKey(),
                KeyDef.punct(commaOutput, commaLabel, commaAlt, 1f),
                spaceKey(3.3f),
                KeyDef.punct(periodOutput, periodLabel, periodAlt, 1f),
                KeyDef.fn(KeyAction.MODE_SWITCH, "中/英", 1.45f, KeyStyle.SPECIAL),
                enterKey(1.8f),
            ),
        ),
    )

    // ── 数字页 ──

    /**
     * 数字页：第 1 行 10 个数字键保持不变，底行是 `ABC` / `9键`（九宫格入口）/ 空格 / `↵`。
     */
    fun numbers(): KeyboardPage = KeyboardPage(
        title = "数字",
        rows = rows(
            digitKeys(),
            row(
                KeyDef.punct("-"),
                KeyDef.punct("/"),
                KeyDef.punct(":"),
                KeyDef.punct(";"),
                KeyDef.punct("(", alt = listOf("[", "{", "<")),
                KeyDef.punct(")", alt = listOf("]", "}", ">")),
                KeyDef.punct("¥"),
                KeyDef.punct("@"),
                KeyDef.punct("\""),
            ),
            row(
                KeyDef.fn(KeyAction.LAYOUT, "#+=", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.SYMBOL),
                KeyDef.punct(","),
                KeyDef.punct("."),
                KeyDef.punct("?"),
                KeyDef.punct("!"),
                KeyDef.punct("'"),
                backspaceKey(),
            ),
            row(
                letterSwitchKey(),
                t9SwitchKey(),
                spaceKey(),
                enterKey(),
            ),
        ),
    )

    // ── 符号页 ──

    /**
     * 符号页：第 1 页中文标点与常用符号，第 2 页数学与单位，
     * 末尾追加 [T9Layouts.moreSymbolPages]（数学 / 箭头 / 货币 / 单位 / 希腊 / 线框）。
     * 每页底行固定为 `123` / `ABC` / 空格 / `↵`。
     */
// ── 符号页（复刻搜狗五笔符号面板）──

    /** 符号每页的符号列数（不含右侧控制列）。 */
    private const val SYMBOL_COLUMNS = 4

    /** 符号每页的符号行数。 */
    private const val SYMBOL_ROWS = 4

    /** 右侧控制列宽度权重：比符号键窄一些，和搜狗一致。 */
    private const val SYMBOL_CONTROL_WEIGHT = 0.85f

    /** 分类条文案，顺序即页序；点一下直达该页（[KeyDef.pageOffset]）。 */
    private val symbolCategoryNames = listOf(
        "1.常用",
        "2.中文",
        "3.英文",
        "4.网络",
        "5.数学",
        "6.角标",
    )

    /** 常用符号（实拍第 1 页）。 */
    private val symbolCommon = listOf(
        ".", "@", "~", "-",
        ",", ":", "*", "?",
        "!", "–", "#", "/",
        "=", "+", "——", "&",
    )

    /** 中文符号（实拍第 2 页）。 */
    private val symbolChinese = listOf(
        "，", "。", "？", "！",
        "～", "`", "：", "#",
        "；", "%", "*", "——",
        "……", "&", "·", "￥",
    )

    /** 英文符号（实拍第 3 页）。 */
    private val symbolEnglish = listOf(
        "“", "”", "~", "@",
        "?", "+", "—", "、",
        ",", "#", ":", "*",
        "(", ")", "/", "-",
    )

    /** 网络符号（未实拍，按搜狗常见集合补齐）。 */
    private val symbolNetwork = listOf(
        "@", "#", ".", "/",
        ":", "-", "_", "~",
        "?", "&", "=", "%",
        "http://", "www.", ".com", ".cn",
    )

    /** 数学符号（未实拍；高等数学符号见追加的「数学」页）。 */
    private val symbolMath = listOf(
        "+", "-", "×", "÷",
        "=", "≠", "≈", "≤",
        "≥", "±", "√", "∞",
        "°", "′", "″", "π",
    )

    /** 角标符号（上标 / 下标数字）。 */
    private val symbolScript = listOf(
        "⁰", "¹", "²", "³",
        "⁴", "⁵", "⁶", "⁷",
        "⁸", "⁹", "₀", "₁",
        "₂", "₃", "₄", "₅",
    )

    /**
     * 符号页：复刻搜狗五笔的符号面板。
     *
     * 版式与实拍一致：4 列 × 4 行符号 + 右侧控制列（返回 / 上一页 / 下一页 / 删除），
     * 底部一行是分类条，点一下直达对应分类页（不靠反复翻页找）。
     *
     * 末尾仍追加 [T9Layouts.moreSymbolPages]（高等数学 / 箭头 / 货币 / 单位 / 希腊 / 线框），
     * 这些页不在分类条上，用右侧 ↑ / ↓ 翻到。
     */
    fun symbols(): List<KeyboardPage> = listOf(
        symbolPage("符号1", symbolCommon, 0),
        symbolPage("符号2", symbolChinese, 1),
        symbolPage("符号3", symbolEnglish, 2),
        symbolPage("符号4", symbolNetwork, 3),
        symbolPage("符号5", symbolMath, 4),
        symbolPage("符号6", symbolScript, 5),
    ) + T9Layouts.moreSymbolPages()

    /** 组一页符号：[symbols] 按 4 列铺开，右侧插控制键，末行是分类条。 */
    private fun symbolPage(title: String, symbols: List<String>, pageIndex: Int): KeyboardPage {
        val pageRows = ArrayList<List<KeyDef>>(SYMBOL_ROWS + 1)
        for (row in 0 until SYMBOL_ROWS) {
            val cells = (0 until SYMBOL_COLUMNS).map { column ->
                val symbol = symbols.getOrNull(row * SYMBOL_COLUMNS + column).orEmpty()
                if (symbol.isEmpty()) {
                    // 数据不足时留一个不可点的空位，保持网格不错位
                    KeyDef.fn(KeyAction.CLEAR, "", 1f, KeyStyle.SPECIAL)
                } else {
                    KeyDef.char(symbol)
                }
            }
            pageRows += cells + symbolControlKey(row, pageIndex)
        }
        pageRows += symbolCategoryRow()
        return KeyboardPage(title = title, rows = pageRows)
    }

    /**
     * 右侧控制列：返回 / 上页 / 下页 / 删除。
     *
     * 搜狗第 4 位是「锁键盘」，我们没有对应语义，换成更常用的删除；
     * 翻页文案用「上页 / 下页」而不是箭头 —— 箭头字形与「更多符号」的箭头页重复。
     */
    private fun symbolControlKey(row: Int, pageIndex: Int): KeyDef = when (row) {
        0 -> KeyDef.fn(
            KeyAction.LAYOUT,
            "返回",
            SYMBOL_CONTROL_WEIGHT,
            KeyStyle.SPECIAL,
            KeyboardLayoutKind.LETTER,
        )
        1 -> KeyDef.fn(KeyAction.PAGE_PREV, "上页", SYMBOL_CONTROL_WEIGHT)
        2 -> KeyDef.fn(KeyAction.PAGE_NEXT, "下页", SYMBOL_CONTROL_WEIGHT)
        else -> KeyDef.fn(KeyAction.BACKSPACE, "⌫", SYMBOL_CONTROL_WEIGHT)
    }

    /** 分类条：每格直达一个分类页；用 [KeyStyle.PUNCT] 让文案字号小一号。 */
    private fun symbolCategoryRow(): List<KeyDef> =
        symbolCategoryNames.mapIndexed { index, name ->
            KeyDef.fn(
                KeyAction.LAYOUT,
                name,
                1f,
                KeyStyle.PUNCT,
                KeyboardLayoutKind.SYMBOL,
                pageOffset = index,
            )
        }

    // ── 表情页 ──

    /**
     * 每个分类一页，每行 [EMOJI_PER_ROW] 个表情，末尾追加一行功能区；
     * 颜文字分类（[T9Layouts.kaomojiPages]，标题形如「颜·开心」）追加在最后。
     */
    fun emoji(): List<KeyboardPage> = EmojiData.categories.map { category ->
        val pageRows = mutableListOf<List<KeyDef>>()
        category.symbols.chunked(EMOJI_PER_ROW).forEach { chunk ->
            pageRows += chunk.map { symbol -> KeyDef.char(symbol) }
        }
        pageRows += row(
            letterSwitchKey(),
            numberSwitchKey(),
            spaceKey(),
            backspaceKey(),
            enterKey(),
        )
        KeyboardPage(title = category.name, rows = pageRows)
    } + T9Layouts.kaomojiPages()

    // ── 分派 ──

    /** 按布局种类取页；字母页受中英文态影响。 */
    fun pages(kind: KeyboardLayoutKind, englishMode: Boolean): List<KeyboardPage> = when (kind) {
        KeyboardLayoutKind.LETTER -> if (englishMode) listOf(letterEn()) else listOf(letterZh())
        KeyboardLayoutKind.NUMBER -> listOf(numbers())
        KeyboardLayoutKind.SYMBOL -> symbols().map { it.punctStyled() }
        KeyboardLayoutKind.EMOJI -> emoji()
        KeyboardLayoutKind.T9 -> T9Layouts.pages()
    }

    // ── 数字长按候选 ──

    private val DIGIT_ORDER: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    /** 带圈数字：①..⑨、⓪ */
    private val CIRCLED_DIGITS: Map<String, String> = mapOf(
        "1" to "①", "2" to "②", "3" to "③", "4" to "④", "5" to "⑤",
        "6" to "⑥", "7" to "⑦", "8" to "⑧", "9" to "⑨", "0" to "⓪",
    )

    /** 括号数字：⑴..⑼（0 无对应字符） */
    private val PAREN_DIGITS: Map<String, String> = mapOf(
        "1" to "⑴", "2" to "⑵", "3" to "⑶", "4" to "⑷", "5" to "⑸",
        "6" to "⑹", "7" to "⑺", "8" to "⑻", "9" to "⑼",
    )

    /** 半角数字 → 全角数字（码位偏移 0xFEE0）。 */
    private fun fullWidthDigit(digit: String): String {
        val code = digit[0].code
        return if (code in 0x21..0x7E) (code + 0xFEE0).toChar().toString() else digit
    }

    /** 长按候选：原字符 + 全角 + 带圈（+ 括号）。 */
    private fun digitAlt(digit: String): List<String> {
        val alts = mutableListOf(digit, fullWidthDigit(digit))
        CIRCLED_DIGITS[digit]?.let { alts += it }
        PAREN_DIGITS[digit]?.let { alts += it }
        return alts
    }
}

/**
 * 把一页里的「字符键」（[KeyAction.CHAR]）统一改成 [KeyStyle.PUNCT] 渲染。
 *
 * 符号页 / 标点页的字号因此比字母键小一号（搜狗习惯），全角标点不会显得过大；
 * 只改视觉样式，不动 [KeyDef.action] / [KeyDef.output]，上屏行为完全不变。
 */
internal fun KeyboardPage.punctStyled(): KeyboardPage = copy(
    rows = rows.map { line ->
        line.map { key ->
            if (key.action == KeyAction.CHAR) key.copy(style = KeyStyle.PUNCT) else key
        }
    },
)
