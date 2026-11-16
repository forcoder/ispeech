package com.ispeech.input

/**
 * 已启用输入法清单的完整性判定。
 *
 * 包可见性（Android 11+）会在框架侧裁掉 `getEnabledInputMethodList()` 的返回集合，
 * 裁掉的项在 UI 上与「系统里就只有这么几个」完全不可区分。这里把系统设置里的真值条数
 * 和可见条数对一下，差值只用于「是否可见提示」，不参与取数（ADR-0003 D3）。
 */
internal object ImeListVisibility {

    /** 系统「已启用输入法」设置串的条数：冒号分段，空段不算；读不到或为空按 0。 */
    fun enabledCount(enabledSettings: String?): Int =
        enabledSettings?.split(':')?.count { it.isNotBlank() } ?: 0

    /** 被裁掉的条数，0 表示可见清单与系统启用条数一致（AC-2 的期望态）。 */
    fun hiddenCount(enabledCount: Int, visibleCount: Int): Int =
        (enabledCount - visibleCount).coerceAtLeast(0)
}
