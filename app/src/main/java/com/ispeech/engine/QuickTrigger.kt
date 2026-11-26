package com.ispeech.engine

/**
 * 快捷字母（trigger）校验的单一来源（ADR-0002 C1-1 / C1-2）。
 *
 * VOCA-729 起不再限制位数：唯一约束是「非空 + 全小写字母」（用户明确要求 2–4 位限制改为不限制，
 * 例如 5 位 `ylwsl` 必须能存）。[MIN_TRIGGER_LENGTH] / [MAX_TRIGGER_LENGTH] 只作为可达性提示阈值，
 * 不再参与合法性判定。
 */
object QuickTrigger {

    /** 单字母 / 2 位的参考下限，仅用于文案提示；不再禁用单字母（VOCA-729）。 */
    const val MIN_TRIGGER_LENGTH = 2

    /** 拼音态组合串上限 `MAX_PINYIN_LENGTH`，仅用于「敲不全」提示；不再禁用更长简写（VOCA-729）。 */
    const val MAX_TRIGGER_LENGTH = 12

    /**
     * 五笔态能敲全的长度上限：与 `IspeechImeService.MAX_CODE_LENGTH` 同值。
     *
     * 注意不要与 `PhraseUsageStore.MAX_CODE_LENGTH = 64`（id 归一用的截断长度）混淆，两者同名不同义。
     */
    const val WUBI_REACHABLE_LENGTH = 4

    /** 归一化：先 trim 再小写；映射表键、覆盖判定、去重全都用它（ADR-0002 附录 1）。 */
    fun normalize(trigger: String): String = trigger.trim().lowercase()

    /** 非法原因；返回 null 表示合法。VOCA-729 起不再按位数判死。 */
    fun issue(trigger: String): QuickTriggerIssue? {
        val value = normalize(trigger)
        if (value.isEmpty()) return QuickTriggerIssue.EMPTY
        if (value.any { it !in 'a'..'z' }) return QuickTriggerIssue.NOT_LETTERS
        return null
    }

    /** 唯一校验入口：设置页三处弹窗、ViewModel、同步侧都只调它（C1-2）。 */
    fun isValidShortcutTrigger(trigger: String): Boolean = issue(trigger) == null

    /** 比五笔态组合缓冲更长的简写：需要可达性提示（C1-7）。 */
    fun isBeyondWubiBuffer(trigger: String): Boolean = normalize(trigger).length > WUBI_REACHABLE_LENGTH

    /**
     * 覆盖条目的匹配方式（C1-6）：2–4 位用 PREFIX（两态都能敲全），
     * 单字母与超过 4 位一律 EXACT —— 单字母会被引擎的 minTriggerLength 守卫拦下、
     * 长码在五笔态打满 4 位就前缀命中，长正文会压掉用户正要输入的词。
     */
    fun overrideMode(trigger: String): QuickMatchMode {
        val length = normalize(trigger).length
        return if (length <= 1 || length > WUBI_REACHABLE_LENGTH) {
            QuickMatchMode.EXACT
        } else {
            QuickMatchMode.PREFIX
        }
    }

    /** 长码 + 前缀 / 包含 = 误弹风险（C1-9），与 `check_quick_trigger.py` 的 warn 同口径。 */
    fun mayMisfire(trigger: String, mode: QuickMatchMode): Boolean =
        isBeyondWubiBuffer(trigger) &&
            (mode == QuickMatchMode.PREFIX || mode == QuickMatchMode.CONTAINS)
}

/** 快捷字母非法原因（UI 据此显示「还差什么」，不再让用户自己猜禁用理由）。 */
enum class QuickTriggerIssue {
    EMPTY,
    NOT_LETTERS,
}
