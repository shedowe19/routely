package de.traewelling.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "traewelling_prefs")

class PreferencesManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "traewelling_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        val KEY_SERVER_URL    = stringPreferencesKey("server_url")
        val KEY_USERNAME      = stringPreferencesKey("username")
        val KEY_CLIENT_ID     = stringPreferencesKey("client_id")
        val KEY_LAST_NOTIFICATION_ID = stringPreferencesKey("last_notification_id")
        val KEY_ACTIVE_CHECKIN_ID    = stringPreferencesKey("active_checkin_id")
        val KEY_LAST_NOTIFIED_TRIP_ID = stringPreferencesKey("last_notified_trip_id")

        // Keys for EncryptedSharedPreferences
        private const val S_KEY_ACCESS_TOKEN  = "access_token"
        private const val S_KEY_REFRESH_TOKEN = "refresh_token"
        private const val S_KEY_CLIENT_SECRET = "client_secret"

        // Legacy keys for migration
        private val LEGACY_KEY_ACCESS_TOKEN  = stringPreferencesKey("access_token")
        private val LEGACY_KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val LEGACY_KEY_CLIENT_SECRET = stringPreferencesKey("client_secret")

        const val DEFAULT_SERVER_URL = "https://traewelling.de"
        const val REDIRECT_URI = "traewelling://oauth-callback"
        const val OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
    }

    private val _secureUpdateTrigger = MutableStateFlow(0)

    init {
        // Run migration if legacy tokens exist
        runBlocking {
            val legacyToken = context.dataStore.data.map { it[LEGACY_KEY_ACCESS_TOKEN] }.first()
            if (legacyToken != null) {
                val legacyRefresh = context.dataStore.data.map { it[LEGACY_KEY_REFRESH_TOKEN] }.first()
                val legacySecret  = context.dataStore.data.map { it[LEGACY_KEY_CLIENT_SECRET] }.first()
                
                securePrefs.edit().apply {
                    putString(S_KEY_ACCESS_TOKEN, legacyToken)
                    putString(S_KEY_REFRESH_TOKEN, legacyRefresh)
                    putString(S_KEY_CLIENT_SECRET, legacySecret)
                    apply()
                }
                
                context.dataStore.edit { 
                    it.remove(LEGACY_KEY_ACCESS_TOKEN)
                    it.remove(LEGACY_KEY_REFRESH_TOKEN)
                    it.remove(LEGACY_KEY_CLIENT_SECRET)
                }
            }
        }
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val accessToken: Flow<String?> = _secureUpdateTrigger.map {
        securePrefs.getString(S_KEY_ACCESS_TOKEN, null)
    }

    val refreshToken: Flow<String?> = _secureUpdateTrigger.map {
        securePrefs.getString(S_KEY_REFRESH_TOKEN, null)
    }

    val clientId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLIENT_ID]
    }

    val clientSecret: Flow<String?> = _secureUpdateTrigger.map {
        securePrefs.getString(S_KEY_CLIENT_SECRET, null)
    }

    val username: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USERNAME]
    }

    val isLoggedIn: Flow<Boolean> = accessToken.map { it != null }

    suspend fun saveServerConfig(serverUrl: String, clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl.trimEnd('/')
            prefs[KEY_CLIENT_ID]  = clientId
        }
        securePrefs.edit().putString(S_KEY_CLIENT_SECRET, clientSecret).apply()
        _secureUpdateTrigger.value += 1
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?) {
        securePrefs.edit().apply {
            putString(S_KEY_ACCESS_TOKEN, accessToken)
            putString(S_KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
        _secureUpdateTrigger.value += 1
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = username
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_USERNAME)
        }
        securePrefs.edit().apply {
            remove(S_KEY_ACCESS_TOKEN)
            remove(S_KEY_REFRESH_TOKEN)
            apply()
        }
        _secureUpdateTrigger.value += 1
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
        securePrefs.edit().clear().apply()
        _secureUpdateTrigger.value += 1
    }

    // Read current values once (suspend, for non-flow contexts)
    suspend fun getAccessToken(): String? = securePrefs.getString(S_KEY_ACCESS_TOKEN, null)

    suspend fun getServerUrl(): String =
        context.dataStore.data.map { it[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL }.first()

    suspend fun getClientId(): String? =
        context.dataStore.data.map { it[KEY_CLIENT_ID] }.first()

    suspend fun getClientSecret(): String? = securePrefs.getString(S_KEY_CLIENT_SECRET, null)

    suspend fun getRefreshToken(): String? = securePrefs.getString(S_KEY_REFRESH_TOKEN, null)

    suspend fun getUsername(): String? =
        context.dataStore.data.map { it[KEY_USERNAME] }.first()

    suspend fun getLastNotificationId(): String? =
        context.dataStore.data.map { it[KEY_LAST_NOTIFICATION_ID] }.first()

    suspend fun saveLastNotificationId(id: String) {
        context.dataStore.edit { it[KEY_LAST_NOTIFICATION_ID] = id }
    }

    suspend fun getActiveCheckinId(): String? =
        context.dataStore.data.map { it[KEY_ACTIVE_CHECKIN_ID] }.first()

    suspend fun saveActiveCheckinId(id: String?) {
        context.dataStore.edit { 
            if (id != null) it[KEY_ACTIVE_CHECKIN_ID] = id else it.remove(KEY_ACTIVE_CHECKIN_ID)
        }
    }

    suspend fun getLastNotifiedTripId(): String? =
        context.dataStore.data.map { it[KEY_LAST_NOTIFIED_TRIP_ID] }.first()

    suspend fun saveLastNotifiedTripId(id: String) {
        context.dataStore.edit { it[KEY_LAST_NOTIFIED_TRIP_ID] = id }
    }
}
