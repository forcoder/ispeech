package com.ispeech.settings.store

/**
 * 建议回复使用的大模型来源。
 *
 * 除 [CSBABY] 外的选项都走 **OpenAI 兼容协议**（`POST {baseUrl}chat/completions`），
 * 因此同一份实现可以覆盖全部厂商：只要填 Base URL、模型名和 API Key 即可。
 * 预设里给出各家常用的 Base URL 与默认模型，用户不必查文档。
 *
 * @param label 设置页展示名
 * @param baseUrl 预设 Base URL（必须以 `/` 结尾，Retrofit 相对路径才拼得对）；空表示需要用户自填
 * @param defaultModel 预设默认模型名；空表示需要用户自填
 */
enum class AiProvider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    /** 走 csBaby 后端（已登录时可用），由后端选择模型，本机不持有厂商 Key。 */
    CSBABY("csBaby 云端（默认）", "", ""),

    OPENAI("OpenAI", "https://api.openai.com/v1/", "gpt-4o-mini"),

    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat"),

    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/", "qwen-plus"),

    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash"),

    MOONSHOT("Kimi（Moonshot）", "https://api.moonshot.cn/v1/", "moonshot-v1-8k"),

    /** 任意 OpenAI 兼容网关（自建 / 中转 / 本地 Ollama 的 /v1 端点）。 */
    CUSTOM("自定义（OpenAI 兼容）", "", "");

    /** 是否需要用户填 Base URL + 模型 + Key（csBaby 由后端托管，不需要）。 */
    val isDirect: Boolean get() = this != CSBABY

    companion object {
        /** 解析持久化值，未存过或无法识别时回落 [CSBABY]（保持原有行为）。 */
        fun parse(raw: String?): AiProvider =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CSBABY
    }
}

/**
 * 建议回复的大模型配置（DataStore 持久化）。
 *
 * @param provider 模型来源；[AiProvider.CSBABY] 时后三项不生效
 * @param baseUrl 自定义 Base URL，空则用 [provider] 的预设
 * @param model 自定义模型名，空则用 [provider] 的预设
 * @param apiKey 厂商 API Key，仅存本机
 * @param learning 是否让建议回复学习用户的输入（本地 few-shot，不上传）
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.CSBABY,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val learning: Boolean = true,
) {
    /** 实际使用的 Base URL，保证以 `/` 结尾。 */
    fun effectiveBaseUrl(): String {
        val raw = baseUrl.ifBlank { provider.baseUrl }.trim()
        if (raw.isEmpty()) return raw
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    /** 实际使用的模型名。 */
    fun effectiveModel(): String = model.ifBlank { provider.defaultModel }.trim()

    /**
     * 直连厂商所需的配置是否齐全。
     *
     * csBaby 后端不需要这些字段；直连时三项缺一不可，缺了就在设置页明确提示，
     * 而不是等点了 AI 键才报一个看不懂的 HTTP 错误。
     */
    fun isReady(): Boolean {
        if (!provider.isDirect) return true
        return effectiveBaseUrl().isNotEmpty() &&
            effectiveModel().isNotEmpty() &&
            apiKey.isNotBlank()
    }
}