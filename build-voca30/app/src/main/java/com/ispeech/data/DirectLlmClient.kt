package com.ispeech.data

import android.content.Context
import com.google.gson.Gson
import com.ispeech.data.remote.ChatApiService
import com.ispeech.data.remote.ChatMessage
import com.ispeech.data.remote.ChatRequest
import com.ispeech.settings.store.AiSettings
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 直连厂商大模型（OpenAI 兼容协议）生成建议回复。
 *
 * 与 [CsBabyClient] 的区别：不依赖 csBaby 账号，用户自填 Base URL / 模型 / Key。
 * 提示词里会带上 [AiLearningStore] 里该用户过去采纳过的回复作为 few-shot，
 * 让输出贴合用户自己的口气（这是「学习用户输入」的落点：本地示例，不上传）。
 */
class DirectLlmClient(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val gson = Gson()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 按 Base URL 缓存 Retrofit，避免每次点击 AI 都重建连接池。 */
    private val retrofitCache = HashMap<String, ChatApiService>()

    /**
     * 生成一条建议回复。
     *
     * @param settings 已保存的 AI 配置（调用方保证 [AiSettings.isReady] 为 true）
     * @param message 用户收到的原文
     * @param examples 该用户过去采纳过的「输入 → 回复」，作为语气示例
     */
    suspend fun generate(
        settings: AiSettings,
        message: String,
        examples: List<AiLearningStore.Example> = emptyList(),
    ): String {
        val baseUrl = settings.effectiveBaseUrl()
        val model = settings.effectiveModel()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw IllegalStateException("请先在设置页填写 Base URL 与模型名")
        }
        if (settings.apiKey.isBlank()) {
            throw IllegalStateException("请先在设置页填写 API Key")
        }
        val api = apiFor(baseUrl)
        val request = ChatRequest(
            model = model,
            messages = buildMessages(message, examples),
            temperature = TEMPERATURE,
            maxTokens = MAX_TOKENS,
        )
        val response = try {
            api.complete(request, "Bearer ${settings.apiKey}")
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            throw IllegalStateException("模型返回 ${e.code()}：${short(body)}")
        } catch (e: IOException) {
            throw IllegalStateException("网络请求失败：${e.message ?: "请检查网络"}")
        }
        val reply = response.choices?.firstOrNull()?.message?.content?.trim()
        if (reply.isNullOrEmpty()) {
            throw IllegalStateException(response.error?.message ?: "模型没有返回内容")
        }
        return reply
    }

    /** 组装提示词：系统约束 → 用户历史示例 → 本次输入。 */
    private fun buildMessages(
        message: String,
        examples: List<AiLearningStore.Example>,
    ): List<ChatMessage> {
        val system = buildString {
            append("你是手机输入法里的中文回复助手。")
            append("根据用户收到的那条消息，写出一条用户可以直接发送出去的回复。")
            append("只输出回复正文本身：不要解释、不要加引号、不要写「回复：」。")
            append("语气自然口语化，长度控制在 30 字以内。")
        }
        val messages = ArrayList<ChatMessage>(examples.size * 2 + 2)
        messages.add(ChatMessage(ROLE_SYSTEM, system))
        if (examples.isNotEmpty()) {
            messages.add(
                ChatMessage(
                    ROLE_SYSTEM,
                    "以下是这位用户过去自己采纳过的回复，请模仿其语气、称呼和长度：" +
                        examples.joinToString("") { example ->
                            "\n【收到】${oneLine(example.source)}【回复】${oneLine(example.reply)}"
                        },
                )
            )
        }
        messages.add(ChatMessage(ROLE_USER, "【收到】${oneLine(message)}\n请写一条回复。"))
        return messages
    }

    private fun apiFor(baseUrl: String): ChatApiService = synchronized(retrofitCache) {
        retrofitCache[baseUrl] ?: Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ChatApiService::class.java)
            .also { retrofitCache[baseUrl] = it }
    }

    /** 示例与输入都要压成单行，避免多行消息把提示词结构撑乱。 */
    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_EXAMPLE_CHARS)

    private fun short(text: String?): String {
        if (text.isNullOrBlank()) return "无错误详情"
        val single = text.replace('\n', ' ').trim()
        return if (single.length <= 120) single else single.take(119) + "…"
    }

    companion object {
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 45L
        private const val WRITE_TIMEOUT_SECONDS = 15L

        /** 建议回复要短要稳，温度略低；上限给足中文 30 字。 */
        private const val TEMPERATURE = 0.6
        private const val MAX_TOKENS = 256

        /** 单条示例 / 输入的最大字符数，防止超长聊天记录把提示词撑爆。 */
        private const val MAX_EXAMPLE_CHARS = 200

        /** 提示词里最多带几条历史示例。 */
        const val MAX_FEW_SHOT = 5
    }
}