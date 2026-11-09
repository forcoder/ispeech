package com.ispeech.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * OpenAI 兼容的对话补全接口。
 *
 * 厂商预设（OpenAI / DeepSeek / 通义千问 / 智谱 / Moonshot）都实现了这套协议，
 * 差别只在 Base URL、模型名和鉴权 Key，所以一个接口覆盖全部来源。
 * Base URL 由用户配置，运行时按设置创建 Retrofit。
 */
interface ChatApiService {

    /** 生成一段回复：POST {baseUrl}chat/completions */
    @POST("chat/completions")
    suspend fun complete(
        @Body body: ChatRequest,
        @Header("Authorization") authorization: String,
    ): ChatResponse
}

/** 对话请求体（只用到最小字段集，避免不同厂商的扩展字段差异导致解析失败）。 */
data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("temperature") val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("stream") val stream: Boolean = false,
)

/** 一条对话消息：role 取 system / user / assistant。 */
data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String,
)

/** 对话响应体；各厂商都返回 choices[0].message.content。 */
data class ChatResponse(
    @SerializedName("choices") val choices: List<ChatChoice>? = null,
    @SerializedName("error") val error: ChatError? = null,
)

data class ChatChoice(
    @SerializedName("message") val message: ChatMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

data class ChatError(
    @SerializedName("message") val message: String? = null,
    @SerializedName("type") val type: String? = null,
)