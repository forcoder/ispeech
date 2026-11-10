package com.ispeech.settings.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.engine.WubiEngine
import com.ispeech.engine.WubiLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 关于页界面状态：引擎统计。 */
data class AboutUiState(
    val statsLoading: Boolean = true,
    val entryCount: Int = 0,
    val codeCount: Int = 0,
    val singleCount: Int = 0,
    val phraseCount: Int = 0,
    val summary: String = "",
    val statsError: String? = null,
)

/** 关于页 ViewModel：复用内置词库统计信息。 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = WubiLocator.openDict(app)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("未找到内置词典 assets/wubi86.dict")
                WubiEngine(text)
            }.onSuccess { engine ->
                _uiState.update {
                    it.copy(
                        statsLoading = false,
                        entryCount = engine.entryCount,
                        codeCount = engine.codeCount,
                        singleCount = engine.singleCount,
                        phraseCount = engine.phraseCount,
                        summary = engine.summary(),
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(statsLoading = false, statsError = throwable.message ?: "未知错误")
                }
            }
        }
    }
}
