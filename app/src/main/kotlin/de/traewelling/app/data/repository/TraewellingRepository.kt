package de.traewelling.app.data.repository

import de.traewelling.app.data.api.RetrofitClient
import android.content.Context
import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.AppDatabase
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.*
import de.traewelling.app.util.PreferencesManager

class TraewellingRepository(private val context: Context, private val prefs: PreferencesManager) {
    private val database = AppDatabase.getDatabase(context)
    private val statusDao = database.statusDao()
    private val gson = Gson()

    private suspend fun api(): TraewellingApiService {
        val serverUrl   = prefs.getServerUrl()
        val accessToken = prefs.getAccessToken() ?: error("Not authenticated")
        return RetrofitClient.createApiService(serverUrl, accessToken)
    }

    // ─── Feed ─────────────────────────────────────────────────────────────────

    suspend fun getDashboard(page: Int = 1): Result<StatusListResponse> = runCatching {
        try {
            val r = api().getDashboard(page)
            val body = r.body() ?: error("Leere Antwort (${r.code()})")

            // Cache first page
            if (page == 1) {
                val entities = body.data?.mapNotNull { status ->
                    status.id?.let { id ->
                        StatusEntity(id = id, statusJson = gson.toJson(status), type = "dashboard")
                    }
                }
                if (!entities.isNullOrEmpty()) {
                    statusDao.clearStatuses("dashboard")
                    statusDao.insertStatuses(entities)
                }
            }
            body
        } catch (e: Exception) {
            // Read from cache if offline
            if (page == 1) {
                val cached = statusDao.getStatuses("dashboard")
                if (cached.isNotEmpty()) {
                    val statuses = cached.map { gson.fromJson(it.statusJson, Status::class.java) }
                    StatusListResponse(data = statuses, links = null, meta = null)
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    suspend fun getGlobalFeed(page: Int = 1): Result<StatusListResponse> = runCatching {
        try {
            val r = api().getGlobalFeed(page)
            val body = r.body() ?: error("Leere Antwort (${r.code()})")

            // Cache first page
            if (page == 1) {
                val entities = body.data?.mapNotNull { status ->
                    status.id?.let { id ->
                        StatusEntity(id = id, statusJson = gson.toJson(status), type = "global")
                    }
                }
                if (!entities.isNullOrEmpty()) {
                    statusDao.clearStatuses("global")
                    statusDao.insertStatuses(entities)
                }
            }
            body
        } catch (e: Exception) {
            // Read from cache if offline
            if (page == 1) {
                val cached = statusDao.getStatuses("global")
                if (cached.isNotEmpty()) {
                    val statuses = cached.map { gson.fromJson(it.statusJson, Status::class.java) }
                    StatusListResponse(data = statuses, links = null, meta = null)
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    // ─── Status Actions ───────────────────────────────────────────────────────

    suspend fun likeStatus(id: Int): Result<Unit> = runCatching {
        val r = api().likeStatus(id)
        if (!r.isSuccessful) error("Like fehlgeschlagen (${r.code()})")
    }

    suspend fun unlikeStatus(id: Int): Result<Unit> = runCatching {
        val r = api().unlikeStatus(id)
        if (!r.isSuccessful) error("Unlike fehlgeschlagen (${r.code()})")
    }

    suspend fun deleteStatus(id: Int): Result<Unit> = runCatching {
        val r = api().deleteStatus(id)
        if (!r.isSuccessful) error("Löschen fehlgeschlagen (${r.code()})")
    }
    
    suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Result<Status> = runCatching {
        val r = api().updateStatus(id, request)
        r.body()?.data ?: error("Änderung fehlgeschlagen (${r.code()})")
    }

    // ─── Station Search ───────────────────────────────────────────────────────

    suspend fun searchStations(query: String): Result<List<TrainStation>> = runCatching {
        val r = api().searchStations(query)
        r.body()?.data ?: error("Keine Bahnhöfe gefunden (${r.code()})")
    }

    suspend fun getNearbyStations(lat: Double, lon: Double): Result<List<TrainStation>> = runCatching {
        val r = api().getNearbyStations(lat, lon)
        if (!r.isSuccessful) {
            error("Keine Bahnhöfe in der Nähe (${r.code()})")
        }
        val json = r.body() ?: error("Leere Antwort (${r.code()})")
        val dataNode = json.get("data")
        if (dataNode == null || dataNode.isJsonNull) {
            error("Keine Bahnhöfe in der Nähe (${r.code()})")
        } else {
            // The API returns a single StationResource object (not an array).
            // Wrap it in a list for consistency with searchStations().
            val station = gson.fromJson(dataNode, TrainStation::class.java)
            listOf(station)
        }
    }

    // ─── Check-in ─────────────────────────────────────────────────────────────

    /** Departures for a station by its numeric Traewelling station ID. */
    suspend fun getStationDepartures(
        stationId: Int,
        whenTime: String? = null
    ): Result<List<DepartureTrip>> = runCatching {
        val r = api().getStationDepartures(stationId, whenTime)
        r.body()?.data ?: error("Keine Abfahrten (${r.code()})")
    }

    /** Full trip with stopovers — needed to let the user pick their destination. */
    suspend fun getTrip(hafasTripId: String, lineName: String): Result<TripDetails> = runCatching {
        val r = api().getTrip(hafasTripId, lineName)
        val data = r.body()?.data ?: error("Trip nicht gefunden (${r.code()})")
        data.copy(stopovers = data.stopovers?.deduplicate())
    }

    suspend fun checkIn(request: CheckInRequest): Result<CheckInResult?> = runCatching {
        val r = api().checkIn(request)
        if (r.isSuccessful) r.body()?.data
        else error("Check-in fehlgeschlagen (${r.code()}): ${r.errorBody()?.string()}")
    }

    // ─── Statistics ───────────────────────────────────────────────────────────

    suspend fun getStatistics(): Result<StatisticsData> = runCatching {
        val r = api().getStatistics()
        r.body()?.data ?: error("Keine Statistiken (${r.code()})")
    }

    // ─── Profile ──────────────────────────────────────────────────────────────

    suspend fun getCurrentUser(): Result<User> = runCatching {
        val r = api().getAuthUser()
        r.body()?.data ?: error("Keine Nutzerdaten (${r.code()})")
    }

    suspend fun getUserProfile(username: String): Result<User> = runCatching {
        val r = api().getUserProfile(username)
        r.body()?.data ?: error("Kein Profil (${r.code()})")
    }

    suspend fun getUserStatuses(username: String, page: Int = 1): Result<StatusListResponse> = runCatching {
        val r = api().getUserStatuses(username, page)
        r.body() ?: error("Keine Fahrten (${r.code()})")
    }

    suspend fun searchUsers(query: String): Result<List<User>> = runCatching {
        val r = api().searchUsers(query)
        r.body()?.data ?: error("Benutzersuche fehlgeschlagen (${r.code()})")
    }

    // ─── Status Detail ────────────────────────────────────────────────────────

    suspend fun getStatusDetail(statusId: Int): Result<Status> = runCatching {
        val r = api().getStatus(statusId)
        r.body()?.data ?: error("Status nicht gefunden (${r.code()})")
    }

    suspend fun getStopovers(tripId: Int): Result<List<StopStation>> = runCatching {
        val r = api().getStopovers(tripId)
        r.body()?.allStopovers()?.deduplicate() ?: error("Keine Halte gefunden (${r.code()})")
    }

    // ─── Follow / Unfollow ────────────────────────────────────────────────────

    suspend fun followUser(userId: Int): Result<Unit> = runCatching {
        val r = api().followUser(userId)
        if (!r.isSuccessful) error("Folgen fehlgeschlagen (${r.code()})")
    }

    suspend fun unfollowUser(userId: Int): Result<Unit> = runCatching {
        val r = api().unfollowUser(userId)
        if (!r.isSuccessful) error("Entfolgen fehlgeschlagen (${r.code()})")
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    suspend fun getNotifications(page: Int = 1): Result<NotificationListResponse> = runCatching {
        val r = api().getNotifications(page)
        r.body() ?: error("Keine Benachrichtigungen (${r.code()})")
    }

    suspend fun getUnreadNotificationCount(): Result<Int> = runCatching {
        val r = api().getUnreadNotificationCount()
        r.body()?.data ?: 0
    }

    suspend fun markNotificationRead(id: String): Result<Unit> = runCatching {
        val r = api().markNotificationRead(id)
        if (!r.isSuccessful) error("Markierung fehlgeschlagen (${r.code()})")
    }

    suspend fun markAllNotificationsRead(): Result<Unit> = runCatching {
        val r = api().markAllNotificationsRead()
        if (!r.isSuccessful) error("Markierung fehlgeschlagen (${r.code()})")
    }
}
