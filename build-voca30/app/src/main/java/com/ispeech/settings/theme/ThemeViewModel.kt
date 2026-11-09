package com.ispeech.settings.theme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.settings.store.SettingsStore
import com.ispeech.settings.store.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主题外观页 ViewModel：读取 / 保存主题模式（DataStore）。 */
class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        // 订阅持久化数据，启动时同步一次
        viewModelScope.launch {
            SettingsStore.themeModeFlow(app).collect { _themeMode.value = it }
        }
    }

    /** 选中即生效并持久化，IspeechTheme 会实时响应。 */
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch { SettingsStore.setThemeMode(app, mode) }
    }
}
