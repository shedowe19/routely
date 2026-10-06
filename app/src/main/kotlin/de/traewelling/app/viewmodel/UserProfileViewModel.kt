package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UserProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val statuses: List<Status> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val isFollowLoading: Boolean = false
)

class UserProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    private var currentUsername: String? = null
    private var loadJob: Job? = null
    private var followJob: Job? = null
    private var generation = 0L

    fun loadUserProfile(username: String, refresh: Boolean = false) {
        // If already loading the same user, skip
        if (!refresh && currentUsername == username &&
            (_uiState.value.isLoading || (_uiState.value.user != null && _uiState.value.error == null))) return
        val request = ++generation
        loadJob?.cancel()
        followJob?.cancel()
        currentUsername = username
        _uiState.value = UserProfileUiState(isLoading = true)

        loadJob = viewModelScope.launch {
            val profile = repo.getUserProfile(username)
            coroutineContext.ensureActive()
            if (request != generation) return@launch
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
            if (request != generation) return@launch
            statuses
                .onSuccess { response ->
                    val hasMore = response.links?.next != null
                    _uiState.update {
                        it.copy(
                            isLoading   = false,
                            statuses    = response.data.orEmpty().distinctBy { status -> status.id },
                            hasMore     = hasMore,
                            currentPage = (response.meta?.currentPage ?: 1) + 1,
                            error       = null
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
        val request = generation
        val page = _uiState.value.currentPage
        _uiState.update { it.copy(isLoading = true, error = null) }

        loadJob = viewModelScope.launch {
            val result = repo.getUserStatuses(username, page)
            coroutineContext.ensureActive()
            if (request != generation) return@launch
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
        val request = generation
        _uiState.update { it.copy(isFollowLoading = true, error = null) }

        followJob = viewModelScope.launch {
            val isCurrentlyFollowing = user.following == true
            val result = if (isCurrentlyFollowing) {
                repo.unfollowUser(userId)
            } else {
                repo.followUser(userId)
            }
            coroutineContext.ensureActive()
            if (request != generation) return@launch

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
                _uiState.update { it.copy(isFollowLoading = false, error = "Folgen konnte nicht geändert werden: ${e.message}") }
            }
        }
    }

    fun refresh() {
        currentUsername?.let { loadUserProfile(it, refresh = true) }
    }

    fun reset(username: String? = null) {
        if (username != null && currentUsername != username) return
        ++generation
        loadJob?.cancel()
        followJob?.cancel()
        currentUsername = null
        _uiState.value = UserProfileUiState()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
