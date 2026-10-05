package de.traewelling.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val KEY_TTS_ENABLED   = androidx.datastore.preferences.core.booleanPreferencesKey("tts_enabled")
        val KEY_TTS_ENGINE    = stringPreferencesKey("tts_engine")
        val KEY_TTS_LANGUAGE  = stringPreferencesKey("tts_language")
        val KEY_TTS_VOICE     = stringPreferencesKey("tts_voice")
        val KEY_APP_THEME     = stringPreferencesKey("app_theme")
        val KEY_GPS_TRACKING_ENABLED = booleanPreferencesKey("gps_tracking_enabled")
        val KEY_ANNOUNCEMENT_RADIUS = intPreferencesKey("announcement_radius_meters")
        private val KEY_TRACKING_STATE = stringPreferencesKey("trip_tracking_state")
        private val KEY_LOCATION_PERMISSION_REQUESTED = booleanPreferencesKey("location_permission_requested")

        val ANNOUNCEMENT_RADII = setOf(0, 300, 500, 1000, 2000)

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

    val isTtsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TTS_ENABLED] ?: false
    }

    val ttsEngine: Flow<String?> = context.dataStore.data.map { prefs -> prefs[KEY_TTS_ENGINE] }
    val ttsLanguage: Flow<String?> = context.dataStore.data.map { prefs -> prefs[KEY_TTS_LANGUAGE] }
    val ttsVoice: Flow<String?> = context.dataStore.data.map { prefs -> prefs[KEY_TTS_VOICE] }

    val appTheme: Flow<String> = context.dataStore.data.map { prefs -> prefs[KEY_APP_THEME] ?: "LIGHT" }

    val gpsTrackingEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_GPS_TRACKING_ENABLED] ?: true
    }

    val announcementRadiusMeters: Flow<Int> = context.dataStore.data.map {
        validRadius(it[KEY_ANNOUNCEMENT_RADIUS] ?: 0)
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
            if (prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull() != statusId) {
                prefs.remove(KEY_TRACKING_STATE)
            }
            if (statusId == null) {
                prefs.remove(KEY_ACTIVE_STATUS_ID)
                prefs.remove(KEY_TRACKING_STATE)
            } else {
                prefs[KEY_ACTIVE_STATUS_ID] = statusId.toString()
            }
        }
    }

    suspend fun setGpsTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GPS_TRACKING_ENABLED] = enabled }
    }

    suspend fun setAnnouncementRadiusMeters(radius: Int) {
        context.dataStore.edit { it[KEY_ANNOUNCEMENT_RADIUS] = validRadius(radius) }
    }

    suspend fun getGpsTrackingEnabled(): Boolean = gpsTrackingEnabled.first()

    suspend fun getAnnouncementRadiusMeters(): Int = announcementRadiusMeters.first()

    suspend fun getTrackingState(): String? = context.dataStore.data.first()[KEY_TRACKING_STATE]

    suspend fun hasRequestedLocationPermission(): Boolean =
        context.dataStore.data.first()[KEY_LOCATION_PERMISSION_REQUESTED] ?: false

    suspend fun markLocationPermissionRequested() {
        context.dataStore.edit { it[KEY_LOCATION_PERMISSION_REQUESTED] = true }
    }

    // A superseded service must never overwrite the progress of the current trip.
    suspend fun saveTrackingState(statusId: Int, stateJson: String) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull() == statusId) {
                prefs[KEY_TRACKING_STATE] = stateJson
            }
        }
    }

    suspend fun clearActiveTracking(statusId: Int): Boolean {
        var cleared = false
        context.dataStore.edit { prefs ->
            if (prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull() == statusId) {
                prefs.remove(KEY_ACTIVE_STATUS_ID)
                prefs.remove(KEY_TRACKING_STATE)
                cleared = true
            }
        }
        return cleared
    }

    private fun validRadius(radius: Int): Int = radius.takeIf { it in ANNOUNCEMENT_RADII } ?: 0

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TTS_ENABLED] = enabled
        }
    }

    suspend fun saveTtsSettings(engine: String?, language: String?, voice: String?) {
        context.dataStore.edit { prefs ->
            if (engine != null) prefs[KEY_TTS_ENGINE] = engine else prefs.remove(KEY_TTS_ENGINE)
            if (language != null) prefs[KEY_TTS_LANGUAGE] = language else prefs.remove(KEY_TTS_LANGUAGE)
            if (voice != null) prefs[KEY_TTS_VOICE] = voice else prefs.remove(KEY_TTS_VOICE)
        }
    }

    suspend fun setAppTheme(theme: String) {
        val validThemes = listOf("LIGHT", "DARK", "AMOLED")
        val safeTheme = if (theme in validThemes) theme else "LIGHT"
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_THEME] = safeTheme
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_ACTIVE_STATUS_ID)
            prefs.remove(KEY_TRACKING_STATE)
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

    suspend fun getTtsEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_TTS_ENABLED] ?: false }.first()

    suspend fun getTtsEngine(): String? = context.dataStore.data.map { it[KEY_TTS_ENGINE] }.first()
    suspend fun getTtsLanguage(): String? = context.dataStore.data.map { it[KEY_TTS_LANGUAGE] }.first()
    suspend fun getTtsVoice(): String? = context.dataStore.data.map { it[KEY_TTS_VOICE] }.first()

    suspend fun getAppTheme(): String = context.dataStore.data.map { it[KEY_APP_THEME] ?: "LIGHT" }.first()
}
