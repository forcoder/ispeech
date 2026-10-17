package com.ispeech.input.keyboard

/**
 * 词库查询回调：给一个小写字母串（T9 展开出的字母组合），返回命中的候选字母串。
 *
 * 服务侧通常直接传 `T9LetterLookup { pattern -> engine.lookup(pattern, limit).map { it.insertText } }`。
 * 定义成 `fun interface` 是为了让调用点可以写 lambda。
 */
fun interface T9LetterLookup {
    fun lookup(pattern: String): List<String>
}

/**
 * 九宫格 T9 布局 + 数字串 → 字母串映射。
 *
 * 和 [KeyboardLayouts] 完全同构：这里**只描述键位长什么样**（键面文字、输出字符、宽度、长按候选），
 * 不含任何行为逻辑；按键语义由宿主按 [KeyDef.action] 分发（见 `IspeechImeService.handleSoftKey`）。
 * 所有函数都是无状态纯函数，可反复调用、方便预览。
 *
 * 页结构（顺序即页码，见 [pages]）：
 * 1. [digitPage] 数字页：4×4 键位 —— 3 列数字栅格（`2ABC`…`9WXYZ`）+ 右侧 `⌫`/`↵`/`☺`/`中英`，
 *    底行 `*` `0`（空格）`#`；左上角 `1` 是标点页入口。
 * 2. [punctuationPage] 标点页：常用中文标点，左上角 `1` 循环回数字页。
 *
 * 另外提供两组可直接追加到现有布局的页：
 * - [moreSymbolPages]：更多符号（数学 / 箭头 / 货币 / 单位 / 希腊 / 线框），追加到 `KeyboardLayouts.symbols()` 末尾
 * - [kaomojiPages]：颜文字（[KaomojiData.categories]），追加到 `KeyboardLayouts.emoji()` 末尾
 *
 * 精确接线步骤见 `docs/t9-and-symbols.md`。
 */
object T9Layouts {

    // ── 页码 / 排版常量 ──

    /** 数字页下标（[pages] 中的顺序）。 */
    const val DIGIT_PAGE_INDEX = 0

    /** `1` 标点页下标。 */
    const val PUNCT_PAGE_INDEX = 1

    /** 颜文字页每行个数（键宽权重 2f → SoftKeyboardView 按 16sp 渲染，见其 keyTextSize）。 */
    const val KAOMOJI_PER_ROW = 3

    /** 更多符号页每行个数。 */
    const val SYMBOL_PER_ROW = 8

    /** T9 展开上限：9 位数字最多 4^9 = 262144 组，超限截断，避免拖垮输入法。 */
    const val MAX_LETTER_PATTERNS = 2048

    /**
     * 九宫格主栅格按键，第 1 行到第 4 行逐行铺开（`1..9` + `*` `0` `#`）。
     * `1` 是标点页入口，`0` 是空格键，`*`/`#` 是功能位。
     */
    val DIGIT_GRID: List<String> =
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    /**
     * 数字 → 字母集合（电话键盘标准排布：2ABC 3DEF 4GHI 5JKL 6MNO 7PQRS 8TUV 9WXYZ）。
     * `0` / `1` / `*` / `#` 不在表内：它们不参与字母编码。
     */
    private val T9_DIGIT_LETTERS: Map<Char, String> = mapOf(
        '2' to "ABC",
        '3' to "DEF",
        '4' to "GHI",
        '5' to "JKL",
        '6' to "MNO",
        '7' to "PQRS",
        '8' to "TUV",
        '9' to "WXYZ",
    )

    // ── 数字串 → 字母串 ──

    /** 数字键上的字母集合（大写）；`0`/`1`/`*`/`#` 等非编码键返回空串。 */
    fun t9Letters(digit: Char): String = T9_DIGIT_LETTERS[digit].orEmpty()

