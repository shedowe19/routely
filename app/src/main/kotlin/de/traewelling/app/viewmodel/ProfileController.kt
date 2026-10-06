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

internal interface ProfileGateway {
    suspend fun getCurrentUser(): Result<User>
    suspend fun getStatistics(): Result<StatisticsData>
    suspend fun getUserStatuses(username: String, page: Int): Result<StatusListResponse>
}

data class ProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val statistics: StatisticsData? = null,
    val recentStatuses: List<Status> = emptyList(),
    val error: String? = null
)

internal class ProfileController(
    private val scope: CoroutineScope,
    private val repo: ProfileGateway,
    private val sessionProvider: suspend () -> AuthSession,
    mutations: Flow<StatusMutation>
) {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var needsStatusVerification = false

    init {
        scope.observeProfileMutations(sessionProvider, mutations) { mutation ->
            val previous = _uiState.value
            val loadedCard = previous.recentStatuses.any { it.id == mutation.statusId }
            if (!loadedCard && !previous.isLoading && !needsStatusVerification) return@observeProfileMutations
            ++generation
            loadJob?.cancel()
            if (mutation is StatusMutation.Invalidated) needsStatusVerification = true
            _uiState.update { it.copy(isLoading = false,
                recentStatuses = it.recentStatuses.applyProfileMutation(mutation)) }
            if (previous.isLoading || needsStatusVerification) loadProfile(refresh = true)
        }
    }

    fun loadProfile(refresh: Boolean = false) {
        if (_uiState.value.isLoading && !refresh) return
        val request = ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }

        loadJob = scope.launch {
            val session = sessionProvider()
            val userResult  = repo.getCurrentUser()
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            val user = userResult.getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, error = "Profil konnte nicht geladen werden: ${error.message}") }
                return@launch
            }
            _uiState.update { it.copy(user = user) }
            val statsResult = repo.getStatistics()
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            val statusesResult = repo.getUserStatuses(user.username, 1)
            coroutineContext.ensureActive()
            if (request != generation || sessionProvider() != session) return@launch
            if (statusesResult.isSuccess) needsStatusVerification = false
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
