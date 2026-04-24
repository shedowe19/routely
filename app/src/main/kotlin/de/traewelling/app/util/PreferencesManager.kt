package de.traewelling.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "traewelling_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_SERVER_URL    = stringPreferencesKey("server_url")
        val KEY_ACCESS_TOKEN  = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_CLIENT_ID     = stringPreferencesKey("client_id")
        val KEY_CLIENT_SECRET = stringPreferencesKey("client_secret")
        val KEY_USERNAME      = stringPreferencesKey("username")
        val KEY_ACTIVE_STATUS_ID = stringPreferencesKey("active_status_id")

        const val DEFAULT_SERVER_URL = "https://traewelling.de"
        const val REDIRECT_URI = "traewelling://oauth-callback"
        const val OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCESS_TOKEN]
    }

    val refreshToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_REFRESH_TOKEN]
    }

    val clientId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLIENT_ID]
    }

    val clientSecret: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLIENT_SECRET]
    }

    val username: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USERNAME]
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCESS_TOKEN] != null
    }

    val activeStatusId: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull()
    }

    suspend fun saveServerConfig(serverUrl: String, clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL]    = serverUrl.trimEnd('/')
            prefs[KEY_CLIENT_ID]     = clientId
            prefs[KEY_CLIENT_SECRET] = clientSecret
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = accessToken
            if (refreshToken != null) {
                prefs[KEY_REFRESH_TOKEN] = refreshToken
            }
        }
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = username
        }
    }

    suspend fun saveActiveStatusId(statusId: Int?) {
        context.dataStore.edit { prefs ->
            if (statusId == null) {
                prefs.remove(KEY_ACTIVE_STATUS_ID)
            } else {
                prefs[KEY_ACTIVE_STATUS_ID] = statusId.toString()
            }
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_USERNAME)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    // Read current values once (suspend, for non-flow contexts)
    suspend fun getAccessToken(): String? =
        context.dataStore.data.map { it[KEY_ACCESS_TOKEN] }.first()

    suspend fun getServerUrl(): String =
        context.dataStore.data.map { it[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL }.first()

    suspend fun getClientId(): String? =
        context.dataStore.data.map { it[KEY_CLIENT_ID] }.first()

    suspend fun getClientSecret(): String? =
        context.dataStore.data.map { it[KEY_CLIENT_SECRET] }.first()

    suspend fun getRefreshToken(): String? =
        context.dataStore.data.map { it[KEY_REFRESH_TOKEN] }.first()

    suspend fun getUsername(): String? =
        context.dataStore.data.map { it[KEY_USERNAME] }.first()
}
