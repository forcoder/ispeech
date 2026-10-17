package com.ispeech.settings.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ispeech.data.InputStats
import com.ispeech.data.StatsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 输入统计页 ViewModel：读取统计快照（IO 线程）+ 重置全部统计。 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()

    private val _snapshot = MutableStateFlow(StatsSnapshot.EMPTY)
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    init {
        refresh()
    }

    /** 重新读取统计快照（读盘放 IO 线程，避免卡界面）。 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _snapshot.value = runCatching { InputStats.get(app).snapshot() }
                .getOrDefault(StatsSnapshot.EMPTY)
        }
    }

    /** 重置全部统计，并刷新界面。 */
    fun reset() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { InputStats.get(app).reset() }
            _snapshot.value = runCatching { InputStats.get(app).snapshot() }
                .getOrDefault(StatsSnapshot.EMPTY)
        }
    }
}
