package de.traewelling.app.data.repository

import de.traewelling.app.util.AuthSession
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException

internal data class RefreshCredentials(
    val session: AuthSession,
    val clientId: String?,
    val clientSecret: String?,
    val refreshToken: String?
) {
    override fun toString(): String = "RefreshCredentials(session=$session, configured=${clientId != null})"
}

/** Persistence boundary for delayed-response regression tests without Android. */
internal interface AuthSessionStore {
    suspend fun session(): AuthSession
    suspend fun saveValidated(serverUrl: String, accessToken: String, username: String)
    suspend fun clearIfMatches(expected: AuthSession): Boolean
    suspend fun saveUsernameIfMatches(expected: AuthSession, username: String): Boolean
    suspend fun saveTokensIfMatches(expected: AuthSession, token: String, refresh: String?): Boolean
    suspend fun refreshCredentials(): RefreshCredentials
}

internal class PreferencesAuthSessionStore(private val prefs: PreferencesManager) : AuthSessionStore {
    override suspend fun session() = prefs.getAuthSession()
    override suspend fun saveValidated(serverUrl: String, accessToken: String, username: String) =
        prefs.saveValidatedSession(serverUrl, accessToken, username)
    override suspend fun clearIfMatches(expected: AuthSession) = prefs.clearSessionIfMatches(expected)
    override suspend fun saveUsernameIfMatches(expected: AuthSession, username: String) =
        prefs.saveUsernameIfMatches(expected, username)
    override suspend fun saveTokensIfMatches(expected: AuthSession, token: String, refresh: String?) =
        prefs.saveTokensIfMatches(expected, token, refresh)

    override suspend fun refreshCredentials(): RefreshCredentials {
        val before = session()
        val credentials = RefreshCredentials(before, prefs.getClientId(), prefs.getClientSecret(), prefs.getRefreshToken())
        if (session() != before) throw CancellationException("Session changed during refresh setup")
        return credentials
    }
}
