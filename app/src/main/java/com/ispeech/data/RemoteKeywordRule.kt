package com.ispeech.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * 远端关键词规则（sync/all 响应里 keywordRules 的元素）。
 * 后端字段为 snake_case，这里用 @SerializedName 映射；
 * enabled / deleted 可能是 true/false、1/0 或 "1"/"0"，做统一容错。
 */
data class RemoteKeywordRule(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("keyword") val keyword: String = "",
    @SerializedName("match_type") val matchType: String = "",
    @SerializedName("reply_template") val replyTemplate: String = "",
    @SerializedName("category") val category: String = "",
    @SerializedName("priority") val priority: Int = 0,
    @SerializedName("enabled")
    @JsonAdapter(TolerantBooleanDeserializer::class)
    val enabled: Boolean = true,
    @SerializedName("deleted")
    @JsonAdapter(TolerantBooleanDeserializer::class)
    val deleted: Boolean = false
)

/**
 * 布尔字段容错反序列化：兼容 true/false、1/0、"1"/"0"、"true"/"false"；
 * null、数组、对象一律按 false 处理。
 */
class TolerantBooleanDeserializer : JsonDeserializer<Boolean> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Boolean {
        if (!json.isJsonPrimitive) return false
        val primitive = json.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asDouble != 0.0
            primitive.isString -> {
                val raw = primitive.asString.trim()
                raw == "1" || raw.equals("true", ignoreCase = true)
            }
            else -> false
        }
    }
}
