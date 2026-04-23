package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val statistics: StatisticsData? = null,
    val recentStatuses: List<Status> = emptyList(),
    val error: String? = null
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val userResult  = repo.getCurrentUser()
            val statsResult = repo.getStatistics()

            userResult.onSuccess { user ->
                _uiState.update { it.copy(user = user) }
                repo.getUserStatuses(user.username, 1).onSuccess { response ->
                    _uiState.update { it.copy(recentStatuses = response.data ?: emptyList()) }
                }
            }

            statsResult.onSuccess { statsData ->
                _uiState.update { it.copy(statistics = statsData) }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (userResult.isFailure)
                        "Profil konnte nicht geladen werden: ${userResult.exceptionOrNull()?.message}"
                    else null
                )
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(user = null, statistics = null, recentStatuses = emptyList()) }
        loadProfile()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
