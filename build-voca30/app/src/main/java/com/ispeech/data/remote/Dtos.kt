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

/**
 * 创建关键词规则请求体：POST api/rules。
 * 必填 keyword；其余字段为 null 时 Gson 不下发，由后端取默认值
 * （match_type 默认 CONTAINS）。
 */
data class RuleUpsertRequest(
    @SerializedName("keyword") val keyword: String,
    @SerializedName("reply_template") val replyTemplate: String,
    @SerializedName("match_type") val matchType: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("priority") val priority: Int? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
)

/** 推送的单条规则：id 为空表示新增，非空表示按 id 覆盖远端已有规则。 */
data class SyncPushRule(
    @SerializedName("id") val id: String? = null,
    @SerializedName("keyword") val keyword: String,
    @SerializedName("reply_template") val replyTemplate: String,
    @SerializedName("match_type") val matchType: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("priority") val priority: Int? = null,
    @SerializedName("enabled") val enabled: Boolean? = null
)

/**
 * 批量推送请求体：POST sync/push。
 * rules 承载新增 / 修改，deletedIds 承载删除（与 GET sync/changes 的响应字段同名对称）。
 */
data class SyncPushRequest(
    @SerializedName("rules") val rules: List<SyncPushRule> = emptyList(),
    @SerializedName("deletedIds") val deletedIds: List<String> = emptyList()
)

/** 推送统计：accepted 为后端收下的条数，applied 为真正落库的条数。 */
data class SyncPushResult(
    @SerializedName("accepted") val accepted: Int = 0,
    @SerializedName("applied") val applied: Int = 0,
    @SerializedName("newServerVersion") val newServerVersion: Long = 0L,
    @SerializedName("serverTime") val serverTime: Long = 0L
)

/**
 * 推送响应外壳：后端返回 {code,data:{...}}，data 也可能缺失（两端版本不一致时）。
 * 生效条数一律以推送后重新拉取远端列表的复核结果为准，见 SyncRepository.pushLocalRules。
 */
data class SyncPushEnvelope(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("data") val data: SyncPushResult? = null
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
