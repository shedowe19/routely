package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.data.repository.AuthRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val serverUrl: String = PreferencesManager.DEFAULT_SERVER_URL,
    val accessToken: String = "",
    /** Set once after login / startup validation — shown as a one-shot snackbar. */
    val welcomeMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = AuthRepository(prefs)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Observe persisted login state
            combine(prefs.isLoggedIn, prefs.serverUrl) { loggedIn, url ->
                loggedIn to url
            }.collect { (loggedIn, url) ->
                _uiState.update { it.copy(isLoggedIn = loggedIn, serverUrl = url) }
            }
        }

        // On startup, re-validate the stored token and set a welcome message
        viewModelScope.launch {
            val token     = prefs.getAccessToken() ?: return@launch
            val url       = prefs.getServerUrl()
            val username  = prefs.getUsername()
            if (token.isBlank() || url.isBlank()) return@launch

            // Silently verify — no loading spinner so the UI stays snappy
            runCatching {
                val api = RetrofitClient.createApiService(url, token)
                api.getAuthUser()
            }.onSuccess { response ->
                if (response.isSuccessful) {
                    val user = response.body()?.data
                    val name = user?.username ?: username ?: "User"
                    prefs.saveUsername(name)
                    _uiState.update { it.copy(welcomeMessage = "Willkommen, @$name!") }
                } else {
                    // Token no longer valid — force logout
                    prefs.clearSession()
                    _uiState.update { it.copy(isLoggedIn = false) }
                }
            }
            // On network error: stay logged in — user might be offline
        }
    }

    fun updateServerUrl(url: String) = _uiState.update { it.copy(serverUrl = url) }
    fun updateAccessToken(token: String) = _uiState.update { it.copy(accessToken = token) }

    /** Save token directly and verify it against the API. */
    fun loginWithToken() {
        val serverUrl = _uiState.value.serverUrl.trim().trimEnd('/')
        val token     = _uiState.value.accessToken.trim()

        if (serverUrl.isBlank()) {
            _uiState.update { it.copy(error = "Bitte Server-URL eingeben.") }
            return
        }
        if (token.isBlank()) {
            _uiState.update { it.copy(error = "Bitte Access-Token eingeben.") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            prefs.saveServerConfig(serverUrl, "", "")
            prefs.saveTokens(token, null)

            val api = RetrofitClient.createApiService(serverUrl, token)
            runCatching { api.getAuthUser() }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        val user = response.body()?.data
                        val name = user?.username ?: "User"
                        prefs.saveUsername(name)
                        _uiState.update {
                            it.copy(
                                isLoading      = false,
                                isLoggedIn     = true,
                                welcomeMessage = "Willkommen, @$name!"
                            )
                        }
                    } else {
                        prefs.clearSession()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Token ungültig (${response.code()}). Bitte prüfe URL und Token."
                            )
                        }
                    }
                }
                .onFailure { e ->
                    prefs.clearSession()
                    _uiState.update {
                        it.copy(isLoading = false, error = "Verbindungsfehler: ${e.message}")
                    }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.logout()
            _uiState.update { it.copy(isLoading = false, isLoggedIn = false, accessToken = "", welcomeMessage = null) }
        }
    }

    /** Call after the welcome snackbar has been shown to avoid repeating it. */
    fun clearWelcomeMessage() = _uiState.update { it.copy(welcomeMessage = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
