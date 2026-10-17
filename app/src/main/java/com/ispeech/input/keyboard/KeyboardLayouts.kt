package com.ispeech.input.keyboard

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

    /** 字母键：output 为小写字母，键面用大写。 */
    private fun letterKeys(letters: String): List<KeyDef> = letters.map { c ->
        KeyDef.char(output = c.toString(), label = c.uppercaseChar().toString())
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

    /**
     * 切到九宫格（T9）的 `9键` 键。
     *
     * 单独放在数字页底行，和 `ABC` / 空格 / `↵` 并排 —— 这样不动数字页任何一个数字键，
     * 也不占工具栏（工具栏留给「输入法 / 中英 / 符号 / 表情 / 短语 / 剪贴 / AI / 设置」8 键）。
     */
    private fun t9SwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "9键", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.T9)

    /** 空格键。 */
    private fun spaceKey(weight: Float = 4f): KeyDef =
        KeyDef.fn(KeyAction.SPACE, "空格", weight, KeyStyle.SPACE)

    /** 回车键。 */
    private fun enterKey(weight: Float = 1.5f): KeyDef =
        KeyDef.fn(KeyAction.ENTER, "↵", weight, KeyStyle.PRIMARY)

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
     */
    fun letterZh(): KeyboardPage = letterPage("字母", ",", ".", "，", "。")

    /** 英文态字母页：第 4 行标点用半角逗号 / 句点。 */
    fun letterEn(): KeyboardPage = letterPage("英文", ",", ".", ",", ".")

    private fun letterPage(
        title: String,
        commaOutput: String,
        periodOutput: String,
        commaLabel: String,
        periodLabel: String,
    ): KeyboardPage = KeyboardPage(
        title = title,
        rows = rows(
            letterKeys("qwertyuiop"),
            letterKeys("asdfghjkl"),
            row(KeyDef.fn(KeyAction.SHIFT, "⇧", 1.5f)) +
                letterKeys("zxcvbnm") +
                row(backspaceKey()),
            listOf(
                numberSwitchKey(),
                KeyDef.punct(commaOutput, commaLabel),
                spaceKey(),
                KeyDef.punct(periodOutput, periodLabel),
                enterKey(),
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
    fun symbols(): List<KeyboardPage> = listOf(
        KeyboardPage(
            title = "符号1",
            rows = rows(
                row(
                    KeyDef.char("，"),
                    KeyDef.char("。"),
                    KeyDef.char("？"),
                    KeyDef.char("！"),
                    KeyDef.char("、"),
                    KeyDef.char("；"),
                    KeyDef.char("："),
                    KeyDef.char("“"),
                    KeyDef.char("”"),
                    KeyDef.char("‘"),
                ),
                row(
                    KeyDef.char("’"),
                    KeyDef.char("《"),
                    KeyDef.char("》"),
                    KeyDef.char("【"),
                    KeyDef.char("】"),
                    KeyDef.char("（"),
                    KeyDef.char("）"),
                    KeyDef.char("—"),
                    KeyDef.char("…"),
                    KeyDef.char("·"),
                ),
                row(
                    KeyDef.char("～"),
                    KeyDef.char("｜"),
                    KeyDef.char("〈"),
                    KeyDef.char("〉"),
                    KeyDef.fn(KeyAction.MOVE_LEFT, "⬅", 1.2f),
                    KeyDef.fn(KeyAction.MOVE_RIGHT, "➡", 1.2f),
                ),
                bottomRow(),
            ),
        ),
        KeyboardPage(
            title = "符号2",
            rows = rows(
                row(
                    KeyDef.char("＋"),
                    KeyDef.char("－"),
                    KeyDef.char("×"),
                    KeyDef.char("÷"),
                    KeyDef.char("＝"),
                    KeyDef.char("≠"),
                    KeyDef.char("≈"),
                    KeyDef.char("≤"),
                    KeyDef.char("≥"),
                ),
                row(
                    KeyDef.char("±"),
                    KeyDef.char("√"),
                    KeyDef.char("∞"),
                    KeyDef.char("°"),
                    KeyDef.char("′"),
                    KeyDef.char("″"),
                    KeyDef.char("℃"),
                    KeyDef.char("㎡"),
                    KeyDef.char("$"),
                ),
                row(
                    KeyDef.char("€"),
                    KeyDef.char("£"),
                    KeyDef.char("‰"),
                    KeyDef.char("§"),
                    KeyDef.char("№"),
                    KeyDef.char("①"),
                    KeyDef.char("②"),
                    KeyDef.char("③"),
                    KeyDef.char("④"),
                    KeyDef.char("⑤"),
                ),
                bottomRow(),
            ),
        ),
    ) + T9Layouts.moreSymbolPages()

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
        KeyboardLayoutKind.SYMBOL -> symbols()
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
