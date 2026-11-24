package com.ispeech.input

/**
 * 快捷短语搜索框「文本 + 光标」双状态的纯计算内核（无 Android 依赖，JVM 可直测）。
 *
 * 为什么单独抽出来：VOCA-627（D-2）只恢复“光标渲染面”，把光标位置从“永远贴在末尾”变成
 * 一个可读取、可夹紧的真实状态；条目 1 才会在服务侧接上 `◀ ▶` 与四条写入路径。
 * 这里先把位置算式固定下来，使 AC-1.1~1.4、AC-1.9 在 JVM 层可判定，后续接线只调用这些函数，
 * 不再各处各自算下标（也就不会再出现 `setSelection(length)` 把光标钉在末尾的写法）。
 *
 * **索引单位是码点（code point），不是 UTF-16 码元。** 规格 VOCA-68 §3.2 R1-1 明确
 * 「emoji 等代理对算 1 个码点、不拆半」：`😀` 在 UTF-16 里占 2 个 `Char`，若按码元下标
 * `substring` 删除，`backspace("a😀d", 2)` 会留下孤立高位代理，界面渲染成豆腐块、上屏即乱码。
 * 因此边界与位移全部走 `codePointCount` / `offsetByCodePoints`，只有真正落到字符串时才换成码元下标。
 *
 * 全部函数都是纯函数：输入 (text, cursor) 返回新的 (text, cursor)，越界一律夹紧到 `0..码点数`，
 * 不抛异常——空搜索框退格、越界移动都不能崩（AC-1.3 / AC-1.4 / AC-1.5）。
 */
object SearchCaret {

    /** 文本的码点数 = 光标允许的最大索引。 */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** 把任意下标夹到 `0..length(text)`（码点口径）；空文本恒为 0（D2-5：空搜索框光标在索引 0）。 */
    fun clamp(text: String, index: Int): Int = index.coerceIn(0, length(text))

    /**
     * 在光标处插入 [insert]，返回新文本与移动后的光标（AC-1.1 / AC-1.9 / R1-11）。
     *
     * 光标按 [insert] 的**码点数**前移，不是 `insert.length`——候选词可能带 emoji。
     */
    fun insert(text: String, cursor: Int, insert: String): Pair<String, Int> {
        val at = clamp(text, cursor)
        if (insert.isEmpty()) return text to at
        val atUnits = text.offsetByCodePoints(0, at)
        return (text.substring(0, atUnits) + insert + text.substring(atUnits)) to (at + length(insert))
    }

    /** 删除光标前一个**码点**，返回新文本与光标；光标在 0 时原样返回（AC-1.2 / AC-1.5）。 */
    fun backspace(text: String, cursor: Int): Pair<String, Int> {
        val at = clamp(text, cursor)
        if (at == 0) return text to 0
        val endUnits = text.offsetByCodePoints(0, at)
        val startUnits = text.offsetByCodePoints(endUnits, -1)
        return (text.substring(0, startUnits) + text.substring(endUnits)) to (at - 1)
    }

    /** 删除光标**后**一个码点（硬件键盘 `FORWARD_DEL`，R1-43）；空词 / 光标在末尾时原样返回。 */
    fun deleteAfter(text: String, cursor: Int): Pair<String, Int> {
        val at = clamp(text, cursor)
        if (at >= length(text)) return text to at
        val fromUnits = text.offsetByCodePoints(0, at)
        val toUnits = text.offsetByCodePoints(fromUnits, 1)
        return (text.substring(0, fromUnits) + text.substring(toUnits)) to at
    }

    /** 光标左 / 右移 [delta] 个码点（负数向左），越界停在边界（AC-1.3 / AC-1.4 / R1-8 不环绕）。 */
    fun moveBy(text: String, cursor: Int, delta: Int): Int =
        clamp(text, clamp(text, cursor) + delta)
}