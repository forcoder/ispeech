package com.ispeech.data.remote

import retrofit2.http.GET
import retrofit2.http.Header
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
}
