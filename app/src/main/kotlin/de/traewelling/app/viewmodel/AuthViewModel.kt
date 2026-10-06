package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.repository.AuthRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val serverUrl: String = PreferencesManager.DEFAULT_SERVER_URL,
    val accessToken: String = "",
    val sessionRevision: String = "legacy",
    /** Set once after login / startup validation — shown as a one-shot snackbar. */
    val welcomeMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val repo = AuthRepository(prefs)
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private var authJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.authSession.collect { session ->
                _uiState.update { previous ->
                    val signedOut = previous.sessionRevision != session.revision && session.accessToken.isNullOrBlank()
                    previous.copy(isLoggedIn = !session.accessToken.isNullOrBlank(),
                        serverUrl = session.serverUrl, sessionRevision = session.revision,
                        isLoading = if (signedOut) false else previous.isLoading,
                        accessToken = if (signedOut) "" else previous.accessToken,
                        welcomeMessage = if (signedOut) null else previous.welcomeMessage)
                }
            }
        }
        authJob = viewModelScope.launch {
            repo.validateCurrentSession().onSuccess { username ->
                if (username != null) _uiState.update { it.copy(welcomeMessage = "Willkommen, @$username!") }
            }
        }
    }

    fun updateServerUrl(url: String) = _uiState.update { it.copy(serverUrl = url) }
    fun updateAccessToken(token: String) = _uiState.update { it.copy(accessToken = token) }

    fun loginWithToken() {
        if (_uiState.value.isLoading) return
        val serverUrl = _uiState.value.serverUrl.trim()
        val token = _uiState.value.accessToken.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(error = "Bitte Access-Token eingeben.") }
            return
        }
        authJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        authJob = viewModelScope.launch {
            repo.loginWithToken(serverUrl, token)
                .onSuccess { username ->
                    _uiState.update { it.copy(isLoading = false, accessToken = "",
                        welcomeMessage = "Willkommen, @$username!") }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(isLoading = false, error = failure.message ?: "Anmeldung fehlgeschlagen.") }
                }
        }
    }

    fun logout() {
        if (_uiState.value.isLoading) return
        authJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        authJob = viewModelScope.launch {
            repo.logout()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, accessToken = "", welcomeMessage = null) }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(isLoading = false, error = failure.message ?: "Abmeldung fehlgeschlagen.") }
                }
        }
    }

    fun clearWelcomeMessage() = _uiState.update { it.copy(welcomeMessage = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
