package com.ispeech.engine

/** 快捷规则的匹配模式。 */
enum class QuickMatchMode {
    /** 精确匹配触发码。 */
    EXACT,

    /** 前缀匹配：触发码与输入互为前缀时命中。 */
    PREFIX,

    /** 包含匹配：触发码与输入互为子串时命中。 */
    CONTAINS,

    /** 正则匹配：trigger 作为正则表达式匹配输入。 */
    REGEX;

    companion object {
        /** 解析规则文件中的 mode 字段，无法识别时按 PREFIX 处理。 */
        fun parse(raw: String): QuickMatchMode = when (raw.trim().lowercase()) {
            "exact", "=" -> EXACT
            "contains", "fuzzy", "~" -> CONTAINS
            "regex", "re" -> REGEX
            else -> PREFIX
        }
    }
}
