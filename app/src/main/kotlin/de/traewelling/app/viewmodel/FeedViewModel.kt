package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private val pendingLikes = mutableSetOf<Int>()

    fun loadFeed(refresh: Boolean = false) {
        if (!refresh && (_uiState.value.isLoading || _uiState.value.isRefreshing)) return
        if (refresh && _uiState.value.isRefreshing) return
        val feedType = _uiState.value.feedType
        val page = if (refresh) 1 else _uiState.value.currentPage
        val request = ++generation
        loadJob?.cancel()
        _uiState.update {
            it.copy(isLoading = !refresh, isRefreshing = refresh, error = null)
        }

        loadJob = viewModelScope.launch {
            val result = when (feedType) {
                FeedType.DASHBOARD -> repo.getDashboard(page)
                FeedType.GLOBAL    -> repo.getGlobalFeed(page)
            }
            coroutineContext.ensureActive()
            if (request != generation) return@launch

            result.onSuccess { response ->
                val fetched = response.data ?: emptyList()
                val newStatuses = if (refresh || page == 1) {
                    fetched
                } else {
                    _uiState.value.statuses + fetched
                }
                val hasMore = response.links?.next != null
                val meta    = response.meta
                _uiState.update {
                    it.copy(
                        isLoading    = false,
                        isRefreshing = false,
                        statuses     = newStatuses.distinctBy { status -> status.id },
                        hasMore      = hasMore,
                        currentPage  = (meta?.currentPage ?: page) + 1,
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
        if (!_uiState.value.isLoading && !_uiState.value.isRefreshing && _uiState.value.hasMore) {
            loadFeed(refresh = false)
        }
    }

    fun switchFeedType(type: FeedType) {
        if (type == _uiState.value.feedType) return
        ++generation
        loadJob?.cancel()
        _uiState.value = FeedUiState(feedType = type)
        loadFeed(refresh = true)
    }

    fun likeStatus(statusId: Int) {
        val currentStatus = _uiState.value.statuses.find { it.id == statusId } ?: return
        if (currentStatus.isLikable == false || !pendingLikes.add(statusId)) return
        viewModelScope.launch {
            val isLiked = currentStatus.liked == true
            val likes   = currentStatus.likes ?: 0
            val optimisticLikes = if (isLiked) (likes - 1).coerceAtLeast(0) else likes + 1
            // Optimistic update
            updateStatusInList(statusId) {
                it.copy(liked = !isLiked, likes = optimisticLikes)
            }
            try {
                val result = if (!isLiked) repo.likeStatus(statusId) else repo.unlikeStatus(statusId)
                coroutineContext.ensureActive()
                result.onFailure {
                    updateStatusInList(statusId) { status ->
                        // A refreshed server snapshot may already have replaced this optimistic value.
                        if (status.liked == !isLiked && status.likes == optimisticLikes)
                            status.copy(liked = isLiked, likes = likes) else status
                    }
                }
            } finally {
                pendingLikes.remove(statusId)
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
