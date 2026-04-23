package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class FeedType { DASHBOARD, GLOBAL }

data class FeedUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val statuses: List<Status> = emptyList(),
    val error: String? = null,
    val feedType: FeedType = FeedType.DASHBOARD,
    val hasMore: Boolean = false,
    val currentPage: Int = 1
)

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    fun loadFeed(refresh: Boolean = false) {
        val feedType = _uiState.value.feedType
        val page = if (refresh) 1 else _uiState.value.currentPage

        viewModelScope.launch {
            _uiState.update {
                if (refresh) it.copy(isRefreshing = true, error = null)
                else         it.copy(isLoading = true, error = null)
            }

            val result = when (feedType) {
                FeedType.DASHBOARD -> repo.getDashboard(page)
                FeedType.GLOBAL    -> repo.getGlobalFeed(page)
            }

            result.onSuccess { response ->
                val fetched = response.data ?: emptyList()
                val newStatuses = if (refresh || page == 1) {
                    fetched
                } else {
                    _uiState.value.statuses + fetched
                }
                // Dashboard API uses cursor pagination — no last_page field!
                // Use links.next to determine if more pages exist.
                val hasMore = response.links?.next != null
                val meta    = response.meta
                _uiState.update {
                    it.copy(
                        isLoading    = false,
                        isRefreshing = false,
                        statuses     = newStatuses,
                        hasMore      = hasMore,
                        currentPage  = (meta?.currentPage ?: 1) + 1,
                        error        = null
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = e.message)
                }
            }
        }
    }

    fun refresh() = loadFeed(refresh = true)

    fun loadMore() {
        if (!_uiState.value.isLoading && _uiState.value.hasMore) {
            loadFeed(refresh = false)
        }
    }

    fun switchFeedType(type: FeedType) {
        if (type == _uiState.value.feedType) return
        _uiState.update { it.copy(feedType = type, statuses = emptyList(), currentPage = 1) }
        loadFeed(refresh = true)
    }

    fun likeStatus(statusId: Int) {
        viewModelScope.launch {
            val currentStatus = _uiState.value.statuses.find { it.id == statusId } ?: return@launch
            val isLiked = currentStatus.liked == true
            val likes   = currentStatus.likes ?: 0
            // Optimistic update
            updateStatusInList(statusId) {
                it.copy(liked = !isLiked, likes = if (isLiked) likes - 1 else likes + 1)
            }
            val result = if (!isLiked) repo.likeStatus(statusId)
                         else          repo.unlikeStatus(statusId)
            result.onFailure {
                // Revert on failure
                updateStatusInList(statusId) {
                    it.copy(liked = isLiked, likes = likes)
                }
            }
        }
    }

    private fun updateStatusInList(statusId: Int, transform: (Status) -> Status) {
        _uiState.update { state ->
            state.copy(statuses = state.statuses.map { s ->
                if (s.id == statusId) transform(s) else s
            })
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
