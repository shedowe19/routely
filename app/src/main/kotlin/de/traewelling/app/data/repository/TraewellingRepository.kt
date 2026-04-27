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
        // Compute bounding box for 1 KM radius
        // 1 degree latitude = ~111.32 km. 1 km ≈ 0.008983 degrees.
        val latOffset = 0.008983
        val minLat = lat - latOffset
        val maxLat = lat + latOffset

        // 1 degree longitude = ~111.32 km * cos(latitude).
        val latRad = Math.toRadians(lat)
        val lonOffset = 1.0 / (111.32 * Math.cos(latRad))
        val minLon = lon - lonOffset
        val maxLon = lon + lonOffset

        val r = api().getStationsInBoundingBox(minLat, maxLat, minLon, maxLon)
        val data = r.body()?.data
        if (!r.isSuccessful || data.isNullOrEmpty()) {
            error("Keine Bahnhöfe in der Nähe (${r.code()})")
        }

        // First sort by distance
        val sortedData = data.sortedBy { st ->
            if (st.latitude != null && st.longitude != null) {
                val dLat = st.latitude - lat
                val dLon = (st.longitude - lon) * Math.cos(latRad)
                dLat * dLat + dLon * dLon
            } else {
                Double.MAX_VALUE
            }
        }

        // Custom deduplication to handle variations like "Kaarster See" and "Kaarster See, Kaarst"
        val distinctStations = mutableListOf<TrainStation>()
        for (st in sortedData) {
            val isDuplicate = distinctStations.any { existing ->
                if (st.latitude != null && st.longitude != null && existing.latitude != null && existing.longitude != null) {
                    val dLat = st.latitude - existing.latitude
                    val dLon = (st.longitude - existing.longitude) * Math.cos(Math.toRadians(existing.latitude))
                    val distSq = dLat * dLat + dLon * dLon

                    // Roughly 200m is about 0.0018 degrees. 0.0018^2 = 0.00000324
                    val isClose = distSq < 0.0000035

                    val name1 = st.name?.lowercase() ?: ""
                    val name2 = existing.name?.lowercase() ?: ""

                    val name1NoCity = name1.substringBefore(",").trim()
                    val name2NoCity = name2.substringBefore(",").trim()

                    val tokens1 = name1NoCity.split(Regex("[\\s\\.-]+")).filter { it.length > 2 }.toSet()
                    val tokens2 = name2NoCity.split(Regex("[\\s\\.-]+")).filter { it.length > 2 }.toSet()

                    val hasOverlap = tokens1.intersect(tokens2).isNotEmpty() || name1.contains(name2NoCity) || name2.contains(name1NoCity)

                    isClose && hasOverlap
                } else {
                    st.name == existing.name
                }
            }
            if (!isDuplicate) {
                distinctStations.add(st)
            }
        }

        distinctStations
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
