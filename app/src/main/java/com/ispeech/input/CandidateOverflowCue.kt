package com.ispeech.input

/**
 * AC-12「候选溢出可见性」的判定规则（纯函数，可在 JVM 单测里直接跑）。
 *
 * 规格原文（docs/keyboard-ux-spec.md AC-12，PR #17 head `732da2e`）：
 * - 有溢出 → 视口右界必须给出静态可辨的「后面还有」线索，且线索不占横向预算；
 * - 无溢出 → 线索不得出现（不许常驻一个假的「更多」）。
 *
 * 视口已经被滚到最右端时「后面还有」不再成立，线索也要跟着消失 —— 这就是
 * [shouldShow] 同时收两个入参而不是只看候选数的原因。
 */
object CandidateOverflowCue {

    /** 遮罩宽度：规格给 8–12 dp，取上限，让 fs 1.15 档的放大字身也被盖住。 */
    const val FADE_WIDTH_DP = 12

    /**
     * 是否该显示溢出线索。
     *
     * @param scrollable 候选区本身在位（chip-only 态 / 空态时为 false）
     * @param canScrollForward 候选区还能往右滚（即右边界外确实还有内容）
     */
    fun shouldShow(scrollable: Boolean, canScrollForward: Boolean): Boolean =
        scrollable && canScrollForward

    /** 遮罩朝向：左透明、右实底，与候选条底色衔接。 */
    const val GRADIENT_HORIZONTAL_START_ALPHA = 0x00
    const val GRADIENT_HORIZONTAL_END_ALPHA = 0xFF
}