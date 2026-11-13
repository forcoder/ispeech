package com.ispeech.settings.keyboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.data.UserDictEntry
import com.ispeech.di.AppContainer
import com.ispeech.settings.store.KeyboardSettings
import com.ispeech.settings.store.KeyboardSize
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 键盘与输入页 ViewModel：读写键盘参数（DataStore）+ 管理用户词库。 */
class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _settings = MutableStateFlow(KeyboardSettings())
    val settings: StateFlow<KeyboardSettings> = _settings.asStateFlow()

    private val _userWords = MutableStateFlow<List<UserDictEntry>>(emptyList())
    val userWords: StateFlow<List<UserDictEntry>> = _userWords.asStateFlow()

    init {
        // 订阅持久化数据，启动时同步一次
        viewModelScope.launch {
            SettingsStore.keyboardSettingsFlow(app).collect { _settings.value = it }
        }
        viewModelScope.launch {
            SettingsStore.inputMethodModeFlow(app).collect { _inputMethodMode.value = it }
        }
        viewModelScope.launch {
            SettingsStore.voiceSpaceLongPressMsFlow(app).collect { _spaceLongPressMs.value = it }
        }
        refreshUserWords()
    }

    // —— 键盘参数：先更新内存，再写 DataStore，界面即时响应 ——

    fun setSize(size: KeyboardSize) {
        _settings.value = _settings.value.copy(keyHeightDp = size.heightDp)
        viewModelScope.launch { SettingsStore.setKeyHeightDp(app, size.heightDp) }
    }

    fun setCandidatePageSize(size: Int) {
        _settings.value = _settings.value.copy(candidatePageSize = size)
        viewModelScope.launch { SettingsStore.setCandidatePageSize(app, size) }
    }

    fun setVibrate(enabled: Boolean) {
        _settings.value = _settings.value.copy(vibrate = enabled)
        viewModelScope.launch { SettingsStore.setVibrate(app, enabled) }
    }

    fun setChinesePunctuation(enabled: Boolean) {
        _settings.value = _settings.value.copy(chinesePunctuation = enabled)
        viewModelScope.launch { SettingsStore.setChinesePunctuation(app, enabled) }
    }

    fun setFullWidth(enabled: Boolean) {
        _settings.value = _settings.value.copy(fullWidth = enabled)
        viewModelScope.launch { SettingsStore.setFullWidth(app, enabled) }
    }

    fun setAssociation(enabled: Boolean) {
        _settings.value = _settings.value.copy(association = enabled)
        viewModelScope.launch { SettingsStore.setAssociation(app, enabled) }
    }

    fun setLearning(enabled: Boolean) {
        _settings.value = _settings.value.copy(learning = enabled)
        viewModelScope.launch { SettingsStore.setLearning(app, enabled) }
    }

    fun setPinyinMixed(enabled: Boolean) {
        _settings.value = _settings.value.copy(pinyinMixed = enabled)
        viewModelScope.launch {
            SettingsStore.setPinyinMixed(app, enabled)
            // 引擎按设置构建，改开关后必须丢弃缓存重建，否则新设置不生效
            AppContainer.invalidateEngine()
        }
    }

    fun setFuzzyPinyin(enabled: Boolean) {
        _settings.value = _settings.value.copy(fuzzyPinyin = enabled)
        viewModelScope.launch {
            SettingsStore.setFuzzyPinyin(app, enabled)
            AppContainer.invalidateEngine()
        }
    }

    fun setTraditionalOutput(enabled: Boolean) {
        _settings.value = _settings.value.copy(traditionalOutput = enabled)
        viewModelScope.launch { SettingsStore.setTraditionalOutput(app, enabled) }
    }

    private val _inputMethodMode = MutableStateFlow(SettingsStore.InputMethodMode.PINYIN)
    val inputMethodMode: StateFlow<SettingsStore.InputMethodMode> = _inputMethodMode.asStateFlow()

    private val _spaceLongPressMs = MutableStateFlow(500)
    val spaceLongPressMs: StateFlow<Int> = _spaceLongPressMs.asStateFlow()

    fun setInputMethodMode(mode: SettingsStore.InputMethodMode) {
        _inputMethodMode.value = mode
        viewModelScope.launch { SettingsStore.setInputMethodMode(app, mode) }
    }

    fun setSpaceLongPressMs(ms: Int) {
        _spaceLongPressMs.value = ms
        viewModelScope.launch { SettingsStore.setVoiceSpaceLongPressMs(app, ms) }
    }

    // —— 用户词库 ——

    /** 重新读取用户学习到的词条（IO 线程）。 */
    fun refreshUserWords() {
        viewModelScope.launch(Dispatchers.IO) {
            val words = runCatching { AppContainer.userDict(app).all() }.getOrDefault(emptyList())
            _userWords.value = words
        }
    }

    /** 删除单条用户词条，并让引擎缓存失效以便下次查询重建。 */
    fun removeUserWord(entry: UserDictEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val store = AppContainer.userDict(app)
                store.remove(entry.code, entry.text, entry.mode)
                // 误学率（7 天内被删除的词组 / 写入总数）目前不落盘统计，只留一条日志供
                // `adb logcat` 抽样（ADR-0001 §10 已知缺口）；需要量化时另立 issue 加计数
                Log.i("UserDictStore", "phrase_remove code=${entry.code} text=${entry.text}")
                AppContainer.invalidateUserDict()
                store.all()
            }.onSuccess { _userWords.value = it }
        }
    }

    /** 清空用户词库。 */
    fun clearUserWords() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val store = AppContainer.userDict(app)
                store.clear()
                AppContainer.invalidateUserDict()
                store.all()
            }.onSuccess { _userWords.value = it }
        }
    }
}
