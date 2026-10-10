package de.traewelling.app.data.dbf

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StationIdentifier
import de.traewelling.app.data.model.StopRealtimeInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DbfRealtimeRepositoryTest {
    private val now = Instant.parse("2026-10-10T17:10:00Z")
    private val eva = "8000152"

    @Test fun publicRequestIsAnonymousAndExcludesUnidentifiedRelatedStations() {
        val http = DbfBoardHttp()
        val request = http.request(eva)
        assertEquals("https://dbf.finalrewind.org/8000152.json?version=3&no_related=1&past=1", request.url.toString())
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertNull(request.header("Authorization"))
        assertNull(request.header("DB-Client-ID"))
        assertNull(request.header("DB-Api-Key"))
        assertNull(request.header("Cookie"))
        assertTrue(http.client.interceptors.isEmpty())
        assertTrue(http.client.networkInterceptors.isEmpty())
        assertFalse(http.client.followRedirects)
        assertFalse(http.client.followSslRedirects)
        assertFalse(http.client.retryOnConnectionFailure)
        assertEquals(6_000, http.client.callTimeoutMillis)
        listOf("Hannover", "8000152/else", "123", "8000152?token=x").forEach {
            assertTrue(runCatching { http.request(it) }.isFailure)
        }
    }

    @Test fun decoderRejectsForeignUrlRedirectOversizeAndMalformedUtf8() {
        val http = DbfBoardHttp(nowMillis = { now.toEpochMilli() })
        val request = http.request(eva)
        val foreign = response(http, json().toResponseBody()).newBuilder()
            .request(request.newBuilder().url("https://other.test/8000152.json").build()).build()
        assertNull(http.decodeResponse(foreign, request, eva).board)
        assertNull(http.decodeResponse(response(http, json().toResponseBody(), 302), request, eva).board)
        val buffer = Buffer().write(ByteArray(DbfBoardHttp.MAX_BODY_BYTES + 100) { 32 })
        val unknownLength = object : ResponseBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = buffer
        }
        assertNull(http.decodeResponse(response(http, unknownLength), request, eva).board)
        assertEquals(99L, buffer.size)
        val malformed = byteArrayOf(0xc3.toByte(), 0x28).toResponseBody("application/json".toMediaType())
        assertNull(http.decodeResponse(response(http, malformed), request, eva).board)
    }

    @Test fun retryAfterSecondsAndDatePauseFutureStationsWithoutShorteningLongPauses() = runBlocking {
        val http = DbfBoardHttp(nowMillis = { now.toEpochMilli() })
        val request = http.request(eva)
        val rateLimited = response(http, "".toResponseBody(), 429).newBuilder().header("Retry-After", "120").build()
        assertEquals(120_000L, http.decodeResponse(rateLimited, request, eva).retryAfterMillis)
        val dated = response(http, "".toResponseBody(), 503).newBuilder()
            .header("Retry-After", "Sat, 10 Oct 2026 17:12:00 GMT").build()
        assertEquals(120_000L, http.decodeResponse(dated, request, eva).retryAfterMillis)
        val huge = response(http, "".toResponseBody(), 429).newBuilder().header("Retry-After", "999999999").build()
        assertEquals(999_999_999_000L, http.decodeResponse(huge, request, eva).retryAfterMillis)
        val overflow = response(http, "".toResponseBody(), 429).newBuilder()
            .header("Retry-After", Long.MAX_VALUE.toString()).build()
        assertEquals(Long.MAX_VALUE, http.decodeResponse(overflow, request, eva).retryAfterMillis)
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { calls.incrementAndGet(); DbfFetchResult(null, 120_000L) }
        try {
            assertNull(store.get(eva))
            clock.set(119_999L)
            assertNull(store.get("8000105"))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNull(store.get("8000105"))
            assertEquals(2, calls.get())
        } finally { store.close() }
    }

    @Test fun datedOrCachedOldBoardsCannotBeRelabelledAsTodaysFreshPredictions() {
        val http = DbfBoardHttp(nowMillis = { now.toEpochMilli() })
        val request = http.request(eva)
        for ((name, value) in listOf("Date" to "Fri, 9 Oct 2026 17:10:00 GMT",
            "Date" to "Sat, 10 Oct 2026 17:12:00 GMT", "Age" to "86400", "Age" to "invalid")) {
            val old = response(http, json().toResponseBody()).newBuilder().header(name, value).build()
            assertNull(http.decodeResponse(old, request, eva).board)
        }
        val validCached = response(http, json().toResponseBody()).newBuilder()
            .header("Date", "Sat, 10 Oct 2026 17:08:50 GMT").header("Age", "70").build()
        val board = http.decodeResponse(validCached, request, eva).board
        assertNotNull(board)
        assertEquals(now, board?.fetchedAt)
        assertNull(board?.providerUpdatedAt)
    }

    @Test fun cachedBoardIsSharedForSixtySecondsAndFailureNeverRevivesExpiredSuccess() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { station ->
            if (calls.incrementAndGet() == 1) DbfFetchResult(board(station)) else DbfFetchResult(null)
        }
        try {
            assertNotNull(store.get(eva))
            clock.set(59_999)
            assertNotNull(store.get(eva))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNull(store.get(eva))
            clock.set(119_999)
            assertNull(store.get(eva))
            assertEquals(2, calls.get())
            clock.incrementAndGet()
            assertNull(store.get(eva))
            assertEquals(3, calls.get())
        } finally { store.close() }
    }

    @Test fun providerHourLongCooldownStillBlocksAllStationsAfterThirtyMinutes() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { calls.incrementAndGet(); DbfFetchResult(null, 3_600_000L) }
        try {
            assertNull(store.get(eva))
            clock.set(30 * 60_000L)
            assertNull(store.get(eva))
            assertNull(store.get("8000105"))
            assertEquals(1, calls.get())
            clock.set(3_600_000L)
            assertNull(store.get("8000105"))
            assertEquals(2, calls.get())
        } finally { store.close() }
    }

    @Test fun exactSlidingWindowAllowsTenStationStartsAndCountsFailedRequests() = runBlocking {
        val clock = AtomicLong()
        val starts = mutableListOf<Long>()
        val store = store(clock) { starts.add(clock.get()); DbfFetchResult(null) }
        try {
            repeat(10) { index -> assertNull(store.get((8_000_000 + index).toString())) }
            assertEquals(10, starts.size)
            clock.set(59_999L)
            assertNull(store.get("8000111"))
            assertEquals(10, starts.size)
            clock.incrementAndGet()
            assertNull(store.get("8000111"))
            assertEquals(11, starts.size)
            assertEquals(60_000L, starts.last())
        } finally { store.close() }
    }

    @Test fun cacheEvictionDoesNotPermitSecondStationRequestInsideOneMinute() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock, cacheLimit = 1) { station -> calls.incrementAndGet(); DbfFetchResult(board(station)) }
        try {
            store.get(eva)
            store.get("8000105")
            assertNull(store.get(eva))
            assertEquals(2, calls.get())
            clock.set(60_000L)
            assertNotNull(store.get(eva))
            assertEquals(3, calls.get())
        } finally { store.close() }
    }

    @Test fun cancellingOneAwaiterPreservesSingleflightForOtherScreenAndCache() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(clock) { station -> calls.incrementAndGet(); entered.complete(Unit); release.await(); DbfFetchResult(board(station)) }
        try {
            val first = async { store.get(eva) }
            entered.await()
            val second = async { store.get(eva) }
            yield()
            first.cancelAndJoin()
            assertFalse(second.isCancelled)
            release.complete(Unit)
            assertNotNull(second.await())
            assertNotNull(store.get(eva))
            assertEquals(1, calls.get())
        } finally { store.close() }
    }

    @Test fun enrichmentPreservesIdentityPlansAndSeparateUnchangedFieldReadAges() = runBlocking {
        val clock = AtomicLong()
        val sourceInfo = StopRealtimeInfo(now.minusSeconds(90).toEpochMilli(), "Träwelling")
        val original = stop().copy(arrivalReal = "2026-10-10T17:17:00Z", departureReal = "2026-10-10T17:20:00Z",
            arrivalPlatformPlanned = "5", departurePlatformPlanned = "5",
            arrivalRealtimeInfo = sourceInfo, departureRealtimeInfo = sourceInfo,
            arrivalPlatformRealtimeInfo = sourceInfo, departurePlatformRealtimeInfo = sourceInfo)
        // Platform-only source update has no trustworthy zero-minute realtime prediction.
        val store = store(clock) { station -> DbfFetchResult(board(station, arrivalDelay = 0, departureDelay = 0,
            scheduledPlatform = "12")) }
        try {
            val repo = DbfRealtimeRepository({ true }, store) { now.toEpochMilli() }
            val changed = repo.enrich(listOf(original), checkin(original)).single()
            assertEquals(original.station, changed.station)
            assertEquals(original.uuid, changed.uuid)
            assertEquals(original.arrivalPlanned, changed.arrivalPlanned)
            assertEquals(original.departurePlanned, changed.departurePlanned)
            assertEquals(original.arrivalReal, changed.arrivalReal)
            assertEquals(original.departureReal, changed.departureReal)
            assertEquals(sourceInfo, changed.arrivalRealtimeInfo)
            assertEquals(sourceInfo, changed.departureRealtimeInfo)
            // v3 has one platform and prefers departure, so it must not overwrite arrival.
            assertNull(changed.arrivalPlatformReal)
            assertEquals("5", changed.arrivalPlatformPlanned)
            assertEquals("7", changed.departurePlatformReal)
            assertEquals("12", changed.departurePlatformPlanned)
            assertEquals(sourceInfo, changed.arrivalPlatformRealtimeInfo)
            assertEquals("DBF · IRIS", changed.departurePlatformRealtimeInfo?.sourceLabel)
        } finally { store.close() }
    }

    @Test fun missingRealtimeOrDisabledOrBusOrMissingOperationalNumberMakesNoFalseUpdate() = runBlocking {
        val original = stop().copy(arrivalReal = "2026-10-10T17:25:00Z", cancelled = true)
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { station -> calls.incrementAndGet(); DbfFetchResult(board(station, missingRealtime = true)) }
        try {
            val disabled = DbfRealtimeRepository({ false }, store) { now.toEpochMilli() }
            assertEquals(listOf(original), disabled.enrich(listOf(original), checkin(original)))
            val repo = DbfRealtimeRepository({ true }, store) { now.toEpochMilli() }
            assertEquals(listOf(original), repo.enrich(listOf(original), checkin(original).copy(mode = "bus")))
            assertEquals(listOf(original), repo.enrich(listOf(original), checkin(original).copy(journeyNumber = null)))
            assertEquals(0, calls.get())
            assertEquals(listOf(original), repo.enrich(listOf(original), checkin(original)))
        } finally { store.close() }
    }

    @Test fun disablingDuringBoardReadDiscardsResultAndSourceDateDoesNotGetInvented() = runBlocking {
        val original = stop()
        val clock = AtomicLong()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var enabled = true
        val store = store(clock) { station -> entered.complete(Unit); release.await(); DbfFetchResult(board(station)) }
        try {
            val repo = DbfRealtimeRepository({ enabled }, store) { now.toEpochMilli() }
            val read = async { repo.enrich(listOf(original), checkin(original)) }
            entered.await()
            enabled = false
            release.complete(Unit)
            assertEquals(listOf(original), read.await())
            enabled = true
            val changed = repo.enrich(listOf(original), checkin(original)).single()
            assertNull(changed.arrivalRealtimeInfo?.providerUpdatedAtMillis)
            assertEquals("2026-10-10T17:15:00Z", changed.arrivalReal)
        } finally { store.close() }
    }

    @Test fun noDeltaDoesNotRestampRetainedTimesPlatformsOrCancellation() {
        val info = StopRealtimeInfo(123L, "Träwelling")
        val original = stop().copy(arrivalReal = "2026-10-10T17:25:00Z", arrivalPlatformReal = "4", cancelled = true,
            arrivalRealtimeInfo = info, arrivalPlatformRealtimeInfo = info, cancellationRealtimeInfo = info)
        val delta = DbfStopDelta(null, null, DbfField.Absent, now)
        assertEquals(original, DbfRealtimeRepository.applyDelta(original, delta))
    }

    @Test fun delayedCurrentTrainCanBeDiscoveredWhenPrimaryRealtimeIsMissing() = runBlocking {
        val original = stop().copy(arrivalPlanned = "2026-10-10T16:40:00Z", departurePlanned = "2026-10-10T16:41:00Z")
        val clock = AtomicLong()
        val store = store(clock) { station ->
            val delayed = json(arrivalDelay = 43, departureDelay = 43)
                .replace("19:12", "18:40").replace("19:13", "18:41")
            DbfFetchResult(DbfJsonParser.parse(delayed, station, now))
        }
        try {
            val repo = DbfRealtimeRepository({ true }, store) { now.toEpochMilli() }
            val changed = repo.enrich(listOf(original), checkin(original)).single()
            assertEquals("2026-10-10T17:23:00Z", changed.arrivalReal)
            assertEquals("2026-10-10T17:24:00Z", changed.departureReal)
            assertEquals(original.arrivalPlanned, changed.arrivalPlanned)
        } finally { store.close() }
    }

    @Test fun unpairedPlatformDeltaCannotCreateCrossProviderPlatformComparison() {
        val original = stop().copy(departurePlatformPlanned = "5", departurePlatformReal = "6")
        val delta = DbfStopDelta(null, DbfEventDelta(platform = DbfField.Present("7")), DbfField.Absent, now)
        assertEquals(original, DbfRealtimeRepository.applyDelta(original, delta))
    }

    private fun store(clock: AtomicLong, cacheLimit: Int = 96, fetch: suspend (String) -> DbfFetchResult): DbfBoardStore =
        DbfBoardStore(fetch, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock::get, cacheLimit)

    private fun board(station: String = eva, arrivalDelay: Int = 3, departureDelay: Int = 5,
                      missingRealtime: Boolean = false, scheduledPlatform: String = "4"): DbfBoard = DbfJsonParser.parse(
        json(arrivalDelay, departureDelay, missingRealtime, scheduledPlatform), station, now)

    private fun json(arrivalDelay: Int = 3, departureDelay: Int = 5, missingRealtime: Boolean = false,
                     scheduledPlatform: String = "4"): String = """
        {"departures":[{"trainType":"RE","trainNumber":"5001","train":"RE 5001",
        "scheduledArrival":"19:12","scheduledDeparture":"19:13","delayArrival":$arrivalDelay,
        "delayDeparture":$departureDelay,"platform":"7","scheduledPlatform":"$scheduledPlatform",
        "isCancelled":0,"missingRealtime":$missingRealtime}]}
    """.trimIndent()

    private fun stop(station: String = eva): StopStation = StopStation(uuid = "provider-stop-id", station = TrainStation(
        id = 80, name = "Synthetic station", identifiers = listOf(StationIdentifier("de_db_ibnr", station))),
        arrivalPlanned = "2026-10-10T17:12:00Z", departurePlanned = "2026-10-10T17:13:00Z",
        arrivalPlatformPlanned = "4", departurePlatformPlanned = "4")

    private fun checkin(stop: StopStation): CheckinInfo = CheckinInfo(
        hafasId = "synthetic-trip", category = "regional", mode = "train", lineName = "RE 1", distanceMeters = null,
        points = null, duration = null, origin = stop, destination = stop, operator = null, trip = 42, tripUuid = null,
        number = null, routeColor = null, routeTextColor = null, journeyNumber = 5001,
        manualDeparture = null, manualArrival = null)

    private fun response(http: DbfBoardHttp, body: ResponseBody, code: Int = 200): Response = Response.Builder()
        .request(http.request(eva)).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
}
