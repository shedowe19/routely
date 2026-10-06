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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "traewelling_prefs")

data class TrackingConfiguration(
    val session: AuthSession,
    val activeStatusId: Int?,
    val gpsEnabled: Boolean,
    val recognitionEnabled: Boolean
)

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_SERVER_URL    = AuthSessionPreferences.server
        val KEY_ACCESS_TOKEN  = AuthSessionPreferences.accessToken
        val KEY_REFRESH_TOKEN = AuthSessionPreferences.refreshToken
        val KEY_CLIENT_ID     = AuthSessionPreferences.clientId
        val KEY_CLIENT_SECRET = AuthSessionPreferences.clientSecret
        val KEY_USERNAME      = AuthSessionPreferences.username
        val KEY_ACTIVE_STATUS_ID = AuthSessionPreferences.activeStatusId
        val KEY_TTS_ENABLED   = androidx.datastore.preferences.core.booleanPreferencesKey("tts_enabled")
        val KEY_TTS_ENGINE    = stringPreferencesKey("tts_engine")
        val KEY_TTS_LANGUAGE  = stringPreferencesKey("tts_language")
        val KEY_TTS_VOICE     = stringPreferencesKey("tts_voice")
        val KEY_APP_THEME     = stringPreferencesKey("app_theme")
        val KEY_GPS_TRACKING_ENABLED = booleanPreferencesKey("gps_tracking_enabled")
        val KEY_ANNOUNCEMENT_RADIUS = intPreferencesKey("announcement_radius_meters")
        val KEY_RIDE_RECOGNITION_ENABLED = AuthSessionPreferences.recognitionEnabled
        val KEY_TRIP_CHANGE_ALERTS_ENABLED = booleanPreferencesKey("trip_change_alerts_enabled")
        val KEY_TRIP_CHANGE_SPEECH_ENABLED = booleanPreferencesKey("trip_change_speech_enabled")
        val KEY_LIVE_PROGRESS_ENABLED = booleanPreferencesKey("live_progress_enabled")
        val KEY_LOCK_SCREEN_DETAILS_ENABLED = booleanPreferencesKey("lock_screen_details_enabled")
        private val KEY_TRACKING_STATE = AuthSessionPreferences.trackingState
        private val KEY_LOCATION_PERMISSION_REQUESTED = booleanPreferencesKey("location_permission_requested")

        val ANNOUNCEMENT_RADII = setOf(0, 300, 500, 1000, 2000)

        const val DEFAULT_SERVER_URL = "https://traewelling.de"
        const val REDIRECT_URI = "traewelling://oauth-callback"
        const val OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val authSession: Flow<AuthSession> = context.dataStore.data.map(AuthSessionPreferences::read).distinctUntilChanged()

    // Startup decisions must not combine credentials from one edit with an active ride from another.
    val trackingConfiguration: Flow<TrackingConfiguration> = context.dataStore.data.map { prefs ->
        TrackingConfiguration(AuthSessionPreferences.read(prefs), prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull(),
            prefs[KEY_GPS_TRACKING_ENABLED] ?: true, prefs[KEY_RIDE_RECOGNITION_ENABLED] ?: false)
    }.distinctUntilChanged()

    val accessToken: Flow<String?> = authSession.map { it.accessToken }

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

    val isLoggedIn: Flow<Boolean> = authSession.map { it.accessToken != null }

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

    val rideRecognitionEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RIDE_RECOGNITION_ENABLED] ?: false
    }
    val tripChangeAlertsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TRIP_CHANGE_ALERTS_ENABLED] ?: true
    }
    val tripChangeSpeechEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TRIP_CHANGE_SPEECH_ENABLED] ?: true
    }
    val liveProgressEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_LIVE_PROGRESS_ENABLED] ?: true
    }
    val lockScreenDetailsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_LOCK_SCREEN_DETAILS_ENABLED] ?: true
    }

    suspend fun setRideRecognitionEnabled(enabled: Boolean, expectedSession: AuthSession? = null): Boolean {
        var changed = false
        context.dataStore.edit { changed = AuthSessionPreferences.setRecognitionEnabled(it, enabled, expectedSession) }
        return changed
    }
    suspend fun setTripChangeAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TRIP_CHANGE_ALERTS_ENABLED] = enabled }
        if (!enabled) de.traewelling.app.service.TripTrackingService.clearChangeNotifications(context)
    }
    suspend fun setTripChangeSpeechEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TRIP_CHANGE_SPEECH_ENABLED] = enabled }
    }
    suspend fun setLiveProgressEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LIVE_PROGRESS_ENABLED] = enabled }
    }
    suspend fun setLockScreenDetailsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LOCK_SCREEN_DETAILS_ENABLED] = enabled }
        if (!enabled) de.traewelling.app.service.TripTrackingService.clearChangeNotifications(context)
    }

    suspend fun getRideRecognitionEnabled(): Boolean = rideRecognitionEnabled.first()
    suspend fun getTripChangeAlertsEnabled(): Boolean = tripChangeAlertsEnabled.first()
    suspend fun getTripChangeSpeechEnabled(): Boolean = tripChangeSpeechEnabled.first()
    suspend fun getLiveProgressEnabled(): Boolean = liveProgressEnabled.first()
    suspend fun getLockScreenDetailsEnabled(): Boolean = lockScreenDetailsEnabled.first()

    suspend fun saveServerConfig(serverUrl: String, clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            if (AuthSessionPreferences.read(prefs).serverUrl != serverUrl.trimEnd('/')) {
                AuthSessionPreferences.clear(prefs)
            }
            prefs[KEY_SERVER_URL]    = serverUrl.trimEnd('/')
            prefs[KEY_CLIENT_ID]     = clientId
            prefs[KEY_CLIENT_SECRET] = clientSecret
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?) {
        context.dataStore.edit { AuthSessionPreferences.saveTokens(it, accessToken, refreshToken) }
    }

    suspend fun getAuthSession(): AuthSession = AuthSessionPreferences.read(context.dataStore.data.first())

    suspend fun saveValidatedSession(serverUrl: String, token: String, username: String) {
        context.dataStore.edit { AuthSessionPreferences.saveValidated(it, serverUrl, token, username) }
    }

    suspend fun clearSessionIfMatches(expected: AuthSession): Boolean {
        var changed = false
        context.dataStore.edit { changed = AuthSessionPreferences.clearIfMatches(it, expected) }
        return changed
    }

    suspend fun saveUsernameIfMatches(expected: AuthSession, username: String): Boolean {
        var changed = false
        context.dataStore.edit { changed = AuthSessionPreferences.saveUsernameIfMatches(it, expected, username) }
        return changed
    }

    suspend fun saveTokensIfMatches(expected: AuthSession, token: String, refresh: String?): Boolean {
        var changed = false
        context.dataStore.edit { changed = AuthSessionPreferences.saveTokensIfMatches(it, expected, token, refresh) }
        return changed
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = username
        }
    }

    suspend fun saveActiveStatusId(statusId: Int?) {
        context.dataStore.edit { AuthSessionPreferences.saveActiveStatusId(it, statusId) }
    }

    suspend fun saveActiveStatusIdIfMatches(expected: AuthSession, statusId: Int?): Boolean {
        var changed = false
        context.dataStore.edit { changed = AuthSessionPreferences.saveActiveStatusIdIfMatches(it, expected, statusId) }
        return changed
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
    suspend fun saveTrackingState(statusId: Int, stateJson: String, expectedSession: AuthSession? = null) {
        context.dataStore.edit { AuthSessionPreferences.saveTrackingState(it, statusId, stateJson, expectedSession) }
    }

    suspend fun clearActiveTracking(statusId: Int, expectedSession: AuthSession? = null): Boolean {
        var cleared = false
        context.dataStore.edit { cleared = AuthSessionPreferences.clearActiveTracking(it, statusId, expectedSession) }
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
        context.dataStore.edit { AuthSessionPreferences.clear(it) }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear(); AuthSessionPreferences.renew(it) }
    }

    // Read current values once (suspend, for non-flow contexts)
    suspend fun getAccessToken(): String? =
        getAuthSession().accessToken

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
