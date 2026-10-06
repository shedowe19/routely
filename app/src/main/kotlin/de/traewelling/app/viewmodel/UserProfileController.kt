package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.*
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal interface UserProfileGateway {
    suspend fun getUserProfile(username: String): Result<User>
    suspend fun getUserStatuses(username: String, page: Int): Result<StatusListResponse>
    suspend fun followUser(id: Int): Result<Unit>
    suspend fun unfollowUser(id: Int): Result<Unit>
}

data class UserProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val statuses: List<Status> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val isFollowLoading: Boolean = false
)

internal class UserProfileController(
    private val scope: CoroutineScope,
    private val repo: UserProfileGateway,
    private val sessionProvider: suspend () -> AuthSession,
    mutations: Flow<StatusMutation>
) {
    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    private var currentUsername: String? = null
    private var loadJob: Job? = null
    private var followJob: Job? = null
    private var generation = 0L
    private var followGeneration = 0L
    private var followError: String? = null
    private var needsStatusVerification = false

    init {
        scope.observeProfileMutations(sessionProvider, mutations) { mutation ->
            val username = currentUsername ?: return@observeProfileMutations
            val previous = _uiState.value
            val loadedCard = previous.statuses.any { it.id == mutation.statusId }
            if (!loadedCard && !previous.isLoading && !needsStatusVerification) return@observeProfileMutations
            ++generation
            loadJob?.cancel()
            if (mutation is StatusMutation.Invalidated || previous.isLoading) needsStatusVerification = true
            _uiState.update { it.copy(isLoading = false,
                statuses = it.statuses.applyProfileMutation(mutation)) }
            // Card writes do not cancel a submitted follow. Verify after that independent action.
            if (needsStatusVerification && !previous.isFollowLoading) loadUserProfile(username, refresh = true)
        }
    }

    fun loadUserProfile(username: String, refresh: Boolean = false) {
        if (currentUsername == username && _uiState.value.isFollowLoading) {
            // Refresh the same profile after its submitted follow returns, without cancel/reoffer.
            if (refresh) needsStatusVerification = true
            return
        }
        // If already loading the same user, skip
        if (!refresh && currentUsername == username &&
            (_uiState.value.isLoading || (_uiState.value.user != null && _uiState.value.error == null))) return
        val request = ++generation
        ++followGeneration
        loadJob?.cancel()
        followJob?.cancel()
        val sameProfile = currentUsername == username
        if (!sameProfile) {
            needsStatusVerification = false
            followError = null
        }
        currentUsername = username
        _uiState.value = if (sameProfile) _uiState.value.copy(isLoading = true,
            isFollowLoading = false, error = followError) else UserProfileUiState(isLoading = true)

        loadJob = scope.launch {
            val session = sessionProvider()
            val profile = repo.getUserProfile(username)
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            profile
                .onSuccess { user ->
                    _uiState.update { it.copy(user = user) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Profil konnte nicht geladen werden: ${e.message}")
                    }
                    return@launch
                }

            val statuses = repo.getUserStatuses(username, 1)
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            statuses
                .onSuccess { response ->
                    needsStatusVerification = false
                    val hasMore = response.links?.next != null
                    _uiState.update {
                        it.copy(
                            isLoading   = false,
                            statuses    = response.data.orEmpty().distinctBy { status -> status.id },
                            hasMore     = hasMore,
                            currentPage = (response.meta?.currentPage ?: 1) + 1,
                            error       = followError
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Fahrten konnten nicht geladen werden: ${e.message}")
                    }
                }
        }
    }

    fun loadMoreStatuses() {
        val username = currentUsername ?: return
        if (_uiState.value.isLoading || !_uiState.value.hasMore) return
        if (needsStatusVerification) {
            if (!_uiState.value.isFollowLoading) loadUserProfile(username, refresh = true)
            return
        }
        val request = generation
        val page = _uiState.value.currentPage
        _uiState.update { it.copy(isLoading = true, error = followError) }

        loadJob = scope.launch {
            val session = sessionProvider()
            val result = repo.getUserStatuses(username, page)
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            result
                .onSuccess { response ->
                    val hasMore = response.links?.next != null
                    _uiState.update {
                        it.copy(
                            isLoading   = false,
                            statuses    = (it.statuses + response.data.orEmpty()).distinctBy { status -> status.id },
                            hasMore     = hasMore,
                            currentPage = (response.meta?.currentPage ?: page) + 1
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Weitere Fahrten konnten nicht geladen werden: ${e.message}") }
                }
        }
    }

    fun toggleFollow() {
        if (_uiState.value.isFollowLoading) return
        val user = _uiState.value.user ?: return
        // The API has no cancellation endpoint for a pending private-profile request.
        if (user.followPending == true && user.following != true) return
        val userId = user.id ?: return
        val request = followGeneration
        followError = null
        _uiState.update { it.copy(isFollowLoading = true, error = null) }

        followJob = scope.launch {
            val session = sessionProvider()
            val isCurrentlyFollowing = user.following == true
            val result = if (isCurrentlyFollowing) {
                repo.unfollowUser(userId)
            } else {
                repo.followUser(userId)
            }
            coroutineContext.ensureActive()
            if (request != followGeneration || sessionProvider() != session) return@launch

            result.onSuccess {
                // If user has private profile and we just followed, set followPending
                val newFollowing = if (!isCurrentlyFollowing && user.privateProfile == true) {
                    false
                } else {
                    !isCurrentlyFollowing
                }
                val newPending = if (!isCurrentlyFollowing && user.privateProfile == true) {
                    true
                } else {
                    false
                }
                _uiState.update {
                    it.copy(
                        isFollowLoading = false,
                        user = user.copy(following = newFollowing, followPending = newPending)
                    )
                }
            }.onFailure { e ->
                followError = "Folgen konnte nicht geändert werden: ${e.message}"
                _uiState.update { it.copy(isFollowLoading = false, error = followError) }
            }
            if (needsStatusVerification) refresh()
        }
    }

    fun refresh() {
        currentUsername?.let { loadUserProfile(it, refresh = true) }
    }

    fun reset(username: String? = null) {
        if (username != null && currentUsername != username) return
        ++generation
        ++followGeneration
        loadJob?.cancel()
        followJob?.cancel()
        currentUsername = null
        needsStatusVerification = false
        followError = null
        _uiState.value = UserProfileUiState()
    }

    fun clearError() {
        followError = null
        _uiState.update { it.copy(error = null) }
    }
}
