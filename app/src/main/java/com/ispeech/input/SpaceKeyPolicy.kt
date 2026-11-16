package com.ispeech.input

import com.ispeech.settings.store.KeyboardSettings

/**
 * 空格键判定（规格 VOCA-45 §2.2、AC-2 / AC-3）。
 *
 * 两件事：
 * 1. 上屏优先级链条 `t9Digits → buffer → aiReply → voice → association → 直接空格`，顺序即优先级；
 *    链条被前面任一项接管时，双击判定不参与（R5：有候选时永远先上屏首选）。
 * 2. 走到底之后才轮到「双击空格出句号」：距上次空格上屏在时间窗内才出「。」，否则输出普通空格。
 *
 * 抽成纯函数是因为本仓库单测是 JVM-only（无 Robolectric、无 `androidTest`），
 * 时间窗与状态前提必须能在单测里判真假，所以这里不碰任何 Android API，
 * 时间戳由服务侧喂入（`SystemClock.elapsedRealtime`）。
 *
 * 判定只在服务侧 `handleSpace()` 调用，**不得**改成 View 层双击手势（R8）：
 * 空格已绑 `SoftKeyboardView.installSpaceGesture` 的长按语音，两层手势会抢事件。
 */
object SpaceKeyPolicy {

    /** 双击判定窗口：普通 300 ms（R2）。 */
    const val DOUBLE_SPACE_WINDOW_MS = 300L

    /** 无障碍模式下系统会拉长按键接受间隔，窗口放宽到 600 ms（§5）。 */
    const val ACCESSIBILITY_WINDOW_MS = 600L

    /** 空格键这一次该做什么。 */
    enum class Action {
        COMMIT_T9,
        COMMIT_TOP,
        COMMIT_AI_REPLY,
        COMMIT_VOICE,
        PICK_FIRST_CANDIDATE,

        /** 普通上屏一个空格，并把本次时刻记为「上次空格上屏」。 */
        SPACE,

        /** 双击生效：删掉刚上屏的空格，替换成句号（R1）。 */
        DOUBLE_SPACE_PERIOD,
    }

    /**
     * 空格键按下那一刻的服务状态快照，全是布尔量与一个时间戳，单测可直接构造。
     *
     * @param englishMode 英文态（`mode == InputMode.ENGLISH`），句号不转换（R6）。
     * @param accessibilityEnabled 无障碍服务是否启用，决定判定窗口取 300 还是 600 ms（§5）。
     * @param lastSpaceCommitAt 上一次「空格上屏」的时刻；任何非空格上屏、退格、切面板、
     *                          切中英、换输入框都要由调用方清零（R3 / R4）。
     */
    data class State(
        val t9Pending: Boolean = false,
        val composing: Boolean = false,
        val aiReplyPending: Boolean = false,
        val voicePending: Boolean = false,
        val associationPending: Boolean = false,
        val englishMode: Boolean = false,
        val accessibilityEnabled: Boolean = false,
        val lastSpaceCommitAt: Long? = null,
    )

    /** 当前生效的判定窗口（毫秒）。 */
    fun windowMs(accessibilityEnabled: Boolean): Long =
        if (accessibilityEnabled) ACCESSIBILITY_WINDOW_MS else DOUBLE_SPACE_WINDOW_MS

    fun decide(
        state: State,
        settings: KeyboardSettings,
        now: Long,
    ): Action {
        if (state.t9Pending) return Action.COMMIT_T9
        if (state.composing) return Action.COMMIT_TOP
        if (state.aiReplyPending) return Action.COMMIT_AI_REPLY
        if (state.voicePending) return Action.COMMIT_VOICE
        if (state.associationPending) return Action.PICK_FIRST_CANDIDATE
        // 开关默认关；中文标点关闭或英文态不转换，连按两次就是两个空格（R6，对齐搜狗）
        if (!settings.doubleSpacePeriod || !settings.chinesePunctuation || state.englishMode) {
            return Action.SPACE
        }
        val previous = state.lastSpaceCommitAt ?: return Action.SPACE
        return if (now - previous <= windowMs(state.accessibilityEnabled)) {
            Action.DOUBLE_SPACE_PERIOD
        } else {
            Action.SPACE
        }
    }
}
