package com.ispeech.data

import android.content.Context
import com.google.gson.Gson
import com.ispeech.BuildConfig
import com.ispeech.data.remote.AiApiService
import com.ispeech.data.remote.AuthApiService
import com.ispeech.data.remote.GenerateRequest
import com.ispeech.data.remote.LoginRequest
import com.ispeech.data.remote.LoginResponse
import com.ispeech.data.remote.RefreshTokenRequest
import com.ispeech.data.remote.RegisterRequest
import com.ispeech.data.remote.SyncApiService
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * csBaby 后端客户端：认证（登录 / 注册 / 刷新令牌）与远端关键词规则同步。
 *
 * baseUrl 优先取 BuildConfig.API_BASE_URL，未配置时回退 DEFAULT_BASE_URL。
 */
class CsBabyClient(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val gson: Gson = Gson()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(resolveBaseUrl())
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val authApi: AuthApiService = retrofit.create(AuthApiService::class.java)
    private val syncApi: SyncApiService = retrofit.create(SyncApiService::class.java)
    private val aiApi: AiApiService = retrofit.create(AiApiService::class.java)

    /** 登录；业务失败 / HTTP 错误 / 网络错误均抛 CsBabyException。 */
    suspend fun login(identifier: String, password: String): CsBabyAuthState {
        val response = call { authApi.login(LoginRequest(identifier, password)) }
        return toAuthState(response, identifier, "登录失败：响应缺少必要字段")
    }

    /** 注册；identifier 为手机号或邮箱，自动选择对应字段。 */
    suspend fun register(identifier: String, password: String, name: String?): CsBabyAuthState {
        val request = if (identifier.contains('@')) {
            RegisterRequest(email = identifier, password = password, name = name)
        } else {
            RegisterRequest(phone = identifier, password = password, name = name)
        }
        val response = call { authApi.register(request) }
        return toAuthState(response, identifier, "注册失败：响应缺少必要字段")
    }

    /** 用 refreshToken 换取新的登录态。 */
    suspend fun refresh(refreshToken: String, identifier: String): CsBabyAuthState {
        val response = call { authApi.refresh(RefreshTokenRequest(refreshToken)) }
        return toAuthState(response, identifier, "刷新登录态失败：响应缺少必要字段")
    }

    /** 拉取全部远端关键词规则。 */
    suspend fun fetchAllRules(auth: CsBabyAuthState): List<RemoteKeywordRule> {
        val data = call {
            syncApi.syncAll(
                tenantId = auth.tenantId.ifBlank { null },
                authorization = "Bearer ${auth.token}"
            )
        }
        return data.keywordRules.orEmpty()
    }

    /** AI 生成回复：优先命中关键词规则，未命中时调用用户配置的 LLM。 */
    suspend fun generateReply(auth: CsBabyAuthState, message: String): AiReply {
        val response = call {
            aiApi.generate(
                body = GenerateRequest(
                    message = message,
                    context = mapOf("platform" to "ispeech-ime"),
                ),
                authorization = "Bearer ${auth.token}",
            )
        }
        val reply = response.reply
        if (reply.isNullOrBlank()) {
            throw CsBabyException.Api(response.error ?: "AI 回复为空，请稍后重试")
        }
        return AiReply(
            reply = reply,
            source = response.source.orEmpty(),
            modelUsed = response.modelUsed.orEmpty(),
        )
    }

    /** 统一把 retrofit / OkHttp 的异常转换成 CsBabyException。 */
    private suspend fun <T> call(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            throw CsBabyException.Http(e.code(), body)
        } catch (e: IOException) {
            throw CsBabyException.Network(e.message ?: "网络请求失败，请检查网络连接")
        }
    }

    /** 校验业务结果并构造本地登录态。 */
    private fun toAuthState(response: LoginResponse, identifier: String, failMessage: String): CsBabyAuthState {
        val error = response.error
        if (!error.isNullOrBlank()) {
            throw CsBabyException.Api(error)
        }
        if (!response.isSuccess) {
            throw CsBabyException.Api(failMessage)
        }
        return CsBabyAuthState(
            userId = response.effectiveUserId.orEmpty(),
            tenantId = response.tenantId.orEmpty(),
            token = response.effectiveToken.orEmpty(),
            refreshToken = response.refreshToken.orEmpty(),
            expiresAtEpochMs = resolveExpiresAtEpochMs(response),
            identifier = identifier
        )
    }

    /** 到期时间（毫秒）：expires_in 为秒偏移；expiresAt 大于阈值视为毫秒绝对时间，否则按秒偏移。 */
    private fun resolveExpiresAtEpochMs(response: LoginResponse): Long {
        val now = System.currentTimeMillis()
        val expiresInSeconds = response.expiresIn ?: 0L
        val expiresAt = response.expiresAt ?: 0L
        return when {
            expiresInSeconds > 0L -> now + expiresInSeconds * 1000L
            expiresAt > MILLIS_TIMESTAMP_THRESHOLD -> expiresAt
            expiresAt > 0L -> now + expiresAt * 1000L
            else -> now
        }
    }

    /** baseUrl 优先 BuildConfig.API_BASE_URL，并补齐 Retrofit 要求的结尾 "/"。 */
    private fun resolveBaseUrl(): String {
        val configured = BuildConfig.API_BASE_URL
        val base = configured.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return if (base.endsWith("/")) base else "$base/"
    }

    companion object {

        /** 默认后端地址（与 csBaby 开源仓库一致）。 */
        const val DEFAULT_BASE_URL = "http://api.agentai0.com/"

        /** 大于 1e11 视为毫秒绝对时间戳，否则按秒偏移处理。 */
        private const val MILLIS_TIMESTAMP_THRESHOLD = 100_000_000_000L

        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_WEIGHT = 100

        /**
         * 把远端关键词规则转换为本地快捷短语；规则被删除 / 禁用或内容为空时返回 null。
         */
        fun toQuickEntry(rule: RemoteKeywordRule): QuickEntry? {
            if (rule.deleted || !rule.enabled) return null
            val keyword = rule.keyword.orEmpty()
            val template = rule.replyTemplate.orEmpty()
            if (keyword.isBlank() || template.isBlank()) return null
            // 换行 / 制表符转成字面量，保证短语文件按行解析不被破坏
            val content = template
                .replace("\r", "")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
            val mode = when (rule.matchType.orEmpty().trim().uppercase()) {
                "EXACT" -> QuickMatchMode.EXACT
                "CONTAINS", "FUZZY" -> QuickMatchMode.CONTAINS
                "REGEX" -> QuickMatchMode.REGEX
                else -> QuickMatchMode.PREFIX
            }
            return QuickEntry(
                trigger = keyword,
                label = keyword,
                content = content,
                weight = if (rule.priority > 0) rule.priority else DEFAULT_WEIGHT,
                mode = mode,
                kind = LookupKind.QUICKAPPLY
            )
        }
    }
}
