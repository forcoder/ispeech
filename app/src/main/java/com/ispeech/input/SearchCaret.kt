package com.ispeech.input

/**
 * 快捷短语搜索框「文本 + 光标」双状态的纯计算内核（无 Android 依赖，JVM 可直测）。
 *
 * 为什么单独抽出来：VOCA-627（D-2）只恢复“光标渲染面”，把光标位置从“永远贴在末尾”变成
 * 一个可读取、可夹紧的真实状态；条目 1 才会在服务侧接上 `◀ ▶` 与四条写入路径。
 * 这里先把位置算式固定下来，使 AC-1.1~1.4、AC-1.9 在 JVM 层可判定，后续接线只调用这些函数，
 * 不再各处各自算下标（也就不会再出现 `setSelection(length)` 把光标钉在末尾的写法）。
 *
 * 全部函数都是纯函数：输入 (text, cursor) 返回新的 (text, cursor)，越界一律夹紧到 `0..length`，
 * 不抛异常——空搜索框退格、越界移动都不能崩（AC-1.3 / AC-1.4 / AC-1.5）。
 */
object SearchCaret {

    /** 把任意下标夹到 `0..length`；空文本恒为 0（D2-5：空搜索框光标在索引 0）。 */
    fun clamp(length: Int, index: Int): Int {
        if (length <= 0) return 0
        return index.coerceIn(0, length)
    }

    /** 在光标处插入 [insert]，返回新文本与移动后的光标（AC-1.1 / AC-1.9）。 */
    fun insert(text: String, cursor: Int, insert: String): Pair<String, Int> {
        if (insert.isEmpty()) return text to clamp(text.length, cursor)
        val at = clamp(text.length, cursor)
        return (text.substring(0, at) + insert + text.substring(at)) to (at + insert.length)
    }

    /** 删除光标前一个字符，返回新文本与光标；光标在 0 时原样返回（AC-1.2 / AC-1.5）。 */
    fun backspace(text: String, cursor: Int): Pair<String, Int> {
        val at = clamp(text.length, cursor)
        if (at == 0) return text to 0
        return (text.substring(0, at - 1) + text.substring(at)) to (at - 1)
    }

    /** 光标左 / 右移 [delta]（负数向左），越界停在边界（AC-1.3 / AC-1.4）。 */
    fun moveBy(text: String, cursor: Int, delta: Int): Int =
        clamp(text.length, clamp(text.length, cursor) + delta)
}