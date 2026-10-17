package com.ispeech.data.remote

import com.google.gson.annotations.SerializedName
import com.ispeech.data.RemoteKeywordRule

/** 登录请求体：POST api/auth/user/login */
data class LoginRequest(
    @SerializedName("identifier") val identifier: String,
    @SerializedName("password") val password: String
)

/**
 * 注册请求体：POST api/auth/user/register。
 * identifier 为手机号或邮箱，调用方只填对应字段；Gson 默认不下发 null 字段。
 */
data class RegisterRequest(
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("password") val password: String,
    @SerializedName("name") val name: String? = null
)

/** 刷新令牌请求体：POST auth/refresh */
data class RefreshTokenRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

/**
 * 登录 / 注册 / 刷新接口的统一响应。
 * 登录返回 user_id + token + expires_in，刷新返回 userId + accessToken + expiresAt，
 * 这里全部兼容，取值请用 effectiveUserId / effectiveToken。
 */
data class LoginResponse(
    /** 登录响应里的 user_id（snake_case） */
    @SerializedName("user_id") val userIdSnake: String? = null,
    /** 刷新响应里的 userId（camelCase） */
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("accessToken") val accessToken: String? = null,
    @SerializedName("refreshToken") val refreshToken: String? = null,
    /** 有效期（秒），登录 / 注册时返回 */
    @SerializedName("expires_in") val expiresIn: Long? = null,
    /** 到期时间：毫秒绝对时间戳或秒偏移，具体见 CsBabyClient.resolveExpiresAtEpochMs */
    @SerializedName("expiresAt") val expiresAt: Long? = null,
    @SerializedName("tenantId") val tenantId: String? = null,
    /** 业务失败时的错误信息 */
    @SerializedName("error") val error: String? = null
) {
    /** 兼容 user_id / userId 两种字段名 */
    val effectiveUserId: String?
        get() = userIdSnake ?: userId

    /** 兼容 token / accessToken 两种字段名 */
    val effectiveToken: String?
        get() = accessToken ?: token

    /** 无业务错误且用户、令牌字段齐全才算成功 */
    val isSuccess: Boolean
        get() = error.isNullOrBlank() &&
            !effectiveUserId.isNullOrBlank() &&
            !effectiveToken.isNullOrBlank()
}

/** sync/all 响应体。 */
data class SyncAllData(
    @SerializedName("keywordRules") val keywordRules: List<RemoteKeywordRule> = emptyList(),
    @SerializedName("serverTime") val serverTime: Long = 0L
)

/** AI 生成请求体：POST api/ai/generate */
data class GenerateRequest(
    @SerializedName("message") val message: String,
    @SerializedName("context") val context: Map<String, String>? = null,
    @SerializedName("style") val style: Map<String, Double>? = null
)

/**
 * AI 生成响应体。
 * source 为 keyword（命中关键词规则）或 ai（调用已配置的 LLM）。
 */
data class GenerateResponse(
    @SerializedName("reply") val reply: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("model_used") val modelUsed: String? = null,
    @SerializedName("confidence") val confidence: Double? = null,
    @SerializedName("response_time_ms") val responseTimeMs: Long? = null,
    @SerializedName("error") val error: String? = null
)
