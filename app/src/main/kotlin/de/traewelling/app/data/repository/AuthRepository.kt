package de.traewelling.app.data.repository

import de.traewelling.app.data.api.ApiServerUrl
import de.traewelling.app.data.api.OAuthApiService
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException

class AuthRepository internal constructor(
    private val store: AuthSessionStore,
    private val apiFactory: (String, String) -> TraewellingApiService = RetrofitClient::createApiService,
    private val oauthFactory: (String) -> OAuthApiService = RetrofitClient::createOAuthService
) {
    constructor(prefs: PreferencesManager) : this(PreferencesAuthSessionStore(prefs))

    /** Persist a manual token only after a complete successful profile response. */
    suspend fun loginWithToken(serverUrl: String, token: String): Result<String> = apiResult {
        val server = ApiServerUrl.normalize(serverUrl)
        val accessToken = token.trim().takeIf { it.isNotEmpty() } ?: error("Bitte Access-Token eingeben.")
        val response = apiFactory(server, accessToken).getAuthUser()
        if (!response.isSuccessful) throw HttpException(response)
        val username = response.body()?.data?.username?.takeIf { it.isNotBlank() }
            ?: error("Leere oder unvollständige Nutzerantwort.")
        currentCoroutineContext().ensureActive()
        store.saveValidated(server, accessToken, username)
        username
    }

    /** Temporary failures preserve the session; old responses cannot change a new one. */
    suspend fun validateCurrentSession(): Result<String?> = apiResult {
        val session = store.session()
        val token = session.accessToken?.takeIf { it.isNotBlank() } ?: return@apiResult null
        val response = apiFactory(session.serverUrl, token).getAuthUser()
        if (response.code() == 401 || response.code() == 403) {
            store.clearIfMatches(session)
            return@apiResult null
        }
        if (!response.isSuccessful) throw HttpException(response)
        val username = response.body()?.data?.username?.takeIf { it.isNotBlank() }
            ?: error("Leere oder unvollständige Nutzerantwort.")
        if (store.saveUsernameIfMatches(session, username)) username else null
    }

    /** Optional OAuth path: reject missing tokens and superseded sessions. */
    suspend fun exchangeCodeForToken(
        serverUrl: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String?
    ): Result<Unit> = apiResult {
        val server = ApiServerUrl.normalize(serverUrl)
        val session = store.session()
        require(ApiServerUrl.normalize(session.serverUrl) == server) { "OAuth server changed" }
        val response = oauthFactory(server).exchangeToken(
            clientId = clientId,
            clientSecret = clientSecret?.ifBlank { null },
            redirectUri = PreferencesManager.REDIRECT_URI,
            code = code,
            codeVerifier = codeVerifier
        )
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: error("Empty token response")
        val token = body.accessToken?.takeIf { it.isNotBlank() } ?: error("Empty access token")
        if (!store.saveTokensIfMatches(session, token, body.refreshToken)) {
            throw CancellationException("Session changed during token exchange")
        }
    }

    suspend fun refreshAccessToken(): Result<Unit> = apiResult {
        val credentials = store.refreshCredentials()
        val session = credentials.session
        val clientId = credentials.clientId?.takeIf { it.isNotBlank() } ?: error("No client ID saved")
        val refreshToken = credentials.refreshToken?.takeIf { it.isNotBlank() } ?: error("No refresh token")
        val response = oauthFactory(session.serverUrl).refreshToken(
            clientId = clientId,
            clientSecret = credentials.clientSecret?.ifBlank { null },
            refreshToken = refreshToken
        )
        if (!response.isSuccessful) {
            // OAuth invalid_grant commonly returns 400; outages/rate limits are transient.
            if (response.code() in listOf(400, 401, 403)) store.clearIfMatches(session)
            throw HttpException(response)
        }
        val body = response.body() ?: error("Empty refresh response")
        val token = body.accessToken?.takeIf { it.isNotBlank() } ?: error("Empty access token")
        if (!store.saveTokensIfMatches(session, token, body.refreshToken)) {
            throw CancellationException("Session changed during token refresh")
        }
    }

    suspend fun fetchAndSaveCurrentUser(): Result<String> = apiResult {
        validateCurrentSession().getOrThrow() ?: error("Not authenticated")
    }

    /** Local logout precedes networking and cannot erase a later login. */
    suspend fun logout(): Result<Unit> = apiResult {
        val session = store.session()
        if (!store.clearIfMatches(session)) return@apiResult
        session.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
            apiResult { apiFactory(session.serverUrl, token).logout() }
        }
        Unit
    }
}
