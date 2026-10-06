package de.traewelling.app.util

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID

/** One atomic credential snapshot. Revision prevents a delayed A -> logout -> A response from winning. */
data class AuthSession(val serverUrl: String, val accessToken: String?, val revision: String) {
    override fun toString(): String = "AuthSession(authenticated=${accessToken != null}, revision=$revision)"
}

internal object AuthSessionPreferences {
    val server = stringPreferencesKey("server_url")
    val accessToken = stringPreferencesKey("access_token")
    val refreshToken = stringPreferencesKey("refresh_token")
    val clientId = stringPreferencesKey("client_id")
    val clientSecret = stringPreferencesKey("client_secret")
    val username = stringPreferencesKey("username")
    val activeStatusId = stringPreferencesKey("active_status_id")
    val trackingState = stringPreferencesKey("trip_tracking_state")
    val recognitionEnabled = booleanPreferencesKey("ride_recognition_enabled")
    private val revision = stringPreferencesKey("auth_session_revision")

    fun read(prefs: Preferences) = AuthSession(
        prefs[server] ?: "https://traewelling.de",
        prefs[accessToken]?.takeUnless(String::isBlank),
        prefs[revision] ?: "legacy"
    )

    fun clear(prefs: MutablePreferences) {
        prefs.remove(accessToken)
        prefs.remove(refreshToken)
        prefs.remove(clientId)
        prefs.remove(clientSecret)
        prefs.remove(username)
        prefs.remove(activeStatusId)
        prefs.remove(trackingState)
        prefs[recognitionEnabled] = false
        renew(prefs)
    }

    fun clearIfMatches(prefs: MutablePreferences, expected: AuthSession): Boolean {
        if (read(prefs) != expected) return false
        clear(prefs)
        return true
    }

    fun saveValidated(prefs: MutablePreferences, serverUrl: String, token: String, user: String) {
        require(token.isNotBlank() && user.isNotBlank()) { "Validierte Sitzung benötigt Token und Nutzername" }
        clear(prefs)
        prefs[server] = serverUrl.trimEnd('/')
        prefs[accessToken] = token
        prefs[username] = user
    }

    fun saveTokens(prefs: MutablePreferences, token: String, refresh: String?) {
        require(token.isNotBlank()) { "Access-Token darf nicht leer sein" }
        prefs[accessToken] = token
        if (refresh == null) prefs.remove(refreshToken) else prefs[refreshToken] = refresh
        renew(prefs)
    }

    fun saveTokensIfMatches(prefs: MutablePreferences, expected: AuthSession, token: String, refresh: String?): Boolean {
        if (read(prefs) != expected) return false
        saveTokens(prefs, token, refresh)
        return true
    }

    fun saveUsernameIfMatches(prefs: MutablePreferences, expected: AuthSession, user: String): Boolean {
        if (read(prefs) != expected) return false
        prefs[username] = user
        return true
    }

    fun saveActiveStatusId(prefs: MutablePreferences, statusId: Int?) {
        if (prefs[activeStatusId]?.toIntOrNull() != statusId || statusId == null) prefs.remove(trackingState)
        if (statusId == null) prefs.remove(activeStatusId) else prefs[activeStatusId] = statusId.toString()
    }

    fun saveActiveStatusIdIfMatches(prefs: MutablePreferences, expected: AuthSession, statusId: Int?): Boolean {
        if (read(prefs) != expected || expected.accessToken == null) return false
        saveActiveStatusId(prefs, statusId)
        return true
    }

    fun saveTrackingState(prefs: MutablePreferences, statusId: Int, json: String, expected: AuthSession?): Boolean {
        if (prefs[activeStatusId]?.toIntOrNull() != statusId || (expected != null && read(prefs) != expected)) return false
        prefs[trackingState] = json
        return true
    }

    fun clearActiveTracking(prefs: MutablePreferences, statusId: Int, expected: AuthSession?): Boolean {
        if (prefs[activeStatusId]?.toIntOrNull() != statusId || (expected != null && read(prefs) != expected)) return false
        prefs.remove(activeStatusId)
        prefs.remove(trackingState)
        return true
    }

    fun setRecognitionEnabled(prefs: MutablePreferences, enabled: Boolean, expected: AuthSession?): Boolean {
        if (expected != null && read(prefs) != expected) return false
        prefs[recognitionEnabled] = enabled
        return true
    }

    fun renew(prefs: MutablePreferences) { prefs[revision] = UUID.randomUUID().toString() }
}
