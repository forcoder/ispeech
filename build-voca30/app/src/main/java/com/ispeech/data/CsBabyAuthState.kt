package com.ispeech.data

/**
 * csBaby 认证状态快照：用于 UI 判断登录态与令牌过期。
 */
data class CsBabyAuthState(
    val userId: String,
    val tenantId: String,
    val token: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val identifier: String,
) {
    /** token 非空即视为已登录。 */
    val isLoggedIn: Boolean get() = token.isNotBlank()

    /** 到期时间大于 0 且已过期。 */
    val isExpired: Boolean
        get() = expiresAtEpochMs > 0 && expiresAtEpochMs <= System.currentTimeMillis()
}
