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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class TraewellingRepository internal constructor(
    private val statusDao: StatusDao,
    private val sessionProvider: suspend () -> AuthSession,
    private val apiFactory: (AuthSession) -> TraewellingApiService,
    private val mutations: StatusMutationStore,
    private val invalidateTrackingState: suspend (Int, AuthSession) -> Unit = { _, _ -> }
) {
    internal constructor(statusDao: StatusDao, sessionProvider: suspend () -> AuthSession,
        apiFactory: (AuthSession) -> TraewellingApiService) :
        this(statusDao, sessionProvider, apiFactory, StatusMutationEvents.store)

    constructor(context: Context, prefs: PreferencesManager) : this(
        AppDatabase.getDatabase(context).statusDao(),
        prefs::getAuthSession,
        { session -> RetrofitClient.createApiService(session.serverUrl,
            session.accessToken?.takeIf { it.isNotBlank() } ?: error("Not authenticated")) },
        StatusMutationEvents.store,
        prefs::invalidateTrackingState
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
        val accountKey = cacheType("account", session)
        val cacheRevision = mutations.cacheMutex.withLock { mutations.revisionFor(accountKey) }
        suspend fun requireUnchangedFeed() {
            requireCurrentSession(session)
            if (mutations.revisionFor(accountKey) != cacheRevision)
                throw CancellationException("Status changed during feed request")
        }
        try {
            val response = fetch(apiFactory(session))
            if (!response.isSuccessful) throw HttpException(response)
            val body = response.body() ?: error("Leere Antwort (" + response.code() + ")")
            val statuses = body.data ?: error("Fehlende Statusliste")
            mutations.cacheMutex.withLock {
                requireUnchangedFeed()
                if (page == 1) {
                    statusDao.replaceStatuses(type, statuses.mapIndexed { position, status ->
                        StatusEntity(status.id, gson.toJson(status), type, position)
                    })
                    requireUnchangedFeed()
                    mutations.freshCacheWritten(type)
                }
            }
            body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Auth/validation failures must never resurrect a previous private feed.
            val temporaryFailure = failure is IOException || failure is HttpException &&
                (failure.code() == 408 || failure.code() == 429 || failure.code() >= 500)
            if (page != 1 || !temporaryFailure) throw failure
            mutations.cacheMutex.withLock {
                requireUnchangedFeed()
                if (!mutations.canReadCache(type)) throw failure
                val cached = statusDao.getStatuses(type)
                requireUnchangedFeed()
                if (cached.isEmpty()) throw failure
                val statuses = cached.map { gson.fromJson(it.statusJson, Status::class.java) }
                StatusListResponse(statuses, links = null, meta = null)
            }
        }
    }

    private suspend fun requireCurrentSession(expected: AuthSession) {
        if (sessionProvider() != expected) throw CancellationException("Session changed during request")
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

    suspend fun likeStatus(id: Int): Result<Unit> = changeLike(id, liked = true)

    suspend fun unlikeStatus(id: Int): Result<Unit> = changeLike(id, liked = false)

    private suspend fun changeLike(id: Int, liked: Boolean): Result<Unit> = apiResult {
        val session = authenticatedSession()
        orderedStatusWrite(session, id) {
            val r = if (liked) apiFactory(session).likeStatus(id) else apiFactory(session).unlikeStatus(id)
            if (!r.isSuccessful) error("${if (liked) "Like" else "Unlike"} fehlgeschlagen (${r.code()})")
            completeStatusMutation(session, StatusMutation.LikeChanged(session.revision, id, liked))
        }
    }

    suspend fun deleteStatus(
        id: Int,
        expectedSession: AuthSession? = null,
        onCommitted: suspend (AuthSession) -> Unit = {}
    ): Result<Unit> = apiResult {
        val session = authenticatedSession()
        if (expectedSession != null && session != expectedSession)
            throw CancellationException("Session changed before status deletion")
        orderedStatusWrite(session, id) {
            val r = apiFactory(session).deleteStatus(id)
            if (!r.isSuccessful) error("Löschen fehlgeschlagen (${r.code()})")
            completeStatusMutation(session, StatusMutation.Deleted(session.revision, id))
            onCommitted(session)
        }
    }
    
    suspend fun updateStatus(id: Int, request: UpdateStatusRequest, expectedSession: AuthSession? = null): Result<Status> = apiResult {
        val session = authenticatedSession()
        if (expectedSession != null && session != expectedSession)
            throw CancellationException("Session changed before status correction")
        orderedStatusWrite(session, id) {
            val r = apiFactory(session).updateStatus(id, request)
            if (!r.isSuccessful) error("Änderung fehlgeschlagen (${r.code()})")
            val status = r.body()?.data?.takeIf { it.id == id }
            if (status == null) {
                completeStatusMutation(session, StatusMutation.Invalidated(session.revision, id))
                error("Änderung wurde angenommen, aber die Antwort ist unvollständig. Bitte aktualisiere die Fahrt (${r.code()}).")
            }
            completeStatusMutation(session, StatusMutation.Updated(session.revision, status))
            status
        }
    }

    private suspend fun <T> orderedStatusWrite(session: AuthSession, id: Int, write: suspend () -> T): T =
        mutations.writeMutex(cacheType("account", session), id).withLock {
            // Waiting remains cancellable. Never dispatch a queued write for a replaced login.
            coroutineContext.ensureActive()
            requireCurrentSession(session)
            coroutineContext.ensureActive()
            // Once dispatched, retain ordering through the bounded HTTP response and publication.
            // RetrofitClient has a 60-second *call* timeout; cancellation must not let a newer
            // write overtake this server commit while its response is still in flight.
            withContext(NonCancellable) {
                requireCurrentSession(session)
                write()
            }
        }

    private suspend fun completeStatusMutation(session: AuthSession, event: StatusMutation) =
        withContext(NonCancellable) {
            mutations.cacheMutex.withLock {
                val types = listOf(cacheType("dashboard", session), cacheType("global", session))
                // Even a late committed response invalidates its original credential partition.
                // A new revision may reuse those credentials, but may not revive this old cache.
                mutations.invalidate(cacheType("account", session), types)
                mutations.recordContentMutation(cacheType("account", session), event)
                if (event !is StatusMutation.LikeChanged) {
                    // Prevent a restart from restoring the old target before its next fresh GET.
                    // Local cleanup remains bounded and cannot make a committed PUT retryable.
                    try { withTimeout(15_000) { invalidateTrackingState(event.statusId, session) } }
                    catch (_: Exception) { /* The in-memory revision still blocks the old target. */ }
                }
                for (type in types) {
                    // The server already committed. A storage error must not turn that into a retryable mutation.
                    try { statusDao.clearStatuses(type) } catch (_: Exception) { /* Invalid cache remains blocked. */ }
                }
                requireCurrentSession(session)
                mutations.publish(event)
            }
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

        deduplicateNearbyStations(sortedData)
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

    suspend fun checkIn(request: CheckInRequest, expectedSession: AuthSession? = null): Result<CheckInResult?> = apiResult {
        val session = authenticatedSession()
        if (expectedSession != null && session != expectedSession)
            throw CancellationException("Session changed before check-in")
        val r = apiFactory(session).checkIn(request)
        requireCurrentSession(session)
        if (r.isSuccessful) {
            r.body()?.data?.takeIf { (it.status?.id ?: 0) > 0 } ?: throw CheckInAcceptedException()
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
        if (!r.isSuccessful) throw HttpException(r)
        val body = r.body() ?: error("Keine Fahrten (${r.code()})")
        body.data ?: error("Fehlende Statusliste (${r.code()})")
        body
    }

    suspend fun searchUsers(query: String): Result<List<User>> = apiResult {
        val r = api().searchUsers(query)
        r.body()?.data ?: error("Benutzersuche fehlgeschlagen (${r.code()})")
    }

    // ─── Status Detail ────────────────────────────────────────────────────────

    suspend fun getStatusDetail(statusId: Int): Result<Status> =
        getStatusDetailSnapshot(statusId).map { it.status }

    internal suspend fun getStatusMutationSnapshot(statusId: Int, expectedSession: AuthSession): StatusMutationSnapshot {
        requireCurrentSession(expectedSession)
        return mutations.cacheMutex.withLock {
            requireCurrentSession(expectedSession)
            mutations.contentSnapshot(cacheType("account", expectedSession), statusId).let { snapshot ->
                snapshot.copy(mutation = snapshot.mutation?.takeIf { it.sessionRevision == expectedSession.revision })
            }
        }
    }

    internal suspend fun getStatusDetailSnapshot(statusId: Int): Result<StatusDetailSnapshot> = apiResult {
        val session = authenticatedSession()
        val before = getStatusMutationSnapshot(statusId, session)
        val response = apiFactory(session).getStatus(statusId)
        if (!response.isSuccessful) throw HttpException(response)
        val status = response.body()?.data?.takeIf { it.id == statusId }
            ?: error("Status nicht gefunden (${response.code()})")
        mutations.cacheMutex.withLock {
            requireCurrentSession(session)
            val accountKey = cacheType("account", session)
            val after = mutations.contentSnapshot(accountKey, statusId)
            // A normal failed snapshot lets pollers retry; it must not cancel their owning loop.
            check(before.revision == after.revision) { "Fahrt wurde während des Abrufs geändert. Bitte erneut aktualisieren." }
            mutations.rememberContentBaseline(accountKey, statusId, after.revision)
            StatusDetailSnapshot(status, after.revision)
        }
    }

    /** A dispatched edit finishes before an automatic destination action may clear its trip. */
    internal suspend fun withStatusRevision(
        statusId: Int, expectedSession: AuthSession, revision: Long, action: suspend () -> Unit
    ): Boolean = mutations.writeMutex(cacheType("account", expectedSession), statusId).withLock {
        requireCurrentSession(expectedSession)
        if (getStatusMutationSnapshot(statusId, expectedSession).revision != revision) return@withLock false
        action()
        true
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
