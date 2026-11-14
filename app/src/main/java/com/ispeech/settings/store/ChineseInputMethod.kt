package com.ispeech.settings.store

/**
 * 中文输入法：中文状态下默认使用的输入法。
 *
 * 对应设置页「键盘与输入 → 中文输入法」；英文态是临时状态，从英文切回中文时
 * 固定回到这里选定的输入法（见 [com.ispeech.input.InputModePolicy]）。
 *
 * 持久化键沿用历史键名 `input_method_mode`，老版本已选的五笔不会被重置。
 */
enum class ChineseInputMethod(val label: String) {
    PINYIN("拼音"),
    WUBI("五笔");

    companion object {
        /** 解析持久化值；未知 / 空值一律回落到拼音（与历史默认一致）。 */
        fun parse(raw: String?): ChineseInputMethod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PINYIN
    }
}