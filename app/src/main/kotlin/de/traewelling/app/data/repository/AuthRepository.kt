package de.traewelling.app.data.repository

import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.util.PreferencesManager

class AuthRepository(private val prefs: PreferencesManager) {

    /**
     * Exchange the OAuth authorization code for an access token.
     * Uses PKCE if a codeVerifier is supplied; otherwise falls back to
     * client_secret-based auth.
     */
    suspend fun exchangeCodeForToken(
        serverUrl: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String?
    ): Result<Unit> = runCatching {
        val oauthService = RetrofitClient.createOAuthService(serverUrl)
        val response = oauthService.exchangeToken(
            clientId     = clientId,
            clientSecret = clientSecret?.ifBlank { null },
            redirectUri  = PreferencesManager.REDIRECT_URI,
            code         = code,
            codeVerifier = codeVerifier
        )
        if (response.isSuccessful) {
            val body = response.body() ?: error("Empty token response")
            prefs.saveTokens(body.accessToken ?: "", body.refreshToken)
        } else {
            error("Token exchange failed: ${response.code()} ${response.errorBody()?.string()}")
        }
    }

    /**
     * Use the refresh token to obtain a new access token silently.
     */
    suspend fun refreshAccessToken(): Result<Unit> = runCatching {
        val serverUrl     = prefs.getServerUrl()
        val clientId      = prefs.getClientId() ?: error("No client ID saved")
        val clientSecret  = prefs.getClientSecret()
        val refreshToken  = prefs.getRefreshToken() ?: error("No refresh token")

        val oauthService = RetrofitClient.createOAuthService(serverUrl)
        val response = oauthService.refreshToken(
            clientId     = clientId,
            clientSecret = clientSecret?.ifBlank { null },
            refreshToken = refreshToken
        )
        if (response.isSuccessful) {
            val body = response.body() ?: error("Empty refresh response")
            prefs.saveTokens(body.accessToken ?: "", body.refreshToken)
        } else {
            // Refresh failed — user needs to re-login
            prefs.clearSession()
            error("Token refresh failed: ${response.code()}")
        }
    }

    /**
     * Fetch the currently authenticated user and save their username.
     */
    suspend fun fetchAndSaveCurrentUser(): Result<String> = runCatching {
        val serverUrl   = prefs.getServerUrl()
        val accessToken = prefs.getAccessToken() ?: error("Not authenticated")
        val api         = RetrofitClient.createApiService(serverUrl, accessToken)

        val response = api.getAuthUser()
        if (response.isSuccessful) {
            val user = response.body()?.data ?: error("Empty user response")
            prefs.saveUsername(user.username)
            user.username
        } else {
            error("Failed to fetch user: ${response.code()}")
        }
    }

    suspend fun logout(): Result<Unit> = runCatching {
        val serverUrl   = prefs.getServerUrl()
        val accessToken = prefs.getAccessToken()
        if (accessToken != null) {
            runCatching {
                val api = RetrofitClient.createApiService(serverUrl, accessToken)
                api.logout()
            }
        }
        prefs.clearSession()
    }
}
