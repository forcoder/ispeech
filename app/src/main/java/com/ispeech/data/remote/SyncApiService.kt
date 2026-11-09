package com.ispeech.data.remote

import com.ispeech.data.RemoteKeywordRule
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * csBaby 数据同步接口。
 */
interface SyncApiService {

    /**
     * 全量数据同步：GET sync/all
     *
     * @param tenantId 租户标识，为空时不下发该查询参数
     * @param authorization 认证头，格式 "Bearer <token>"
     */
    @GET("sync/all")
    suspend fun syncAll(
        @Query("tenantId") tenantId: String?,
        @Header("Authorization") authorization: String
    ): SyncAllData

    /** 列出全部关键词规则：GET api/rules（上传前按触发码去重用）。 */
    @GET("api/rules")
    suspend fun listRules(
        @Header("Authorization") authorization: String
    ): List<RemoteKeywordRule>

    /** 创建关键词规则：POST api/rules */
    @POST("api/rules")
    suspend fun createRule(
        @Body body: RuleUpsertRequest,
        @Header("Authorization") authorization: String
    ): RemoteKeywordRule

    /**
     * 批量推送规则变更：POST sync/push
     *
     * rules 承载新增 / 修改（带 id 视为覆盖远端已有条目），deletedIds 承载删除。
     * 后端响应可能包一层 {code,data}，按 [SyncPushEnvelope] 解析。
     */
    @POST("sync/push")
    suspend fun pushSync(
        @Body body: SyncPushRequest,
        @Header("Authorization") authorization: String
    ): SyncPushEnvelope
}
