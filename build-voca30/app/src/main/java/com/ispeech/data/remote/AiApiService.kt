package com.ispeech.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * csBaby AI 生成接口：优先命中关键词规则，未命中时调用用户配置的 LLM。
 * 基地址由 CsBabyClient 统一配置。
 */
interface AiApiService {

    /** 生成回复：POST api/ai/generate */
    @POST("api/ai/generate")
    suspend fun generate(
        @Body body: GenerateRequest,
        @Header("Authorization") authorization: String
    ): GenerateResponse
}
