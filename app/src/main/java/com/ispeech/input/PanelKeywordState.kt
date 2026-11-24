package com.ispeech.input

/**
 * 快捷短语搜索框的消费级状态：`(keyword, cursor)` 双状态（VOCA-68 §3.2）。
 *
 * 为什么抽成不可变值对象：条目 1 要求「服务侧只有一个写入口」（§3.3 R1-15）——
 * 软键字母、硬件键盘、候选上屏、`✕` 四条路径若各自拼串，光标与文本必然错位。
 * 这里把「改」全部收敛成 `copy` 式纯变换，服务侧唯一要做的事就是把新状态换上去并同步视图，
 * 没有第二条能改到内部字段的通路。
 *
 * 索引单位是**码点**（R1-1，代理对算 1 个），所有变换委托给 [SearchCaret]，
 * 与 `SearchCaretTest` / 设备侧断言跑的是同一份算式，不在服务里另写一遍。
 */
data class PanelKeywordState(
    val keyword: String = "",
    /** 光标位置（码点索引），恒在 `0..keyword 的码点数` 内。 */
    val cursor: Int = 0,
) {

    /** 夹紧后的光标（构造后永远等于 [cursor]，读时再夹一次防外部传入越界值）。 */
    val safeCursor: Int get() = SearchCaret.clamp(keyword, cursor)

    /** 光标是否已在最前 / 最后（`◀ ▶` 的禁用态判据，R1-17）。 */
    val atStart: Boolean get() = safeCursor == 0
    val atEnd: Boolean get() = safeCursor >= SearchCaret.length(keyword)

    /** 在光标处插入 [text]，光标前移其码点数（R1-11 / R1-12 / R1-13）。 */
    fun insert(text: String): PanelKeywordState {
        if (text.isEmpty()) return this
        val (next, at) = SearchCaret.insert(keyword, safeCursor, text)
        return PanelKeywordState(next, at)
    }

    /** 删除光标前一个码点；光标在 0 时原样返回（AC-1.2 / AC-1.5）。 */
    fun backspace(): PanelKeywordState {
        val (next, at) = SearchCaret.backspace(keyword, safeCursor)
        return PanelKeywordState(next, at)
    }

    /** 删除光标后一个码点（硬件 `FORWARD_DEL`，R1-43）。 */
    fun deleteAfter(): PanelKeywordState {
        val (next, at) = SearchCaret.deleteAfter(keyword, safeCursor)
        return PanelKeywordState(next, at)
    }

    /** 光标移 [delta] 个码点，越界停在边界（R1-6 / R1-7 / R1-8）。 */
    fun moveBy(delta: Int): PanelKeywordState =
        PanelKeywordState(keyword, SearchCaret.moveBy(keyword, safeCursor, delta))

    /** 整体替换（`✕` 清空 = `replace("")`；开 / 关面板与旋转复位同样走这里）。 */
    fun replace(next: String, at: Int = SearchCaret.length(next)): PanelKeywordState =
        PanelKeywordState(next, SearchCaret.clamp(next, at))

    companion object {
        /** 空态：空词 + 光标 0（D2-5）。 */
        val EMPTY = PanelKeywordState()
    }
}