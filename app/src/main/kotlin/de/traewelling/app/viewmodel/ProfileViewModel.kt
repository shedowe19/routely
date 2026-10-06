package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L

    fun loadProfile(refresh: Boolean = false) {
        if (_uiState.value.isLoading && !refresh) return
        val request = ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }

        loadJob = viewModelScope.launch {
            val userResult  = repo.getCurrentUser()
            coroutineContext.ensureActive()
            if (request != generation) return@launch
            val user = userResult.getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, error = "Profil konnte nicht geladen werden: ${error.message}") }
                return@launch
            }
            _uiState.update { it.copy(user = user) }
            val statsResult = repo.getStatistics()
            coroutineContext.ensureActive()
            if (request != generation) return@launch
            val statusesResult = repo.getUserStatuses(user.username, 1)
            coroutineContext.ensureActive()
            if (request != generation) return@launch
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statistics = statsResult.getOrNull() ?: it.statistics,
                    recentStatuses = statusesResult.getOrNull()?.data?.distinctBy { status -> status.id } ?: it.recentStatuses,
                    error = when {
                        statsResult.isFailure -> "Statistiken konnten nicht geladen werden: ${statsResult.exceptionOrNull()?.message}"
                        statusesResult.isFailure -> "Fahrten konnten nicht geladen werden: ${statusesResult.exceptionOrNull()?.message}"
                        else -> null
                    }
                )
            }
        }
    }

    fun refresh() {
        loadProfile(refresh = true)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
