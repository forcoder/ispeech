package com.ispeech.settings.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.R
import com.ispeech.data.CsBabyAuthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 首页界面状态。 */
data class HomeUiState(
    val accountSummary: String = "",
    val loggedIn: Boolean = false,
)

/** 首页 ViewModel：维护 csBaby 登录态摘要，决定账号卡片跳登录还是同步。 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 刷新登录态摘要（进入首页时调用）。 */
    fun refresh() {
        viewModelScope.launch {
            val auth = runCatching { CsBabyAuthStore.load(app) }.getOrNull()
            val summary = if (auth == null) {
                app.getString(R.string.csbaby_not_logged_in) + "，点击登录或注册"
            } else {
                app.getString(R.string.csbaby_logged_in, auth.identifier)
            }
            _uiState.update { it.copy(accountSummary = summary, loggedIn = auth != null) }
        }
    }
}
