package com.ispeech.settings.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** 主题模式：浅色 / 深色 / 跟随系统。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** 应用级 DataStore：存放设置界面的偏好项（与 csbaby_auth 分开）。 */
private val Context.ispeechSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ispeech_settings")

/**
 * 设置项统一读写入口（DataStore Preferences）。
 *
 * - 词典页三个规则开关：启用快捷短语 / 启用远程规则 / 单字母不触发快捷规则；
 * - 主题模式（浅色 / 深色 / 跟随系统）；
 * - 键盘与输入：高度、每页候选数、震动、标点、全角、联想、词频学习。
 */
object SettingsStore {

    private val KEY_ENABLE_QUICK_PHRASES = booleanPreferencesKey("enable_quick_phrases")
    private val KEY_ENABLE_REMOTE_RULES = booleanPreferencesKey("enable_remote_rules")
    private val KEY_SINGLE_LETTER_NO_TRIGGER = booleanPreferencesKey("single_letter_no_trigger")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

    // —— 键盘与输入 ——
    private val KEY_KEY_HEIGHT_DP = intPreferencesKey("key_height_dp")
    private val KEY_CANDIDATE_PAGE_SIZE = intPreferencesKey("candidate_page_size")
    private val KEY_VIBRATE = booleanPreferencesKey("keyboard_vibrate")
    private val KEY_CHINESE_PUNCTUATION = booleanPreferencesKey("chinese_punctuation")
    private val KEY_FULL_WIDTH = booleanPreferencesKey("full_width")
    private val KEY_ASSOCIATION = booleanPreferencesKey("association")
    private val KEY_LEARNING = booleanPreferencesKey("learning")
    private val KEY_PINYIN_MIXED = booleanPreferencesKey("pinyin_mixed")
    private val KEY_FUZZY_PINYIN = booleanPreferencesKey("fuzzy_pinyin")
    private val KEY_TRADITIONAL_OUTPUT = booleanPreferencesKey("traditional_output")

    /** 读取时统一兜底，避免 IO 异常导致界面崩溃。 */
    private fun Context.safeData(): Flow<Preferences> =
        applicationContext.ispeechSettingsDataStore.data.catch { emit(emptyPreferences()) }

    private suspend fun Context.editSettings(block: (MutablePreferences) -> Unit) {
        applicationContext.ispeechSettingsDataStore.edit(block)
    }

    // —— 词典页规则开关 ——

    /** 启用快捷短语（默认开）。 */
    fun enableQuickPhrasesFlow(context: Context): Flow<Boolean> =
        context.safeData().map { it[KEY_ENABLE_QUICK_PHRASES] ?: true }

    suspend fun setEnableQuickPhrases(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_ENABLE_QUICK_PHRASES] = enabled }

    /** 启用远程规则（默认开）。 */
    fun enableRemoteRulesFlow(context: Context): Flow<Boolean> =
        context.safeData().map { it[KEY_ENABLE_REMOTE_RULES] ?: true }

    suspend fun setEnableRemoteRules(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_ENABLE_REMOTE_RULES] = enabled }

    /** 单字母不触发快捷规则（默认开，避免打字中途误触）。 */
    fun singleLetterNoTriggerFlow(context: Context): Flow<Boolean> =
        context.safeData().map { it[KEY_SINGLE_LETTER_NO_TRIGGER] ?: true }

    suspend fun setSingleLetterNoTrigger(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_SINGLE_LETTER_NO_TRIGGER] = enabled }

    // —— 主题 ——

    /** 主题模式（默认跟随系统）。 */
    fun themeModeFlow(context: Context): Flow<ThemeMode> =
        context.safeData().map { prefs ->
            val raw = prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name
            runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
        }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) =
        context.editSettings { it[KEY_THEME_MODE] = mode.name }

    // —— 键盘与输入 ——

    /** 每页候选数只允许 5 / 7 / 9，其它值一律回落到 9。 */
    private fun normalizeCandidatePageSize(size: Int): Int =
        if (size == 5 || size == 7 || size == 9) size else 9

    /** 键盘高度限制在 40..72 dp 之间，避免异常值把键盘压扁或撑爆。 */
    private fun normalizeKeyHeightDp(heightDp: Int): Int = heightDp.coerceIn(40, 72)

    /** 键盘相关设置（含默认值兜底），输入法服务与设置页共用。 */
    fun keyboardSettingsFlow(context: Context): Flow<KeyboardSettings> =
        context.safeData().map { prefs ->
            KeyboardSettings(
                keyHeightDp = normalizeKeyHeightDp(prefs[KEY_KEY_HEIGHT_DP] ?: 52),
                candidatePageSize = normalizeCandidatePageSize(prefs[KEY_CANDIDATE_PAGE_SIZE] ?: 9),
                vibrate = prefs[KEY_VIBRATE] ?: true,
                chinesePunctuation = prefs[KEY_CHINESE_PUNCTUATION] ?: true,
                fullWidth = prefs[KEY_FULL_WIDTH] ?: false,
                association = prefs[KEY_ASSOCIATION] ?: true,
                learning = prefs[KEY_LEARNING] ?: true,
                pinyinMixed = prefs[KEY_PINYIN_MIXED] ?: true,
                fuzzyPinyin = prefs[KEY_FUZZY_PINYIN] ?: false,
                traditionalOutput = prefs[KEY_TRADITIONAL_OUTPUT] ?: false,
            )
        }

    /** 阻塞读取一次，供输入法服务 onStartInputView 使用（内部 runBlocking + first()）。 */
    fun keyboardSettings(context: Context): KeyboardSettings =
        runBlocking { keyboardSettingsFlow(context).first() }

    suspend fun setKeyHeightDp(context: Context, heightDp: Int) =
        context.editSettings { it[KEY_KEY_HEIGHT_DP] = normalizeKeyHeightDp(heightDp) }

    suspend fun setCandidatePageSize(context: Context, size: Int) =
        context.editSettings { it[KEY_CANDIDATE_PAGE_SIZE] = normalizeCandidatePageSize(size) }

    suspend fun setVibrate(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_VIBRATE] = enabled }

    suspend fun setChinesePunctuation(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_CHINESE_PUNCTUATION] = enabled }

    suspend fun setFullWidth(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_FULL_WIDTH] = enabled }

    suspend fun setAssociation(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_ASSOCIATION] = enabled }

    suspend fun setLearning(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_LEARNING] = enabled }

    /** 拼音混输开关（改后需重建引擎，见 AppContainer.invalidateEngine）。 */
    suspend fun setPinyinMixed(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_PINYIN_MIXED] = enabled }

    /** 模糊音开关（改后需重建引擎）。 */
    suspend fun setFuzzyPinyin(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_FUZZY_PINYIN] = enabled }

    /** 繁体输出开关（只影响上屏瞬间的转换，不需要重建引擎）。 */
    suspend fun setTraditionalOutput(context: Context, enabled: Boolean) =
        context.editSettings { it[KEY_TRADITIONAL_OUTPUT] = enabled }
}
