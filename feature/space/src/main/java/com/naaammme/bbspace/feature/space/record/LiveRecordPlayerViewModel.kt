package com.naaammme.bbspace.feature.space.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.live.LiveRepository
import com.naaammme.bbspace.core.model.LiveRecordItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveRecordPlayerState(
    val records: List<LiveRecordItem> = emptyList(),
    val selectedIndex: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
) {
    val selected: LiveRecordItem?
        get() = records.getOrNull(selectedIndex)
}

@HiltViewModel
class LiveRecordPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: LiveRepository
) : ViewModel() {
    private val uid = savedStateHandle.get<Long>(UID_ARG) ?: 0L
    private val initialRecordId = savedStateHandle.get<Long>(RECORD_ID_ARG) ?: 0L

    private val _state = MutableStateFlow(LiveRecordPlayerState(loading = true))
    val state: StateFlow<LiveRecordPlayerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (uid <= 0L) {
            _state.update { it.copy(loading = false, error = "UP 主参数无效") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repo.fetchLiveRecords(uid = uid, page = 1, pageSize = 30)
            }.onSuccess { page ->
                val index = page.items.indexOfFirst { it.recordId == initialRecordId }
                    .takeIf { it >= 0 }
                    ?: 0
                _state.value = LiveRecordPlayerState(
                    records = page.items,
                    selectedIndex = index,
                    loading = false
                )
            }.onFailure { error ->
                Logger.w(TAG) { "加载录播播放列表失败: ${error.message}" }
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "加载录播列表失败"
                    )
                }
            }
        }
    }

    fun select(index: Int) {
        val records = _state.value.records
        if (index !in records.indices) return
        _state.update { it.copy(selectedIndex = index) }
    }

    private companion object {
        const val TAG = "LiveRecordPlayerVm"
        const val UID_ARG = "uid"
        const val RECORD_ID_ARG = "recordId"
    }
}
