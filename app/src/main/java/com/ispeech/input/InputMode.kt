package com.ispeech.input

import com.ispeech.settings.store.ChineseInputMethod

/** 输入态：五笔 / 拼音 / 英文。五笔与拼音都是「中文态」。 */
enum class InputMode { WUBI, PINYIN, ENGLISH }

/**
 * 「中/英」切换策略。
 *
 * 只做「中文态 ⇄ 英文态」两态切换：从英文切回中文时，一律使用设置页选定的
 * 默认中文输入法（五笔 / 拼音），而不是在两种中文输入法之间循环。
 *
 * 旧版实现是 五笔 → 拼音 → 英文 → 五笔 的三态循环：默认设为五笔后按一次
 * 「中/英」会先切到拼音（另一种中文输入法），这正是需要修掉的行为。
 */
object InputModePolicy {

    /** 设置项 → 对应的中文输入态。 */
    fun chineseMode(method: ChineseInputMethod): InputMode = when (method) {
        ChineseInputMethod.PINYIN -> InputMode.PINYIN
        ChineseInputMethod.WUBI -> InputMode.WUBI
    }

    /** 当前输入态按一次「中/英」后的目标输入态。 */
    fun toggle(current: InputMode, method: ChineseInputMethod): InputMode =
        if (current == InputMode.ENGLISH) chineseMode(method) else InputMode.ENGLISH
}