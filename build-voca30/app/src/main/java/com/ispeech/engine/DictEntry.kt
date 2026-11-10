package com.ispeech.engine

/**
 * 五笔词条：code 为五笔编码，text 为上屏文本。
 */
data class DictEntry(
    val code: String,
    val text: String,
    val weight: Int,
    val isPhrase: Boolean
)
