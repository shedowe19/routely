package de.traewelling.app.data.repository

import android.content.Context
import com.google.gson.Gson
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.AppDatabase
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.*
import de.traewelling.app.util.AuthSession
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest

class TraewellingRepository internal constructor(
    private val statusDao: StatusDao,
    private val sessionProvider: suspend () -> AuthSession,
    private val apiFactory: (AuthSession) -> TraewellingApiService
) {
    constructor(context: Context, prefs: PreferencesManager) : this(
        AppDatabase.getDatabase(context).statusDao(),
        prefs::getAuthSession,
        { session -> RetrofitClient.createApiService(session.serverUrl,
            session.accessToken?.takeIf { it.isNotBlank() } ?: error("Not authenticated")) }
    )

    private val gson = Gson()

    private suspend fun api(): TraewellingApiService = apiFactory(authenticatedSession())

    private suspend fun authenticatedSession(): AuthSession = sessionProvider().also {
        if (it.accessToken.isNullOrBlank()) error("Not authenticated")
    }

    // ─── Feed ─────────────────────────────────────────────────────────────────

    suspend fun getDashboard(page: Int = 1): Result<StatusListResponse> =
        getFeed(page, "dashboard") { it.getDashboard(page) }

    suspend fun getGlobalFeed(page: Int = 1): Result<StatusListResponse> =
        getFeed(page, "global") { it.getGlobalFeed(page) }

    private suspend fun getFeed(
        page: Int,
        kind: String,
        fetch: suspend (TraewellingApiService) -> Response<StatusListResponse>
    ): Result<StatusListResponse> = apiResult {
        val session = authenticatedSession()
        val type = cacheType(kind, session)
        try {
            val response = fetch(apiFactory(session))
            if (!response.isSuccessful) throw HttpException(response)
            val body = response.body() ?: error("Leere Antwort (" + response.code() + ")")
            val statuses = body.data ?: error("Fehlende Statusliste")
            requireCurrentSession(session)
            if (page == 1) {
                statusDao.replaceStatuses(type, statuses.mapIndexed { position, status ->
                    StatusEntity(status.id, gson.toJson(status), type, position)
                })
                requireCurrentSession(session)
            }
            body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Auth/validation failures must never resurrect a previous private feed.
            val temporaryFailure = failure is IOException || failure is HttpException &&
                (failure.code() == 408 || failure.code() == 429 || failure.code() >= 500)
            if (page != 1 || !temporaryFailure) throw failure
            requireCurrentSession(session)
            val cached = statusDao.getStatuses(type)
            requireCurrentSession(session)
            if (cached.isEmpty()) throw failure
            val statuses = cached.map { gson.fromJson(it.statusJson, Status::class.java) }
            StatusListResponse(statuses, links = null, meta = null)
        }
    }

    private suspend fun requireCurrentSession(expected: AuthSession) {
        if (sessionProvider() != expected) throw CancellationException("Session changed during feed request")
    }

    /** Credential digest keeps private snapshots separate without persisting the bearer token. */
    private fun cacheType(kind: String, session: AuthSession): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(session.serverUrl.trimEnd('/').toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(requireNotNull(session.accessToken).toByteArray(Charsets.UTF_8))
        return kind + ":" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ─── Status Actions ───────────────────────────────────────────────────────

    suspend fun likeStatus(id: Int): Result<Unit> = apiResult {
        val r = api().likeStatus(id)
        if (!r.isSuccessful) error("Like fehlgeschlagen (${r.code()})")
    }

    suspend fun unlikeStatus(id: Int): Result<Unit> = apiResult {
        val r = api().unlikeStatus(id)
        if (!r.isSuccessful) error("Unlike fehlgeschlagen (${r.code()})")
    }

    suspend fun deleteStatus(id: Int): Result<Unit> = apiResult {
        val r = api().deleteStatus(id)
        if (!r.isSuccessful) error("Löschen fehlgeschlagen (${r.code()})")
    }
    
    suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Result<Status> = apiResult {
        val r = api().updateStatus(id, request)
        r.body()?.data ?: error("Änderung fehlgeschlagen (${r.code()})")
    }

    // ─── Station Search ───────────────────────────────────────────────────────

    suspend fun searchStations(query: String): Result<List<TrainStation>> = apiResult {
        val r = api().searchStations(query)
        r.body()?.data ?: error("Keine Bahnhöfe gefunden (${r.code()})")
    }

    suspend fun getNearbyStations(lat: Double, lon: Double): Result<List<TrainStation>> = apiResult {
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
    ): Result<List<DepartureTrip>> = apiResult {
        val r = api().getStationDepartures(stationId, whenTime)
        r.body()?.data ?: error("Keine Abfahrten (${r.code()})")
    }

    /** Full trip with stopovers — needed to let the user pick their destination. */
    suspend fun getTrip(hafasTripId: String, lineName: String): Result<TripDetails> = apiResult {
        val r = api().getTrip(hafasTripId, lineName)
        val data = r.body()?.data ?: error("Trip nicht gefunden (${r.code()})")
        data.copy(stopovers = data.stopovers?.deduplicate())
    }

    suspend fun checkIn(request: CheckInRequest): Result<CheckInResult?> = apiResult {
        val r = api().checkIn(request)
        if (r.isSuccessful) {
            r.body()?.data ?: error("Leere Check-in-Antwort (${r.code()})")
        } else if (r.code() == 409) {
            val conflicts = apiResult {
                gson.fromJson(r.errorBody()?.string(), CheckInConflictResponse::class.java)
                    ?.data?.conflicts
            }.getOrNull().orEmpty()
            throw CheckInConflictException(conflicts)
        } else {
            error("Check-in fehlgeschlagen (${r.code()})")
        }
    }

    // ─── Statistics ───────────────────────────────────────────────────────────

    suspend fun getStatistics(): Result<StatisticsData> = apiResult {
        val r = api().getStatistics()
        r.body()?.data ?: error("Keine Statistiken (${r.code()})")
    }

    // ─── Profile ──────────────────────────────────────────────────────────────

    suspend fun getCurrentUser(): Result<User> = apiResult {
        val r = api().getAuthUser()
        r.body()?.data ?: error("Keine Nutzerdaten (${r.code()})")
    }

    suspend fun getUserProfile(username: String): Result<User> = apiResult {
        val r = api().getUserProfile(username)
        r.body()?.data ?: error("Kein Profil (${r.code()})")
    }

    suspend fun getUserStatuses(username: String, page: Int = 1): Result<StatusListResponse> = apiResult {
        val r = api().getUserStatuses(username, page)
        r.body() ?: error("Keine Fahrten (${r.code()})")
    }

    suspend fun searchUsers(query: String): Result<List<User>> = apiResult {
        val r = api().searchUsers(query)
        r.body()?.data ?: error("Benutzersuche fehlgeschlagen (${r.code()})")
    }

    // ─── Status Detail ────────────────────────────────────────────────────────

    suspend fun getStatusDetail(statusId: Int): Result<Status> = apiResult {
        val r = api().getStatus(statusId)
        r.body()?.data ?: error("Status nicht gefunden (${r.code()})")
    }

    suspend fun getStopovers(tripId: Int): Result<List<StopStation>> = apiResult {
        val r = api().getStopovers(tripId)
        r.body()?.allStopovers()?.deduplicate() ?: error("Keine Halte gefunden (${r.code()})")
    }

    // ─── Follow / Unfollow ────────────────────────────────────────────────────

    suspend fun followUser(userId: Int): Result<Unit> = apiResult {
        val r = api().followUser(userId)
        if (!r.isSuccessful) error("Folgen fehlgeschlagen (${r.code()})")
    }

    suspend fun unfollowUser(userId: Int): Result<Unit> = apiResult {
        val r = api().unfollowUser(userId)
        if (!r.isSuccessful) error("Entfolgen fehlgeschlagen (${r.code()})")
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    suspend fun getNotifications(page: Int = 1): Result<NotificationListResponse> = apiResult {
        val r = api().getNotifications(page)
        r.body() ?: error("Keine Benachrichtigungen (${r.code()})")
    }

    suspend fun getUnreadNotificationCount(): Result<Int> = apiResult {
        val r = api().getUnreadNotificationCount()
        if (!r.isSuccessful) throw HttpException(r)
        r.body()?.data ?: error("Leere Benachrichtigungsanzahl (${r.code()})")
    }

    suspend fun markNotificationRead(id: String): Result<Unit> = apiResult {
        val r = api().markNotificationRead(id)
        if (!r.isSuccessful) error("Markierung fehlgeschlagen (${r.code()})")
    }

    suspend fun markAllNotificationsRead(): Result<Unit> = apiResult {
        val r = api().markAllNotificationsRead()
        if (!r.isSuccessful) error("Markierung fehlgeschlagen (${r.code()})")
    }
}
