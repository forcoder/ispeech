package com.ispeech.data

/**
 * AI 生成结果。
 *
 * @param reply 回复正文（关键词规则模板或 LLM 生成）
 * @param source 来源：keyword（关键词命中）/ ai（LLM 生成）
 * @param modelUsed 实际使用的模型名（source=ai 时有值）
 */
data class AiReply(
    val reply: String,
    val source: String,
    val modelUsed: String,
)
