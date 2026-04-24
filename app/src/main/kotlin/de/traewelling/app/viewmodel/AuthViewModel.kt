package de.traewelling.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.data.repository.AuthRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val serverUrl: String = PreferencesManager.DEFAULT_SERVER_URL,
    val clientId: String = "",
    val accessToken: String = "",
    /** Set once after login / startup validation — shown as a one-shot snackbar. */
    val welcomeMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = AuthRepository(prefs)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var currentCodeVerifier: String? = null

    init {
        viewModelScope.launch {
            // Observe persisted login state
            combine(prefs.isLoggedIn, prefs.serverUrl, prefs.clientId) { loggedIn, url, clientId ->
                Triple(loggedIn, url, clientId ?: "")
            }.collect { (loggedIn, url, clientId) ->
                _uiState.update { it.copy(isLoggedIn = loggedIn, serverUrl = url, clientId = clientId) }
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
    fun updateClientId(id: String)   = _uiState.update { it.copy(clientId = id) }
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

    /** 
     * Construct the OAuth2 authorization URL and trigger a browser intent.
     * Uses PKCE for enhanced security.
     */
    fun startOAuthLogin(onLaunchIntent: (Intent) -> Unit) {
        val serverUrl = _uiState.value.serverUrl.trim().trimEnd('/')
        val clientId  = _uiState.value.clientId.trim()

        if (serverUrl.isBlank() || clientId.isBlank()) {
            _uiState.update { it.copy(error = "Bitte Server-URL und Client-ID eingeben.") }
            return
        }

        val verifier  = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        currentCodeVerifier = verifier

        val authUrl = Uri.parse(serverUrl).buildUpon()
            .appendEncodedPath("oauth/authorize")
            .appendQueryParameter("client_id",             clientId)
            .appendQueryParameter("redirect_uri",          PreferencesManager.REDIRECT_URI)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("scope",                 PreferencesManager.OAUTH_SCOPES)
            .appendQueryParameter("code_challenge",        challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        onLaunchIntent(Intent(Intent.ACTION_VIEW, authUrl))
    }

    /** Handle the callback from the browser redirect. */
    fun handleOAuthCallback(uri: Uri) {
        val code      = uri.getQueryParameter("code") ?: return
        val serverUrl = _uiState.value.serverUrl.trim().trimEnd('/')
        val clientId  = _uiState.value.clientId.trim()
        val verifier  = currentCodeVerifier

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = repo.exchangeCodeForToken(
                serverUrl    = serverUrl,
                clientId     = clientId,
                clientSecret = null, // PKCE doesn't need a secret
                code         = code,
                codeVerifier = verifier
            )

            result.onSuccess {
                // Save config (URL and Client ID) as well
                prefs.saveServerConfig(serverUrl, clientId, "")
                
                // Fetch user info to complete login
                repo.fetchAndSaveCurrentUser()
                    .onSuccess { name ->
                        _uiState.update {
                            it.copy(
                                isLoading      = false,
                                isLoggedIn     = true,
                                welcomeMessage = "Willkommen, @$name!"
                            )
                        }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(isLoading = false, error = "Fehler beim Profil-Abruf: ${e.message}") }
                    }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = "Login fehlgeschlagen: ${e.message}") }
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val digest = messageDigest.digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            de.traewelling.app.util.WorkManagerHelper.stopTripMonitoring(getApplication())
            repo.logout()
            _uiState.update { it.copy(isLoading = false, isLoggedIn = false, accessToken = "", welcomeMessage = null) }
        }
    }

    /** Call after the welcome snackbar has been shown to avoid repeating it. */
    fun clearWelcomeMessage() = _uiState.update { it.copy(welcomeMessage = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
