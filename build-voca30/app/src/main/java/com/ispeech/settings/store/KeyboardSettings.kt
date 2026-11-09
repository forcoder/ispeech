package com.ispeech.settings.store

/** 键盘高度档位。 */
enum class KeyboardSize(val label: String, val heightDp: Int) {
    COMPACT("紧凑", 46),
    STANDARD("标准", 52),
    TALL("加高", 58);

    companion object {
        /** 按 dp 反查档位，未命中返回 STANDARD。 */
        fun fromHeightDp(heightDp: Int): KeyboardSize =
            KeyboardSize.entries.firstOrNull { it.heightDp == heightDp } ?: STANDARD
    }
}

/** 输入法软键盘相关设置（DataStore 持久化，默认值即此处默认值）。 */
data class KeyboardSettings(
    val keyHeightDp: Int = 52,
    val candidatePageSize: Int = 9,     // 每页候选数：5 / 7 / 9
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
