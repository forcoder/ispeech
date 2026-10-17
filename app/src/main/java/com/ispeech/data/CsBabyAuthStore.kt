package com.ispeech.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** 应用级 DataStore，存放 csBaby 登录凭证。 */
private val Context.csBabyAuthDataStore by preferencesDataStore(name = "csbaby_auth")

/**
 * csBaby 登录凭证本地存储（DataStore Preferences）。
 */
object CsBabyAuthStore {

    private val KEY_USER_ID = stringPreferencesKey("userId")
    private val KEY_TENANT_ID = stringPreferencesKey("tenantId")
    private val KEY_TOKEN = stringPreferencesKey("token")
    private val KEY_REFRESH_TOKEN = stringPreferencesKey("refreshToken")
    private val KEY_EXPIRES_AT = longPreferencesKey("expiresAtMs")
    private val KEY_IDENTIFIER = stringPreferencesKey("identifier")

    /** 读取登录态；从未登录或关键字段缺失时返回 null。 */
    suspend fun load(context: Context): CsBabyAuthState? {
        val prefs = context.csBabyAuthDataStore.data.first()
        val userId = prefs[KEY_USER_ID]
        val token = prefs[KEY_TOKEN]
        if (userId.isNullOrBlank() || token.isNullOrBlank()) return null
        return CsBabyAuthState(
            userId = userId,
            tenantId = prefs[KEY_TENANT_ID].orEmpty(),
            token = token,
            refreshToken = prefs[KEY_REFRESH_TOKEN].orEmpty(),
            expiresAtEpochMs = prefs[KEY_EXPIRES_AT] ?: 0L,
            identifier = prefs[KEY_IDENTIFIER].orEmpty()
        )
    }

    /** 覆盖写入登录态。 */
    suspend fun save(context: Context, s: CsBabyAuthState) {
        context.csBabyAuthDataStore.edit { prefs ->
            prefs[KEY_USER_ID] = s.userId
            prefs[KEY_TENANT_ID] = s.tenantId
            prefs[KEY_TOKEN] = s.token
            prefs[KEY_REFRESH_TOKEN] = s.refreshToken
            prefs[KEY_EXPIRES_AT] = s.expiresAtEpochMs
            prefs[KEY_IDENTIFIER] = s.identifier
        }
    }

    /** 清空登录态（退出登录）。 */
    suspend fun clear(context: Context) {
        context.csBabyAuthDataStore.edit { it.clear() }
    }
}
