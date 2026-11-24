package com.ispeech.engine

import com.ispeech.data.codePointLength

/** 上屏来源：只有 [SINGLE_CHAR] 可能参与词组学习。 */
internal enum class CommitSource {
    /** 五笔单字候选（kind == WUBI，文本按码点为 1 个字符） */
    SINGLE_CHAR,

    /** 词组候选 / 用户词 / 拼音候选 */
    WORD_CANDIDATE,

    /** 标点 / 空格 / 换行 / 英文 / emoji / 语音（commitDirect 系） */
    DIRECT,

    /** 快捷短语面板 / AI 回复 */
    PANEL,

    /** 联想上屏 */
    ASSOCIATION,
}

/** 一次成词判定结果。[null] 表示「本次不学习」。 */
internal data class PhraseLearningDecision(
    /** 推导出的五笔码，4 位小写 a-z */
    val code: String,
    /** 两字词组，码点长度 2 */
    val text: String,
    /** 第一字的全码（便于日志 / 复盘） */
    val firstCode: String,
    /** 第二字的全码（同上） */
    val secondCode: String,
)

/**
 * 本次不学习的原因。与 ADR-0001 §1「失败分支」一一对应；
 * `disabled`（学习开关关闭）只在写库那一刻由服务判断，不经过 tracker，因此不在此枚举内。
 */
internal enum class PhraseSkipReason {
    /** 两跳间隔超过时间窗 W */
    WINDOW,

    /** 两跳之间发生中断（标点 / 空格 / 英文 / 面板 / 光标移动…） */
    INTERRUPT,

    /** 该词已在系统词库，学了属重复占位 */
    EXISTING,

    /** 同字连打（`你`+`你`）且系统词库无此词 */
    REPEAT,

    /** 编码推导不出（非汉字 / 单字不在词库 / 全码不足 2 位） */
    UNDECIDABLE,

    /** 时间戳回退（系统时间被改过） */
    CLOCK_BACKWARDS,
}

/** 一次跳过记录：原因 + 两跳原文，供调用方打结构化日志（tracker 自身不打日志）。 */
internal data class PhraseSkip(
    val reason: PhraseSkipReason,
    val first: String,
    val second: String,
)

/**
 * 是否「本服务自身造成」的光标变化（ADR-0001 §1、AC-3.4）。
 *
 * 本服务每次写 InputConnection 都会触发一次 `onUpdateSelection`，用「与上一次已知光标的距离」
 * 判定：差值为 0（或不超过容差）视为自因，不中断；超出容差视为用户移动光标，必须中断。
 * [tolerance] = 0 即 ADR 记录的降级口径（只比 `newSelStart`）。
 */
internal fun isSelfCausedSelectionChange(
    newSelStart: Int,
    lastCommittedCursor: Int,
    tolerance: Int,
): Boolean {
    if (lastCommittedCursor < 0) return true
    val diff = newSelStart - lastCommittedCursor
    val distance = if (diff < 0) -diff else diff
    return distance <= tolerance.coerceAtLeast(0)
}

/**
 * 五笔双字词组自学习的状态机（ADR-0001 §1）：纯 Kotlin、零 Android 依赖、可 JVM 单测。
 *
 * tracker 只认一种输入——「刚刚上屏了一段文本」，并自带「上一跳」记忆：
 *
 * - 单字候选上屏：`idle → pending(text, t)`；再次单字上屏且仍在窗口内 → 判定成词；
 * - 任何非单字上屏（标点 / 空格 / 英文 / 语音 / 短语 / 联想）→ `idle`；
 * - [onInterrupt]（面板开合 / 光标移动）与 [reset]（换输入框 / 换输入态）→ `idle`。
 *
 * 所有外部依赖通过构造参数注入：[codeOf] 提供单字全码，[systemHasWord] 判断系统词库是否已有该词，
 * [clock] 提供时间戳。因此 [onCommit] 不 IO、不打日志、不抛异常。
 */
internal class PhraseLearningTracker(
    /** 单字（码点串）→ 全码；查不到返回 null。实现侧由 [WubiEngine.fullCodeOf] 提供。 */
    private val codeOf: (String) -> String?,
    /** 系统词库是否已有该词（精确匹配）。 */
    private val systemHasWord: (String) -> Boolean,
    /** 时间窗 W：两跳间隔超过它即不学（可调参数，不写死在判定逻辑里）。 */
    private val windowMillis: Long = LEARN_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 上一跳的单字；null 表示 idle。 */
    private var pendingText: String? = null
    private var pendingAt: Long = 0

    /** 最近一次 [onCommit] 的跳过原因；学习成功或「只上屏了一个字」时为 null。 */
    var lastSkip: PhraseSkip? = null
        private set

    /**
     * 喂入一次上屏事件。
     *
     * @return 成词判定结果（调用方负责写库）；null 表示本次不学习
     */
    fun onCommit(text: String, source: CommitSource): PhraseLearningDecision? {
        lastSkip = null
        if (source != CommitSource.SINGLE_CHAR) {
            return skipIfPending(PhraseSkipReason.INTERRUPT, text)
        }
        if (codePointLength(text) != 1) {
            return skipIfPending(PhraseSkipReason.INTERRUPT, text)
        }
        val first = pendingText
        if (first == null) {
            pendingText = text
            pendingAt = clock()
            return null
        }
        pendingText = null
        return evaluate(first, text)
    }

    /** 不该学习的中断（面板开合 / 光标移动）：清掉上一跳，后续上屏不会被当成第二跳。 */
    fun onInterrupt() {
        pendingText = null
    }

    /** 换输入框 / 换输入态：清掉上一跳。 */
    fun reset() {
        pendingText = null
    }

    private fun evaluate(first: String, second: String): PhraseLearningDecision? {
        val elapsed = clock() - pendingAt
        if (elapsed < 0) return skip(PhraseSkipReason.CLOCK_BACKWARDS, first, second)
        if (elapsed > windowMillis) return skip(PhraseSkipReason.WINDOW, first, second)
        val word = first + second
        // 顺序不能颠倒：叠字词若已在系统词库（`嘿嘿`/`呵呵`），原因应是「已有」而不是「同字」
        if (systemHasWord(word)) return skip(PhraseSkipReason.EXISTING, first, second)
        if (first == second) return skip(PhraseSkipReason.REPEAT, first, second)
        val code = PhraseCodeDeriver.derive(first, second, codeOf)
            ?: return skip(PhraseSkipReason.UNDECIDABLE, first, second)
        return PhraseLearningDecision(
            code = code,
            text = word,
            firstCode = codeOf(first).orEmpty(),
            secondCode = codeOf(second).orEmpty(),
        )
    }

    private fun skipIfPending(reason: PhraseSkipReason, text: String): PhraseLearningDecision? {
        val first = pendingText ?: return null
        pendingText = null
        return skip(reason, first, text)
    }

    private fun skip(reason: PhraseSkipReason, first: String, second: String): PhraseLearningDecision? {
        lastSkip = PhraseSkip(reason, first, second)
        return null
    }

    internal companion object {
        /** 时间窗 W = 5 s（ADR-0001 §6，待 UX 真机节奏复核）。 */
        const val LEARN_WINDOW_MILLIS = 5_000L
    }
}