    /** 字母 → 所属数字键；非 a-z / A-Z 返回 null。 */
    fun t9DigitOf(letter: Char): Char? {
        val upper = letter.uppercaseChar()
        if (upper !in 'A'..'Z') return null
        for ((digit, letters) in T9_DIGIT_LETTERS) {
            if (upper in letters) return digit
        }
        return null
    }

    /**
     * 数字串 → 全部字母组合（笛卡尔积，小写）。
     *
     * `"23"` → `["ad", "ae", "af", "bd", "be", "bf", "cd", "ce", "cf"]`；
     * 只取 `2`-`9`，其它字符（`0`/`1`/分隔符）会被忽略；结果最多 [MAX_LETTER_PATTERNS] 条。
     */
    fun t9ToLetterPatterns(digits: String): List<String> {
        val clean = digits.filter { it in '2'..'9' }
        if (clean.isEmpty()) return emptyList()

        var patterns = listOf("")
        for (digit in clean) {
            val letters = t9Letters(digit)
            if (letters.isEmpty()) return emptyList()
            val next = ArrayList<String>(minOf(patterns.size * letters.length, MAX_LETTER_PATTERNS))
            build@ for (prefix in patterns) {
                for (letter in letters) {
                    if (next.size >= MAX_LETTER_PATTERNS) break@build
                    next += prefix + letter.lowercaseChar()
                }
            }
            patterns = next
        }
        return patterns
    }

    /**
     * 数字串 → 候选字母串：先展开全部字母组合，再逐个丢给 [lookup] 查词库，
     * 汇总命中的词条（按首次命中顺序去重，最多 [MAX_LETTER_PATTERNS] 条）。
     *
     * @param pinyinDictLookup 词库查询（拼音 / 五笔字母串都适用）
     */
    fun t9ToLetterPatterns(digits: String, pinyinDictLookup: T9LetterLookup): List<String> {
        val hits = LinkedHashSet<String>()
        for (pattern in t9ToLetterPatterns(digits)) {
            hits += pinyinDictLookup.lookup(pattern)
            if (hits.size >= MAX_LETTER_PATTERNS) break
        }
        return hits.toList()
    }

    // ── 服务侧路由辅助 ──

    /** 该键是否是 T9 编码键（`2`..`9`）：是则数字进 T9 缓冲，否则按普通按键处理。 */
    fun isT9CodeKey(key: KeyDef): Boolean =
        key.action == KeyAction.CHAR && key.output.length == 1 && key.output[0] in '2'..'9'

    /** 取键上的编码数字；非编码键返回 null。 */
    fun codeDigitOf(key: KeyDef): Char? = if (isT9CodeKey(key)) key.output[0] else null

    /**
     * 更多符号页在符号布局里的首页下标。
     *
     * 因为 `KeyboardLayouts.symbols()` 已经在末尾追加了 [moreSymbolPages]，
     * 所以首页 = 总页数 - 本组页数（符号1 / 符号2 时为 8 - 6 = 2）。
     * 若哪天不再追加，会退化成 0（跳到符号布局第 1 页）而不是指到越界页。
     */
    fun moreSymbolFirstPage(): Int =
        (KeyboardLayouts.symbols().size - moreSymbolPages().size).coerceAtLeast(0)

    /**
     * 颜文字页在表情布局里的首页下标，同样按「总页数 - 本组页数」反推
     * （8 个表情分类 + 7 个颜文字分类时为 15 - 7 = 8）。
     */
    fun kaomojiFirstPage(): Int =
        (KeyboardLayouts.emoji().size - kaomojiPages().size).coerceAtLeast(0)

    // ── 构造辅助（与 KeyboardLayouts 同名同义） ──

    private fun rows(vararg rows: List<KeyDef>): List<List<KeyDef>> = rows.toList()

    private fun row(vararg keys: KeyDef): List<KeyDef> = keys.toList()

    /**
     * 单个数字键：键面 `8TUV` 形式，[KeyDef.output] 仍是数字本身
     * （服务侧按 [isT9CodeKey] 判断进 T9 缓冲还是直接上屏）。
     */
    private fun digitKey(digit: String): KeyDef {
        val letters = t9Letters(digit[0])
        return KeyDef.char(
            output = digit,
            label = if (letters.isEmpty()) digit else digit + letters,
        )
    }

