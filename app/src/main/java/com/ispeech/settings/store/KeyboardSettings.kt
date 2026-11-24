package com.ispeech.settings.store

/**
 * 键盘高度档位。
 *
 * 阶梯 48 / 56（默认）/ 64 由 VOCA-184 人类选定：字母页是 10 列 QWERTY，360dp 竖屏下单列
 * 可点带宽上限 ≈ 行内宽/10，横向加宽只能用尽行宽，再要更好的手指触控只能整体加高。
 *
 * **[DEFAULT_HEIGHT_DP] 是键高默认值的唯一字面量出处**：`KeyboardSettings.keyHeightDp` 的
 * 默认值、`SettingsStore` 的读盘兜底、`SoftKeyboardView.DEFAULT_KEY_HEIGHT_DP` 全部引用它，
 * 不允许在别处再写一个数字，否则「设置页选中档位」与「实际键高」会静默对不上。
 */
enum class KeyboardSize(val label: String, val heightDp: Int) {
    COMPACT("紧凑", 48),
    STANDARD("标准", 56),
    TALL("加高", 64);

    companion object {
        /** 默认档位（新装 / 从未设置过键盘高度的用户落到这一档）。 */
        val DEFAULT: KeyboardSize = STANDARD

        /** 默认键高（dp）：字母行、工具栏各行按它算高度。 */
        val DEFAULT_HEIGHT_DP: Int get() = DEFAULT.heightDp

        /** 按 dp 反查档位，未命中返回 [DEFAULT]。 */
        fun fromHeightDp(heightDp: Int): KeyboardSize =
            KeyboardSize.entries.firstOrNull { it.heightDp == heightDp } ?: DEFAULT
    }
}

/** 输入法软键盘相关设置（DataStore 持久化，默认值即此处默认值）。 */
data class KeyboardSettings(
    val keyHeightDp: Int = KeyboardSize.DEFAULT_HEIGHT_DP,
    val candidatePageSize: Int = 9,     // 每屏候选数：5 / 7 / 9（候选条横向滑动浏览更多）
    val vibrate: Boolean = true,
    val chinesePunctuation: Boolean = true,  // 中文标点
    val fullWidth: Boolean = false,          // 全角输出
    val association: Boolean = true,         // 词语联想
    val learning: Boolean = true,            // 用户词频学习
    val pinyinMixed: Boolean = true,         // 拼音混输（五笔查不到时用拼音兜底）
    val fuzzyPinyin: Boolean = false,        // 模糊音（z=zh / c=ch / s=sh / n=l / an=ang / en=eng / in=ing）
    val traditionalOutput: Boolean = false,  // 繁体输出（上屏前做简 → 繁转换）
) {
    val size: KeyboardSize get() = KeyboardSize.fromHeightDp(keyHeightDp)
}
