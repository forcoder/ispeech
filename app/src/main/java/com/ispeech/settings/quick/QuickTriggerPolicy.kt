package com.ispeech.settings.quick

import com.ispeech.engine.QuickMatchMode

/**
 * 快捷字母的录入规则（设置页新增 / 编辑共用）。
 *
 * 两条约束都源自输入法侧的真实限制，写死在这里，UI 与 ViewModel 共用同一份判定：
 *
 * 1. **长度 1–8 位、只允许小写 a-z**。数字 / 符号在五笔态直接上屏、不进编码缓冲，
 *    所以字母之外的字符永远打不出来。
 * 2. **1 位快捷字母必须用 exact**。`QuickPhraseEngine.matches` 对 prefix / contains 有
 *    `minTriggerLength = 2` 守卫（避免打字中途误触），1 位配合 prefix 会在输入该字母时
 *    一个候选都不返回 —— 用户存了规则却完全没反应。这里直接强转，不把坑留给用户。
 *
 * 超过 4 位的快捷字母在**五笔态**打不全（组合缓冲 `MAX_CODE_LENGTH = 4`），
 * 但在拼音态（上限 12）可以完整输入并精确命中；这一限制用 [longerThanWubiBuffer]
 * 在 UI 上做提示，而不是直接禁止 —— 禁止会挡掉拼音用户的合理用法。
 */
internal object QuickTriggerPolicy {

    /** 快捷字母长度范围。 */
    val LENGTH_RANGE = 1..8

    /** 五笔态组合缓冲上限，超过这个长度的快捷字母在五笔态打不全。 */
    const val WUBI_BUFFER_LIMIT = 4

    /** 是否为合法的快捷字母：1–8 位小写字母。 */
    fun isValid(trigger: String): Boolean =
        trigger.length in LENGTH_RANGE && trigger.all { it in 'a'..'z' }

    /** 超过五笔缓冲上限（在五笔态打不全，仅拼音态可完整输入）。 */
    fun longerThanWubiBuffer(trigger: String): Boolean = trigger.length > WUBI_BUFFER_LIMIT

    /**
     * 实际生效的匹配方式：1 位强制 EXACT，其余沿用用户选择。
     *
     * 只在长度恰好为 1 时改写 —— 2 位以上 prefix 是正常的「打前两个字母就出候选」用法。
     */
    fun effectiveMode(trigger: String, requested: QuickMatchMode): QuickMatchMode =
        if (trigger.length == 1) QuickMatchMode.EXACT else requested

    /**
     * 云端下发用的 `match_type`：1 位必须显式 `EXACT`，其余返回 null（交给后端默认 CONTAINS）。
     *
     * 后端只认 EXACT / CONTAINS / REGEX，默认 CONTAINS。1 位简写落成 CONTAINS 后回读为
     * `QuickMatchMode.CONTAINS`，同样被 `minTriggerLength` 守卫拦下 —— 云端建的 1 位简写
     * 在输入法里永远没有候选。其余长度保持远端条目的既有语义（CONTAINS），不做无谓改写。
     */
    fun remoteMatchType(trigger: String): String? =
        if (trigger.length == 1) "EXACT" else null
}