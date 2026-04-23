package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
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
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    private var currentUsername: String? = null

    fun loadUserProfile(username: String) {
        // If already loading the same user, skip
        if (currentUsername == username && _uiState.value.user != null) return
        currentUsername = username

        viewModelScope.launch {
            _uiState.update { UserProfileUiState(isLoading = true) }

            repo.getUserProfile(username)
                .onSuccess { user ->
                    _uiState.update { it.copy(user = user) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Profil konnte nicht geladen werden: ${e.message}")
                    }
                    return@launch
                }

            repo.getUserStatuses(username, 1)
                .onSuccess { response ->
                    val hasMore = response.links?.next != null
                    _uiState.update {
                        it.copy(
                            isLoading   = false,
                            statuses    = response.data ?: emptyList(),
                            hasMore     = hasMore,
                            currentPage = 2,
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

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val page = _uiState.value.currentPage

            repo.getUserStatuses(username, page)
                .onSuccess { response ->
                    val hasMore = response.links?.next != null
                    _uiState.update {
                        it.copy(
                            isLoading   = false,
                            statuses    = it.statuses + (response.data ?: emptyList()),
                            hasMore     = hasMore,
                            currentPage = page + 1
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false) }
                }
        }
    }

    fun toggleFollow() {
        val user = _uiState.value.user ?: return
        val userId = user.id ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }

            val isCurrentlyFollowing = user.following == true
            val result = if (isCurrentlyFollowing) {
                repo.unfollowUser(userId)
            } else {
                repo.followUser(userId)
            }

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
            }.onFailure {
                _uiState.update { it.copy(isFollowLoading = false) }
            }
        }
    }

    fun refresh() {
        currentUsername?.let {
            currentUsername = null
            loadUserProfile(it)
        }
    }

    fun reset() {
        currentUsername = null
        _uiState.value = UserProfileUiState()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
