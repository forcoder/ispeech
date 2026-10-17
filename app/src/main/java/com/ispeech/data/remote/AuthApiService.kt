package com.ispeech.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * csBaby 认证接口：登录、注册、刷新令牌。
 * 基地址由 CsBabyClient 统一配置。
 */
interface AuthApiService {

    /** 登录：POST api/auth/user/login */
    @POST("api/auth/user/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** 注册：POST api/auth/user/register */
    @POST("api/auth/user/register")
    suspend fun register(@Body body: RegisterRequest): LoginResponse

    /** 刷新令牌：POST auth/refresh */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): LoginResponse
}