    /** 右上角功能列：退格。 */
    private fun backspaceKey(): KeyDef =
        KeyDef.fn(KeyAction.BACKSPACE, "⌫", 1.2f, KeyStyle.SPECIAL)

    /** 右上角功能列：回车 / 发送。 */
    private fun enterKey(): KeyDef =
        KeyDef.fn(KeyAction.ENTER, "↵", 1.2f, KeyStyle.PRIMARY)

    /** 底行导航键：回到字母页。 */
    private fun letterSwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "ABC", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.LETTER)

    /** 底行导航键：切到数字页。 */
    private fun numberSwitchKey(): KeyDef =
        KeyDef.fn(KeyAction.LAYOUT, "123", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.NUMBER)

    /** `123 | ABC | 空格 | ⌫ | ↵`：追加式页面（颜文字 / 更多符号）的通用底行。 */
    private fun bottomRow(): List<KeyDef> = row(
        letterSwitchKey(),
        numberSwitchKey(),
        KeyDef.fn(KeyAction.SPACE, "空格", 4f, KeyStyle.SPACE),
        KeyDef.fn(KeyAction.BACKSPACE, "⌫", 1.5f, KeyStyle.SPECIAL),
        KeyDef.fn(KeyAction.ENTER, "↵", 1.5f, KeyStyle.PRIMARY),
    )

    // ── 数字页 ──

    /**
     * 数字页（T9 主键位）。
     *
     * ```
     * 1  2ABC 3DEF  ⌫
     * 4GHI 5JKL 6MNO ↵
     * 7PQRS 8TUV 9WXYZ ☺
     * *  0     #     中/英
     * ```
     *
     * - `1`：[KeyAction.PAGE_NEXT]，循环到 [punctuationPage]（和 Sogou 一样把标点放在 `1`）。
     * - `0`：[KeyAction.SPACE]，九宫格下 0 就是空格。
     * - `*`：切 [KeyboardLayoutKind.SYMBOL] 第 1 页（符号1）。
     * - `#`：切 [KeyboardLayoutKind.SYMBOL] 的 [moreSymbolFirstPage] 页（更多符号）。
     * - `☺`：切 [KeyboardLayoutKind.EMOJI] 的 [kaomojiFirstPage] 页（颜文字）。
     *
     * 注：后三个键依赖 `KeyboardLayoutKind` 目标布局里已经追加了对应页（`docs/t9-and-symbols.md`）；
     * 未追加时 `SoftKeyboardView.setLayout` 会把页码收敛到最后一页，不会崩。
     */
    fun digitPage(): KeyboardPage = KeyboardPage(
        title = "九宫格",
        rows = rows(
            row(
                KeyDef.fn(KeyAction.PAGE_NEXT, "1", 1f, KeyStyle.SPECIAL),
                digitKey("2"),
                digitKey("3"),
                backspaceKey(),
            ),
            row(
                digitKey("4"),
                digitKey("5"),
                digitKey("6"),
                enterKey(),
            ),
            row(
                digitKey("7"),
                digitKey("8"),
                digitKey("9"),
                KeyDef.fn(
                    action = KeyAction.LAYOUT,
                    label = "☺",
                    weight = 1.2f,
                    style = KeyStyle.SPECIAL,
                    layout = KeyboardLayoutKind.EMOJI,
                    pageOffset = kaomojiFirstPage(),
                ),
            ),
            row(
                KeyDef.fn(
                    action = KeyAction.LAYOUT,
                    label = "*",
                    weight = 1f,
                    style = KeyStyle.SPECIAL,
                    layout = KeyboardLayoutKind.SYMBOL,
                ),
                KeyDef.fn(KeyAction.SPACE, "0", 1f, KeyStyle.SPACE),
                KeyDef.fn(
                    action = KeyAction.LAYOUT,
                    label = "#",
                    weight = 1f,
                    style = KeyStyle.SPECIAL,
                    layout = KeyboardLayoutKind.SYMBOL,
                    pageOffset = moreSymbolFirstPage(),
                ),
                KeyDef.fn(KeyAction.MODE_SWITCH, "中/英", 1.2f, KeyStyle.ACCENT),
            ),
        ),
    )

    // ── 标点页 ──

    /**
     * `1` 标点页：常用中文标点，左上角 `1` 用 [KeyAction.PAGE_NEXT] 循环回数字页。
     *
     * 这一页的字符与 `KeyboardLayouts.symbols()` 的「符号1」有意重合（都是常用中文标点），
     * 见 `docs/t9-and-symbols.md`「标点页为什么和符号1重复」。
     */
    fun punctuationPage(): KeyboardPage = KeyboardPage(
        title = "标点",
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
            ),
            row(
                KeyDef.char("”"),
                KeyDef.char("‘"),
                KeyDef.char("’"),
                KeyDef.char("《"),
                KeyDef.char("》"),
                KeyDef.char("【"),
                KeyDef.char("】"),
                KeyDef.char("（"),
            ),
            row(
                KeyDef.char("）"),
                KeyDef.char("—"),
                KeyDef.char("…"),
                KeyDef.char("·"),
                KeyDef.char("～"),
                KeyDef.char("｜"),
                KeyDef.char("〈"),
                KeyDef.char("〉"),
            ),
            row(
                KeyDef.fn(KeyAction.PAGE_NEXT, "1", 1f, KeyStyle.SPECIAL),
                backspaceKey(),
                KeyDef.fn(KeyAction.SPACE, "空格", 4f, KeyStyle.SPACE),
                enterKey(),
            ),
        ),
    )

    /** 九宫格两页：[digitPage] + [punctuationPage]（顺序与页码常量一致）。 */
    fun pages(): List<KeyboardPage> = listOf(digitPage(), punctuationPage())

    // ── 追加式页面：更多符号 / 颜文字 ──

    /**
     * 更多符号：每个分类一页，每行 [SYMBOL_PER_ROW] 个字符，末尾追加 [bottomRow]。
     * 建议追加到 `KeyboardLayouts.symbols()` 末尾（页码见 [moreSymbolFirstPage]）。
     */
    fun moreSymbolPages(): List<KeyboardPage> = KaomojiData.symbolCategories.map { category ->
        val pageRows = mutableListOf<List<KeyDef>>()
        category.symbols.chunked(SYMBOL_PER_ROW).forEach { chunk ->
            pageRows += chunk.map { symbol -> KeyDef.char(symbol) }
        }
        pageRows += bottomRow()
        KeyboardPage(title = category.name, rows = pageRows)
    }

    /**
     * 颜文字：每个分类一页，每行 [KAOMOJI_PER_ROW] 条，末尾追加 [bottomRow]。
     * 键宽权重 2f 让 SoftKeyboardView 用 16sp 渲染（`weight > 1.4f` 分支），长条目才放得下。
     * 建议追加到 `KeyboardLayouts.emoji()` 末尾（页码见 [kaomojiFirstPage]）。
     *
     * 页标题统一加 `颜·` 前缀：表情布局里已经有一个「动物」页（[EmojiData]），
     * 分类名直接当标题会让页码条出现两个「动物」。数据里的分类名保持原样
     * （[KaomojiData.categories] 的 name 仍是「动物」），只有页标题加前缀。
     */
    fun kaomojiPages(): List<KeyboardPage> = KaomojiData.categories.map { category ->
        val pageRows = mutableListOf<List<KeyDef>>()
        category.symbols.chunked(KAOMOJI_PER_ROW).forEach { chunk ->
            pageRows += chunk.map { text -> KeyDef.char(text, weight = 2f) }
        }
        pageRows += bottomRow()
        KeyboardPage(title = "颜·" + category.name, rows = pageRows)
    }
}
