package com.ispeech.input.keyboard

/**
 * 长按浮层条目生成（VOCA-65 条目 2 / AC-2.7、AC-2.8）。
 *
 * 放在 View 之外、且只依赖 [KeyDef]：验收标准把 AC-2.7 / AC-2.8 判为 JVM 层，
 * 要求这两条能被单元测试直接调用，不经过任何 Android 类。
 */
object AltPopupEntries {

    /**
     * 字母键的浮层条目，顺序固定 `[小写, 大写, 角标]`（R2-8）；
     * 串完全相同才去重并保留首次出现（R2-9：不做大小写归一化，`q` 与 `Q` 都保留），
     * 所以条目数只会是 2 或 3。非字母键沿用键位自带的 [KeyDef.alt] —— 数字 / 符号页
     * 的长按候选是既成行为，不在条目 2 范围内。
     */
    fun build(key: KeyDef): List<String> {
        val letter = letterOf(key) ?: return key.alt
        val lower = letter.lowercaseChar().toString()
        val upper = letter.uppercaseChar().toString()
        val entries = ArrayList<String>(3)
        entries += lower
        if (upper != lower) entries += upper
        val hint = key.hint
        if (hint.isNotEmpty() && entries.none { it == hint }) entries += hint
        return entries
    }

    /** 该键是否走「字母浮层」：服务侧据此决定上屏走精确取字符通道还是既有字符键路径。 */
    fun isLetterKey(key: KeyDef): Boolean = letterOf(key) != null

    /** 单个 ASCII 字母才算字母键；emoji（代理对）与数字 / 符号串都不参与。 */
    private fun letterOf(key: KeyDef): Char? {
        if (key.action != KeyAction.CHAR) return null
        if (key.output.length != 1) return null
        val ch = key.output[0]
        return if (ch in 'a'..'z' || ch in 'A'..'Z') ch else null
    }
}
