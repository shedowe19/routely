package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.*
import de.traewelling.app.util.AuthSession
import de.traewelling.app.viewmodel.readConsistentStatusDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PublicRealtimeSessionTest {
    @Test fun aLatePublicReplyCannotPublishAfterLogoutAndSameCredentialsRelogin() = runTest {
        val fixture = Fixture()
        val originalSession = fixture.session
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.enrich = { stops, _ ->
            entered.complete(Unit)
            release.await()
            stops.map { it.copy(departurePlatformReal = "9") }
        }
        val originalStops = listOf(fixture.stop)
        val request = async { fixture.repository.enrichStopovers(originalStops, fixture.status.checkin!!) }
        entered.await()
        fixture.session = originalSession.copy(accessToken = null, revision = "logged-out")
        fixture.session = originalSession.copy(revision = "new-login-same-token")
        release.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertNull(originalStops.single().departurePlatformReal)
        assertTrue(fixture.apiCalls.isEmpty())
    }

    @Test fun aLatePublicReplyCannotPublishWhileLoggedOut() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.enrich = { stops, _ -> entered.complete(Unit); release.await(); stops }
        val request = async { fixture.repository.enrichStopovers(listOf(fixture.stop), fixture.status.checkin!!) }
        entered.await()
        fixture.session = fixture.session.copy(accessToken = null, revision = "logged-out")
        release.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
    }

    @Test fun successfulStopoverGetStampsTheReadWithoutInventingAProviderUpdate() = runTest {
        val fixture = Fixture()
        val oldProviderInfo = StopRealtimeInfo(1L, "Old provider", providerUpdatedAtMillis = 2L)
        fixture.stop = fixture.stop.copy(
            arrivalReal = "2026-10-10T12:07:00Z",
            arrivalRealtimeInfo = oldProviderInfo,
            departureRealtimeInfo = oldProviderInfo,
            cancellationRealtimeInfo = oldProviderInfo,
            arrivalPlatformRealtimeInfo = oldProviderInfo,
            departurePlatformRealtimeInfo = oldProviderInfo
        )
        val before = System.currentTimeMillis()
        val actual = fixture.repository.getStopovers(9).getOrThrow().single()
        val after = System.currentTimeMillis()
        listOf(actual.arrivalRealtimeInfo, actual.departureRealtimeInfo, actual.cancellationRealtimeInfo,
            actual.arrivalPlatformRealtimeInfo, actual.departurePlatformRealtimeInfo).forEach { nullableInfo ->
            val info = requireNotNull(nullableInfo)
            assertTrue(info.fetchedAtMillis in before..after)
            assertEquals("Träwelling", info.sourceLabel)
            assertNull(info.providerUpdatedAtMillis)
        }
        assertEquals(fixture.stop.arrivalReal, actual.arrivalReal)
        assertEquals(listOf("GET stopovers"), fixture.apiCalls)
    }

    @Test fun publicEnrichmentChangesOnlyTheLocalDisplayAndDoesNotSendStatusWrites() = runTest {
        val fixture = Fixture()
        val serverStatusBefore = fixture.status
        val supplied = fixture.repository.getStopovers(9).getOrThrow()
        val supplementalInfo = StopRealtimeInfo(System.currentTimeMillis(), "DBF / DB IRIS")
        fixture.enrich = { stops, _ -> stops.map {
            it.copy(departureReal = "2026-10-10T12:08:00Z", departurePlatformReal = "7",
                departureRealtimeInfo = supplementalInfo, departurePlatformRealtimeInfo = supplementalInfo)
        } }
        val displayed = fixture.repository.enrichStopovers(supplied, fixture.status.checkin!!).single()
        assertEquals("7", displayed.departurePlatformReal)
        assertEquals("2026-10-10T12:08:00Z", displayed.departureReal)
        assertNull(supplied.single().departurePlatformReal)
        assertSame(serverStatusBefore, fixture.status)
        // An unintended status PUT would appear here; unsupported service calls fail immediately.
        assertEquals(listOf("GET stopovers"), fixture.apiCalls)
        assertEquals(0, fixture.dao.clears)
    }

    @Test fun aTimeCorrectionWhileTheSupplementWaitsRetriesTheEntireDetailSnapshot() = runTest {
        val fixture = Fixture()
        val heldPublicReply = CompletableDeferred<Unit>()
        val publicEntered = CompletableDeferred<Unit>()
        var supplementalReads = 0
        fixture.enrich = { stops, _ ->
            ++supplementalReads
            if (supplementalReads == 1) {
                publicEntered.complete(Unit)
                heldPublicReply.await()
                stops.map { it.copy(departurePlatformReal = "old-platform") }
            } else stops.map { it.copy(departurePlatformReal = "new-platform") }
        }
        var snapshotCheckin: CheckinInfo? = null
        val capturedSession = fixture.session
        val reading = async {
            readConsistentStatusDetail(
                readStatus = {
                    fixture.repository.getStatusDetailSnapshot(42).also {
                        snapshotCheckin = it.getOrNull()?.status?.checkin
                    }
                },
                readStopovers = { tripId ->
                    val stops = fixture.repository.getStopovers(tripId)
                    if (stops.isSuccess) Result.success(fixture.repository.enrichStopovers(
                        stops.getOrThrow(), requireNotNull(snapshotCheckin)
                    )) else stops
                },
                isCurrentRevision = { expected ->
                    fixture.repository.getStatusMutationSnapshot(42, capturedSession).revision == expected
                }
            )
        }
        publicEntered.await()
        fixture.repository.updateStatus(42, UpdateStatusRequest(arrival = "2026-10-10T12:15:00Z")).getOrThrow()
        heldPublicReply.complete(Unit)
        runCurrent()
        val result = reading.await().getOrThrow()
        assertEquals("2026-10-10T12:15:00Z", result.status.checkin?.manualArrival)
        assertEquals("new-platform", result.stopovers?.getOrThrow()?.single()?.departurePlatformReal)
        assertEquals(2, supplementalReads)
        assertEquals(2, fixture.apiCalls.count { it == "GET status" })
        assertEquals(2, fixture.apiCalls.count { it == "GET stopovers" })
        assertEquals(1, fixture.apiCalls.count { it == "PUT status" }) // Only the explicit correction.
    }

    private class Fixture {
        var session = AuthSession("https://example.test", "synthetic-test-token", "original-login")
        val mutations = StatusMutationStore()
        val dao = Dao()
        val apiCalls = mutableListOf<String>()
        var status: Status = Gson().fromJson(
            """{"id":42,"checkin":{"trip":9,"lineName":"RE 1","number":"4306","manualArrival":null}}""",
            Status::class.java
        )
        var stop = StopStation(id = 1, station = TrainStation(id = 11, name = "Test station"),
            arrivalPlanned = "2026-10-10T12:00:00Z", departurePlanned = "2026-10-10T12:02:00Z")
        var enrich: suspend (List<StopStation>, CheckinInfo) -> List<StopStation> = { stops, _ -> stops }
        private val api = object : TraewellingApiService by unusedService() {
            override suspend fun getStopovers(tripId: Int): Response<StopoversResponse> {
                assertEquals(9, tripId)
                apiCalls += "GET stopovers"
                return Response.success(StopoversResponse(mapOf("9" to listOf(stop))))
            }
            override suspend fun getStatus(id: Int): Response<SingleStatusResponse> {
                assertEquals(42, id)
                apiCalls += "GET status"
                return Response.success(SingleStatusResponse(status))
            }
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Response<SingleStatusResponse> {
                assertEquals(42, id)
                apiCalls += "PUT status"
                status = status.copy(checkin = status.checkin!!.copy(manualArrival = request.arrival))
                return Response.success(SingleStatusResponse(status))
            }
        }
        val repository = TraewellingRepository(dao, { session }, { api }, mutations,
            enrichRealtime = { stops, checkin -> enrich(stops, checkin) })
    }

    private class Dao : StatusDao {
        var clears = 0
        override suspend fun getStatuses(type: String): List<StatusEntity> = error("Supplement must not read a feed cache")
        override suspend fun insertStatuses(statuses: List<StatusEntity>) = error("Supplement must not write a feed cache")
        // Only an explicit correction may invalidate its known feed partitions.
        override suspend fun clearStatuses(type: String) { ++clears }
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) = error("Supplement must not write a feed cache")
    }
}
