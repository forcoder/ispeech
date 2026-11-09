package com.ispeech.data

/**
 * 标点映射表与全角转换。
 *
 * 无状态：只做「字符 → 字符」的静态映射，引号成对切换见 [PunctuationConverter]。
 */
object Punctuation {

    /**
     * 半角 ASCII 标点 → 中文标点。
     *
     * 引号在这里给出「首次出现」的形态（`“` / `‘`），实际成对切换由
     * [PunctuationConverter] 按状态决定。
     */
    val chineseMap: Map<Char, String> = mapOf(
        ',' to "，",
        '.' to "。",
        '?' to "？",
        '!' to "！",
        ':' to "：",
        ';' to "；",
        '(' to "（",
        ')' to "）",
        '<' to "《",
        '>' to "》",
        '[' to "【",
        ']' to "】",
        '{' to "｛",
        '}' to "｝",
        '\\' to "、",
        '/' to "／",
        '@' to "＠",
        '#' to "＃",
        '$' to "￥",
        '%' to "％",
        '^' to "……",
        '&' to "＆",
        '*' to "＊",
        '-' to "－",
        '_' to "——",
        '=' to "＝",
        '+' to "＋",
        '|' to "｜",
        '~' to "～",
        '"' to "“",
        '\'' to "‘",
    )

    /**
     * 全角转换：ASCII → 全角（空格转 U+3000），已是非 ASCII 的原样返回。
     *
     * 只处理可打印 ASCII（0x21..0x7E）与半角空格；制表符 / 换行等控制字符保持不变。
     */
    fun toFullWidth(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == ' ' -> out.append('\u3000')
                ch.code in 0x21..0x7E -> out.append((ch.code + 0xFEE0).toChar())
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

/**
 * 有状态的标点转换器：负责引号成对切换（“ ” / ‘ ’）。
 *
 * 生命周期与一次输入会话绑定：切换输入框 / 重置会话时调用 [reset]，
 * 否则删掉半个引号后再次输入会接不上对的引号。
 */
class PunctuationConverter {

    /** 上一个输出的双引号是否为左引号（“）。 */
    private var doubleQuoteOpen: Boolean = false

    /** 上一个输出的单引号是否为左引号（‘）。 */
    private var singleQuoteOpen: Boolean = false

    /** 切换输入框 / 重置会话时调用，重新开始引号配对。 */
    fun reset() {
        doubleQuoteOpen = false
        singleQuoteOpen = false
    }

    /**
     * 按设置把一次按键输出转换成最终上屏文本。
     *
     * 顺序：先做中文标点映射（含引号配对），再对结果里剩余 ASCII 做全角转换。
     *
     * @param text 按键的原始输出（可能是多字符，如 `……` / `——`）
     * @param chinese 中文标点开关；false 时不翻译标点（但全角仍生效）
     * @param fullWidth 全角开关；true 时 ASCII 可打印字符转全角、空格转 U+3000
     */
    fun convert(text: String, chinese: Boolean, fullWidth: Boolean): String {
        if (text.isEmpty()) return text
        val mapped = if (chinese) mapChinese(text) else text
        return if (fullWidth) Punctuation.toFullWidth(mapped) else mapped
    }

    /** 逐字符映射：引号按状态在左右形态之间交替，其余查 [Punctuation.chineseMap]。 */
    private fun mapChinese(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '"' -> {
                    out.append(if (doubleQuoteOpen) "”" else "“")
                    doubleQuoteOpen = !doubleQuoteOpen
                }

                '\'' -> {
                    out.append(if (singleQuoteOpen) "’" else "‘")
                    singleQuoteOpen = !singleQuoteOpen
                }

                else -> out.append(Punctuation.chineseMap[ch] ?: ch.toString())
            }
        }
        return out.toString()
    }
}
