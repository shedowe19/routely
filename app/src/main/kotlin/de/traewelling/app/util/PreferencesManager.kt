package de.traewelling.app.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class TrackingConfiguration(
    val session: AuthSession,
    val activeStatusId: Int?,
    val gpsEnabled: Boolean,
    val recognitionEnabled: Boolean
)

class PreferencesManager private constructor(
    private val storage: PreferenceStorage,
    private val context: Context?
) {
    constructor(context: Context) : this(PreferenceStorageRegistry.get(context), context.applicationContext)
    internal constructor(storage: PreferenceStorage) : this(storage, null)

    val storageState: StateFlow<PreferenceStorageState> = storage.state
    suspend fun retryStorage(): Result<Unit> = storage.retryStorage()
    suspend fun resetCorruptedStorage(expectedFailureId: Long): Result<Unit> = storage.resetCorruptedStorage(expectedFailureId)


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
        val KEY_PUBLIC_RAIL_REALTIME_ENABLED = booleanPreferencesKey("public_rail_realtime_enabled")
        private val KEY_TRACKING_STATE = AuthSessionPreferences.trackingState
        private val KEY_LOCATION_PERMISSION_REQUESTED = booleanPreferencesKey("location_permission_requested")

        val ANNOUNCEMENT_RADII = setOf(0, 300, 500, 1000, 2000)

        const val DEFAULT_SERVER_URL = "https://traewelling.de"
        const val REDIRECT_URI = "traewelling://oauth-callback"
        const val OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
    }

    val serverUrl: Flow<String> = storage.observePreferences().map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val authSession: Flow<AuthSession> = storage.observePreferences().map(AuthSessionPreferences::read).distinctUntilChanged()

    // Startup decisions must not combine credentials from one edit with an active ride from another.
    val trackingConfiguration: Flow<TrackingConfiguration> =
        storage.observePreferences().map(::readTrackingConfiguration).distinctUntilChanged()

    private fun readTrackingConfiguration(prefs: Preferences) = TrackingConfiguration(
        AuthSessionPreferences.read(prefs), prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull(),
        prefs[KEY_GPS_TRACKING_ENABLED] ?: true, prefs[KEY_RIDE_RECOGNITION_ENABLED] ?: false
    )

    suspend fun getTrackingConfiguration(): TrackingConfiguration = readTrackingConfiguration(storage.readPreferences())
    suspend fun getActiveStatusId(): Int? = storage.readPreferences()[KEY_ACTIVE_STATUS_ID]?.toIntOrNull()

    val accessToken: Flow<String?> = authSession.map { it.accessToken }

    val refreshToken: Flow<String?> = storage.observePreferences().map { prefs ->
        prefs[KEY_REFRESH_TOKEN]
    }

    val clientId: Flow<String?> = storage.observePreferences().map { prefs ->
        prefs[KEY_CLIENT_ID]
    }

    val clientSecret: Flow<String?> = storage.observePreferences().map { prefs ->
        prefs[KEY_CLIENT_SECRET]
    }

    val username: Flow<String?> = storage.observePreferences().map { prefs ->
        prefs[KEY_USERNAME]
    }

    val isLoggedIn: Flow<Boolean> = authSession.map { it.accessToken != null }

    val activeStatusId: Flow<Int?> = storage.observePreferences().map { prefs ->
        prefs[KEY_ACTIVE_STATUS_ID]?.toIntOrNull()
    }

    val isTtsEnabled: Flow<Boolean> = storage.observePreferences().map { prefs ->
        prefs[KEY_TTS_ENABLED] ?: false
    }

    val ttsEngine: Flow<String?> = storage.observePreferences().map { prefs -> prefs[KEY_TTS_ENGINE] }
    val ttsLanguage: Flow<String?> = storage.observePreferences().map { prefs -> prefs[KEY_TTS_LANGUAGE] }
    val ttsVoice: Flow<String?> = storage.observePreferences().map { prefs -> prefs[KEY_TTS_VOICE] }

    val appTheme: Flow<String> = storage.observePreferences().map { prefs -> prefs[KEY_APP_THEME] ?: "LIGHT" }

    val gpsTrackingEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_GPS_TRACKING_ENABLED] ?: true
    }

    val announcementRadiusMeters: Flow<Int> = storage.observePreferences().map {
        validRadius(it[KEY_ANNOUNCEMENT_RADIUS] ?: 0)
    }

    val rideRecognitionEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_RIDE_RECOGNITION_ENABLED] ?: false
    }
    val tripChangeAlertsEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_TRIP_CHANGE_ALERTS_ENABLED] ?: true
    }
    val tripChangeSpeechEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_TRIP_CHANGE_SPEECH_ENABLED] ?: true
    }
    val liveProgressEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_LIVE_PROGRESS_ENABLED] ?: true
    }
    val lockScreenDetailsEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_LOCK_SCREEN_DETAILS_ENABLED] ?: true
    }
    val publicRailRealtimeEnabled: Flow<Boolean> = storage.observePreferences().map {
        it[KEY_PUBLIC_RAIL_REALTIME_ENABLED] ?: true
    }.distinctUntilChanged()

    suspend fun setRideRecognitionEnabled(enabled: Boolean, expectedSession: AuthSession? = null): Boolean {
        var changed = false
        storage.editPreferences { changed = AuthSessionPreferences.setRecognitionEnabled(it, enabled, expectedSession) }
        return changed
    }
    suspend fun setTripChangeAlertsEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_TRIP_CHANGE_ALERTS_ENABLED] = enabled }
        if (!enabled) context?.let { de.traewelling.app.service.TripTrackingService.clearChangeNotifications(it) }
    }
    suspend fun setTripChangeSpeechEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_TRIP_CHANGE_SPEECH_ENABLED] = enabled }
    }
    suspend fun setLiveProgressEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_LIVE_PROGRESS_ENABLED] = enabled }
    }
    suspend fun setLockScreenDetailsEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_LOCK_SCREEN_DETAILS_ENABLED] = enabled }
        if (!enabled) context?.let { de.traewelling.app.service.TripTrackingService.clearChangeNotifications(it) }
    }
    suspend fun setPublicRailRealtimeEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_PUBLIC_RAIL_REALTIME_ENABLED] = enabled }
    }

    suspend fun getRideRecognitionEnabled(): Boolean = storage.readPreferences()[KEY_RIDE_RECOGNITION_ENABLED] ?: false
    suspend fun getTripChangeAlertsEnabled(): Boolean = storage.readPreferences()[KEY_TRIP_CHANGE_ALERTS_ENABLED] ?: true
    suspend fun getTripChangeSpeechEnabled(): Boolean = storage.readPreferences()[KEY_TRIP_CHANGE_SPEECH_ENABLED] ?: true
    suspend fun getLiveProgressEnabled(): Boolean = storage.readPreferences()[KEY_LIVE_PROGRESS_ENABLED] ?: true
    suspend fun getLockScreenDetailsEnabled(): Boolean = storage.readPreferences()[KEY_LOCK_SCREEN_DETAILS_ENABLED] ?: true
    suspend fun getPublicRailRealtimeEnabled(): Boolean = storage.readPreferences()[KEY_PUBLIC_RAIL_REALTIME_ENABLED] ?: true

    suspend fun saveServerConfig(serverUrl: String, clientId: String, clientSecret: String) {
        storage.editPreferences { prefs ->
            if (AuthSessionPreferences.read(prefs).serverUrl != serverUrl.trimEnd('/')) {
                AuthSessionPreferences.clear(prefs)
            }
            prefs[KEY_SERVER_URL]    = serverUrl.trimEnd('/')
            prefs[KEY_CLIENT_ID]     = clientId
            prefs[KEY_CLIENT_SECRET] = clientSecret
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?) {
        storage.editPreferences { AuthSessionPreferences.saveTokens(it, accessToken, refreshToken) }
    }

    suspend fun getAuthSession(): AuthSession = AuthSessionPreferences.read(storage.readPreferences())

    suspend fun saveValidatedSession(serverUrl: String, token: String, username: String) {
        storage.editPreferences { AuthSessionPreferences.saveValidated(it, serverUrl, token, username) }
    }

    suspend fun clearSessionIfMatches(expected: AuthSession): Boolean {
        var changed = false
        storage.editPreferences { changed = AuthSessionPreferences.clearIfMatches(it, expected) }
        return changed
    }

    suspend fun saveUsernameIfMatches(expected: AuthSession, username: String): Boolean {
        var changed = false
        storage.editPreferences { changed = AuthSessionPreferences.saveUsernameIfMatches(it, expected, username) }
        return changed
    }

    suspend fun saveTokensIfMatches(expected: AuthSession, token: String, refresh: String?): Boolean {
        var changed = false
        storage.editPreferences { changed = AuthSessionPreferences.saveTokensIfMatches(it, expected, token, refresh) }
        return changed
    }

    suspend fun saveUsername(username: String) {
        storage.editPreferences { prefs ->
            prefs[KEY_USERNAME] = username
        }
    }

    suspend fun saveActiveStatusId(statusId: Int?) {
        storage.editPreferences { AuthSessionPreferences.saveActiveStatusId(it, statusId) }
    }

    suspend fun saveActiveStatusIdIfMatches(expected: AuthSession, statusId: Int?): Boolean {
        var changed = false
        storage.editPreferences { changed = AuthSessionPreferences.saveActiveStatusIdIfMatches(it, expected, statusId) }
        return changed
    }

    suspend fun saveActiveStatusIdIfIdleOrSame(expectedSession: AuthSession, statusId: Int): Boolean {
        var changed = false
        storage.editPreferences {
            changed = AuthSessionPreferences.saveActiveStatusIdIfIdleOrSame(it, expectedSession, statusId)
        }
        return changed
    }

    suspend fun setGpsTrackingEnabled(enabled: Boolean) {
        storage.editPreferences { it[KEY_GPS_TRACKING_ENABLED] = enabled }
    }

    suspend fun setAnnouncementRadiusMeters(radius: Int) {
        storage.editPreferences { it[KEY_ANNOUNCEMENT_RADIUS] = validRadius(radius) }
    }

    suspend fun getGpsTrackingEnabled(): Boolean = storage.readPreferences()[KEY_GPS_TRACKING_ENABLED] ?: true

    suspend fun getAnnouncementRadiusMeters(): Int = validRadius(storage.readPreferences()[KEY_ANNOUNCEMENT_RADIUS] ?: 0)

    suspend fun getTrackingState(): String? = storage.readPreferences()[KEY_TRACKING_STATE]

    suspend fun hasRequestedLocationPermission(): Boolean =
        storage.readPreferences()[KEY_LOCATION_PERMISSION_REQUESTED] ?: false

    suspend fun markLocationPermissionRequested() {
        storage.editPreferences { it[KEY_LOCATION_PERMISSION_REQUESTED] = true }
    }

    // A superseded service must never overwrite the progress of the current trip.
    suspend fun saveTrackingState(statusId: Int, stateJson: String, expectedSession: AuthSession? = null) {
        storage.editPreferences { AuthSessionPreferences.saveTrackingState(it, statusId, stateJson, expectedSession) }
    }

    suspend fun invalidateTrackingState(statusId: Int, expectedSession: AuthSession) {
        storage.editPreferences { AuthSessionPreferences.invalidateTrackingState(it, statusId, expectedSession) }
    }

    suspend fun clearActiveTracking(statusId: Int, expectedSession: AuthSession? = null): Boolean {
        var cleared = false
        storage.editPreferences { cleared = AuthSessionPreferences.clearActiveTracking(it, statusId, expectedSession) }
        return cleared
    }

    private fun validRadius(radius: Int): Int = radius.takeIf { it in ANNOUNCEMENT_RADII } ?: 0

    suspend fun setTtsEnabled(enabled: Boolean) {
        storage.editPreferences { prefs ->
            prefs[KEY_TTS_ENABLED] = enabled
        }
    }

    suspend fun saveTtsSettings(engine: String?, language: String?, voice: String?) {
        storage.editPreferences { prefs ->
            if (engine != null) prefs[KEY_TTS_ENGINE] = engine else prefs.remove(KEY_TTS_ENGINE)
            if (language != null) prefs[KEY_TTS_LANGUAGE] = language else prefs.remove(KEY_TTS_LANGUAGE)
            if (voice != null) prefs[KEY_TTS_VOICE] = voice else prefs.remove(KEY_TTS_VOICE)
        }
    }

    suspend fun setAppTheme(theme: String) {
        val validThemes = listOf("LIGHT", "DARK", "AMOLED")
        val safeTheme = if (theme in validThemes) theme else "LIGHT"
        storage.editPreferences { prefs ->
            prefs[KEY_APP_THEME] = safeTheme
        }
    }

    suspend fun clearSession() {
        storage.editPreferences { AuthSessionPreferences.clear(it) }
    }

    suspend fun clearAll() {
        storage.editPreferences { it.clear(); AuthSessionPreferences.renew(it) }
    }

    // Read current values once (suspend, for non-flow contexts)
    suspend fun getAccessToken(): String? =
        getAuthSession().accessToken

    suspend fun getServerUrl(): String =
        storage.readPreferences()[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL

    suspend fun getClientId(): String? =
        storage.readPreferences()[KEY_CLIENT_ID]

    suspend fun getClientSecret(): String? =
        storage.readPreferences()[KEY_CLIENT_SECRET]

    suspend fun getRefreshToken(): String? =
        storage.readPreferences()[KEY_REFRESH_TOKEN]

    suspend fun getUsername(): String? =
        storage.readPreferences()[KEY_USERNAME]

    suspend fun getTtsEnabled(): Boolean =
        storage.readPreferences()[KEY_TTS_ENABLED] ?: false

    suspend fun getTtsEngine(): String? = storage.readPreferences()[KEY_TTS_ENGINE]
    suspend fun getTtsLanguage(): String? = storage.readPreferences()[KEY_TTS_LANGUAGE]
    suspend fun getTtsVoice(): String? = storage.readPreferences()[KEY_TTS_VOICE]

    suspend fun getAppTheme(): String = storage.readPreferences()[KEY_APP_THEME] ?: "LIGHT"
}
