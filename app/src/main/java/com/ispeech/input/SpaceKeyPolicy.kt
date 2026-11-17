package com.ispeech.input

import com.ispeech.settings.store.KeyboardSettings

/**
 * 空格键判定（规格 VOCA-45 §2.2、AC-2 / AC-3）。
 *
 * 两件事：
 * 1. 上屏优先级链条 `t9Digits → buffer → aiReply → voice → association → 直接空格`，顺序即优先级；
 *    链条被前面任一项接管时，双击判定不参与（R5：有候选时永远先上屏首选）。
 * 2. 走到底之后才轮到「双击空格出句号」：距上次空格上屏在时间窗内、且光标前那个字符确认还是刚上屏
 *    的空格，才出「。」；两个前提少任何一个都输出普通空格。
 *
 * 抽成纯函数是因为本仓库单测是 JVM-only（无 Robolectric、无 `androidTest`），
 * 时间窗与状态前提必须能在单测里判真假，所以这里不碰任何 Android API，
 * 时间戳由服务侧喂入（`SystemClock.elapsedRealtime`）。
 *
 * 判定只在服务侧 `handleSpace()` 调用，**不得**改成 View 层双击手势（R8）：
 * 空格已绑 `SoftKeyboardView.installSpaceGesture` 的长按语音，两层手势会抢事件。
 * 软键（`KeyAction.SPACE`）与硬件 / 注入键（`onKeyDown`）两条入口都要走到这里，
 * 后者要不要接管由 [takesHardwareSpace] 判（VOCA-465）。
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
     * @param precedingIsSpace 光标前那一个字符确认是空格。时间窗只证明「最近上屏过空格」，
     *                         证明不了它还在原地（R4 里「移动光标」没有可靠的观测点，
     *                         宿主也可能自己吞掉它），而双击的动作是删字符，所以默认 `false`
     *                         = 没确认就不删。
     * @param keyRepeat 硬件键按住不放产生的自动重复（`KeyEvent.repeatCount > 0`）。
     *                  只参与 [takesHardwareSpace] 的「要不要接管」，不参与 [decide]：
     *                  连发是长按的副产物，不是双击。
     */
    data class State(
        val t9Pending: Boolean = false,
        val composing: Boolean = false,
        val aiReplyPending: Boolean = false,
        val voicePending: Boolean = false,
        val associationPending: Boolean = false,
        val englishMode: Boolean = false,
        val accessibilityEnabled: Boolean = false,
        val precedingIsSpace: Boolean = false,
        val keyRepeat: Boolean = false,
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
        // 删的要就是刚上屏的那个空格（R4 的兜底）：确认不了宁可多出一个空格，不能删掉别人的字符
        if (!state.precedingIsSpace) return Action.SPACE
        val previous = state.lastSpaceCommitAt ?: return Action.SPACE
        return if (now - previous <= windowMs(state.accessibilityEnabled)) {
            Action.DOUBLE_SPACE_PERIOD
        } else {
            Action.SPACE
        }
    }

    /**
     * 空缓冲时，硬件 / 注入键的这一发空格该不该由输入法吃掉（VOCA-465，D1）。
     *
     * 修复前 `IspeechImeService.onKeyDown` 在空缓冲时一律 `return false`，空格由宿主 EditText
     * 自己吞掉，[decide] 压根不会被调用，`lastSpaceCommitAt` 永远是 null —— AC-2 在外接 / 物理 /
     * 注入键上 4 组全阴性的根因。接管的前提是「双击出句号在这一刻还有可能成立」：
     * 第一下也得上屏到我们自己的 `commitText`，第二下才有时间戳可判。
     *
     * 代价是空格不再走宿主那套行为，所以不满足前提的一律维持放行，开关关闭时与修复前逐字相同：
     * - [State.keyRepeat]：按住不放是自动重复，不是双击，交回宿主连发空格；
     * - 链条上还有别的接管项（T9 / 组合 / AI / 语音 / 联想）时不接管，保持既有放行口径，
     *   本单不扩大键路径行为面（那几条状态在软键路径上一直是接管的）；
     * - 英文态、中文标点关闭：判定链本来只会出空格，没必要抢宿主的空间键（R6）。
     */
    fun takesHardwareSpace(state: State, settings: KeyboardSettings): Boolean =
        !state.keyRepeat &&
            !state.t9Pending &&
            !state.composing &&
            !state.aiReplyPending &&
            !state.voicePending &&
            !state.associationPending &&
            settings.doubleSpacePeriod &&
            settings.chinesePunctuation &&
            !state.englishMode
}
