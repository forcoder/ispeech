package com.ispeech.engine

/**
 * 快捷字母（trigger）校验的单一来源（ADR-0002 C1-1 / C1-2）。
 *
 * 为什么要有这个文件：设置页弹窗、`setRemoteShortcut`、同步侧曾经各写一份长度判定，
 * 编辑器限 2–4 位而仓库自己的数据校验器 `check_quick_trigger.py` 允许 2–12 位，
 * 结果是「数据里允许的东西 UI 不让写」（用户 5 位 `dtwsl` 存不进去）。
 * 上限 12 = 拼音态组合串天花板，下限 2 = 单字母留给五笔一级简码。
 */
object QuickTrigger {

    /** 合法快捷字母的下限（单字母不触发快捷规则，留给五笔一级简码）。 */
    const val MIN_TRIGGER_LENGTH = 2

    /** 合法快捷字母的上限：与拼音态组合串上限 `MAX_PINYIN_LENGTH` 同源，超过即任何输入态都敲不出完整码。 */
    const val MAX_TRIGGER_LENGTH = 12

    /**
     * 五笔态能敲全的长度上限：与 `IspeechImeService.MAX_CODE_LENGTH` 同值。
     *
     * 注意不要与 `PhraseUsageStore.MAX_CODE_LENGTH = 64`（id 归一用的截断长度）混淆，两者同名不同义。
     */
    const val WUBI_REACHABLE_LENGTH = 4

    /** 归一化：先 trim 再小写；映射表键、覆盖判定、去重全都用它（ADR-0002 附录 1）。 */
    fun normalize(trigger: String): String = trigger.trim().lowercase()

    /** 非法原因；返回 null 表示合法。 */
    fun issue(trigger: String): QuickTriggerIssue? {
        val value = normalize(trigger)
        if (value.isEmpty()) return QuickTriggerIssue.EMPTY
        if (value.any { it !in 'a'..'z' }) return QuickTriggerIssue.NOT_LETTERS
        if (value.length < MIN_TRIGGER_LENGTH) return QuickTriggerIssue.TOO_SHORT
        if (value.length > MAX_TRIGGER_LENGTH) return QuickTriggerIssue.TOO_LONG
        return null
    }

    /** 唯一校验入口：设置页三处弹窗、ViewModel、同步侧都只调它（C1-2）。 */
    fun isValidShortcutTrigger(trigger: String): Boolean = issue(trigger) == null

    /** 比五笔态组合缓冲更长的简写：需要可达性提示（C1-7）。 */
    fun isBeyondWubiBuffer(trigger: String): Boolean = normalize(trigger).length > WUBI_REACHABLE_LENGTH

    /**
     * 覆盖条目的匹配方式（C1-6）：4 位以内用 PREFIX（两态都能敲全），
     * 超过 4 位一律 EXACT —— 否则五笔态打满 4 位就命中，长正文会压掉用户正要输入的词。
     */
    fun overrideMode(trigger: String): QuickMatchMode =
        if (isBeyondWubiBuffer(trigger)) QuickMatchMode.EXACT else QuickMatchMode.PREFIX

    /** 长码 + 前缀 / 包含 = 误弹风险（C1-9），与 `check_quick_trigger.py` 的 warn 同口径。 */
    fun mayMisfire(trigger: String, mode: QuickMatchMode): Boolean =
        isBeyondWubiBuffer(trigger) &&
            (mode == QuickMatchMode.PREFIX || mode == QuickMatchMode.CONTAINS)
}

/** 快捷字母非法原因（UI 据此显示「还差什么」，不再让用户自己猜禁用理由）。 */
enum class QuickTriggerIssue {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    NOT_LETTERS,
}
