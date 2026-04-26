package de.traewelling.app.data.repository

import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.model.*
import de.traewelling.app.util.PreferencesManager

class TraewellingRepository(private val prefs: PreferencesManager) {

    private suspend fun api(): TraewellingApiService {
        val serverUrl   = prefs.getServerUrl()
        val accessToken = prefs.getAccessToken() ?: error("Not authenticated")
        return RetrofitClient.createApiService(serverUrl, accessToken)
    }

    // ─── Feed ─────────────────────────────────────────────────────────────────

    suspend fun getDashboard(page: Int = 1): Result<StatusListResponse> = runCatching {
        val r = api().getDashboard(page)
        r.body() ?: error("Leere Antwort (${r.code()})")
    }

    suspend fun getGlobalFeed(page: Int = 1): Result<StatusListResponse> = runCatching {
        val r = api().getGlobalFeed(page)
        r.body() ?: error("Leere Antwort (${r.code()})")
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
        r.body()?.data ?: error("Keine Bahnhöfe in der Nähe (${r.code()})")
    }

    suspend fun getStation(stationId: Int): Result<TrainStation> = runCatching {
        val r = api().getStation(stationId)
        r.body()?.data ?: error("Haltestelle nicht gefunden (${r.code()})")
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
