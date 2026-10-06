package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal interface FeedGateway {
    suspend fun getDashboard(page: Int): Result<StatusListResponse>
    suspend fun getGlobalFeed(page: Int): Result<StatusListResponse>
    suspend fun likeStatus(id: Int): Result<Unit>
    suspend fun unlikeStatus(id: Int): Result<Unit>
}

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

/** Session-bound network ordering and immediate status mutation adoption. */
internal class FeedController(
    private val scope: CoroutineScope,
    private val gateway: FeedGateway,
    private val sessionProvider: suspend () -> AuthSession,
    mutations: Flow<StatusMutation>
) {
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var needsMutationVerification = false
    private var likeRevision = 0L
    private val likeIntents = mutableMapOf<Int, LikeIntent>()

    private class LikeIntent(
        val liked: Boolean,
        var baselineLiked: Boolean,
        var baselineLikes: Int,
        var displayLikes: Int,
        var confirmedAt: Long? = null
    )

    init {
        scope.launch {
            val boundSession = sessionProvider()
            coroutineContext.ensureActive()
            mutations.collect { mutation ->
                if (mutation.sessionRevision != boundSession.revision) return@collect
                val session = sessionProvider()
                coroutineContext.ensureActive()
                if (session.accessToken == null || mutation.sessionRevision != session.revision) return@collect
                // No GET started before a successful write may resurrect its old card.
                ++generation
                loadJob?.cancel()
                if (mutation is StatusMutation.Invalidated) needsMutationVerification = true
                _uiState.update { state ->
                    val statuses = when (mutation) {
                        is StatusMutation.Deleted -> {
                            likeIntents.remove(mutation.statusId)
                            state.statuses.filterNot { it.id == mutation.statusId }
                        }
                        is StatusMutation.Invalidated -> {
                            likeIntents.remove(mutation.statusId)
                            state.statuses.filterNot { it.id == mutation.statusId }
                        }
                        is StatusMutation.Updated -> state.statuses.map { existing ->
                            if (existing.id != mutation.statusId) existing
                            else overlayLike(mutation.status)
                        }
                        is StatusMutation.LikeChanged -> state.statuses.map { existing ->
                            if (existing.id != mutation.statusId) existing
                            else if (existing.id in likeIntents) {
                                val replacement = overlayLike(existing, recordBaseline = false)
                                val intent = likeIntents[existing.id]
                                if (intent?.confirmedAt != null && intent.liked == mutation.liked)
                                    likeIntents.remove(existing.id)
                                replacement
                            }
                            else existing.copy(liked = mutation.liked,
                                likes = adjustedLikes(existing, mutation.liked))
                        }
                    }
                    state.copy(statuses = statuses, isLoading = false, isRefreshing = false)
                }
                // A following successful write can cancel the verification GET before its removed
                // card returns. Retain the requirement until a fresh feed actually arrives.
                if (needsMutationVerification) refresh()
            }
        }
    }

    fun loadFeed(refresh: Boolean = false) {
        if (!refresh && (_uiState.value.isLoading || _uiState.value.isRefreshing)) return
        if (refresh && _uiState.value.isRefreshing) return
        val feedType = _uiState.value.feedType
        val replaceFeed = refresh || needsMutationVerification
        val page = if (replaceFeed) 1 else _uiState.value.currentPage
        val request = ++generation
        val requestLikeRevision = likeRevision
        loadJob?.cancel()
        _uiState.update {
            it.copy(isLoading = !refresh, isRefreshing = refresh, error = null)
        }

        loadJob = scope.launch {
            val session = sessionProvider()
            val result = when (feedType) {
                FeedType.DASHBOARD -> gateway.getDashboard(page)
                FeedType.GLOBAL    -> gateway.getGlobalFeed(page)
            }
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch

            result.onSuccess { response ->
                val fetched = response.data.orEmpty().map { status ->
                    val intent = likeIntents[status.id]
                    // Only a GET begun after confirmation can authoritatively replace that intent.
                    // Older GETs and every GET during submission retain the optimistic value.
                    if (intent?.confirmedAt?.let { it <= requestLikeRevision } == true) {
                        likeIntents.remove(status.id)
                        status
                    } else overlayLike(status)
                }
                val newStatuses = if (replaceFeed || page == 1) {
                    fetched
                } else {
                    _uiState.value.statuses + fetched
                }
                val hasMore = response.links?.next != null
                val meta    = response.meta
                val loadedIds = newStatuses.mapTo(mutableSetOf()) { it.id }
                likeIntents.entries.removeAll { it.value.confirmedAt != null && it.key !in loadedIds }
                needsMutationVerification = false
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
        // Confirmed values need no permanent per-card ledger: new feed requests are authoritative.
        // Keep only still-submitted intents, which must survive a tab switch until they finish.
        likeIntents.entries.removeAll { it.value.confirmedAt != null }
        _uiState.value = FeedUiState(feedType = type)
        loadFeed(refresh = true)
    }

    fun likeStatus(statusId: Int) {
        val currentStatus = _uiState.value.statuses.find { it.id == statusId } ?: return
        val previousIntent = likeIntents[statusId]
        if (currentStatus.isLikable == false || previousIntent != null && previousIntent.confirmedAt == null) return
        val intent = LikeIntent(
            liked = currentStatus.liked != true,
            baselineLiked = currentStatus.liked == true,
            baselineLikes = currentStatus.likes ?: 0,
            displayLikes = adjustedLikes(currentStatus, currentStatus.liked != true)
        )
        likeIntents[statusId] = intent
        ++likeRevision
        updateStatusInList(statusId) { it.copy(liked = intent.liked, likes = intent.displayLikes) }
        scope.launch {
            val session = sessionProvider()
            try {
                val result = if (intent.liked) gateway.likeStatus(statusId) else gateway.unlikeStatus(statusId)
                coroutineContext.ensureActive()
                if (sessionProvider() != session || likeIntents[statusId] !== intent) return@launch
                if (result.isSuccess) {
                    intent.confirmedAt = ++likeRevision
                    // The repository also rejects/cancels old GETs and invalidates their Room cache.
                    // Clear the corresponding busy state even when that GET exited by cancellation.
                    ++generation
                    loadJob?.cancel()
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                    if (needsMutationVerification) refresh()
                } else {
                    likeIntents.remove(statusId)
                    updateStatusInList(statusId) {
                        it.copy(liked = intent.baselineLiked, likes = intent.baselineLikes)
                    }
                }
            } finally {
                // Do not discard a confirmed intent until a later GET, or a stale card event could
                // immediately undo it. Cancellation may only release this exact pending operation.
                if (likeIntents[statusId] === intent && intent.confirmedAt == null)
                    likeIntents.remove(statusId)
            }
        }
    }

    private fun adjustedLikes(status: Status, liked: Boolean): Int {
        val likes = status.likes ?: 0
        return if (status.liked == liked) likes
            else if (liked) likes + 1 else (likes - 1).coerceAtLeast(0)
    }

    private fun overlayLike(status: Status, recordBaseline: Boolean = true): Status {
        val intent = likeIntents[status.id] ?: return status
        if (intent.confirmedAt == null && recordBaseline) {
            intent.baselineLiked = status.liked == true
            intent.baselineLikes = status.likes ?: 0
            intent.displayLikes = adjustedLikes(status, intent.liked)
        }
        return status.copy(liked = intent.liked, likes = intent.displayLikes)
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
