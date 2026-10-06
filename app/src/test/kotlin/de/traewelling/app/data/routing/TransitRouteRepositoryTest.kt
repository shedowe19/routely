package de.traewelling.app.data.routing

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TransitRouteRepositoryTest {
    private val request = TransitRouteRequest(42, "trip-1", listOf(
        TransitRouteVisit("origin", 1, "station-a", 0, 0, RoutePoint(51.0, 7.0)),
        TransitRouteVisit("goal", 2, "station-b", 120_000, 120_000, RoutePoint(51.1, 7.2))
    ))

    @Test fun nativeRequestSendsBearerOnlyToSelectedHttpsOriginAndStatusPath() {
        val http = TransitRouteHttp(AuthSession("https://example.test:8443/custom", "synthetic-token", "one"))
        try {
            val native = http.request(request)
            assertEquals("https", native.url.scheme)
            assertEquals("example.test", native.url.host)
            assertEquals(8443, native.url.port)
            assertEquals("/custom/api/v1/polyline/42", native.url.encodedPath)
            assertNull(native.url.query)
            assertNull(native.body)
            assertEquals("GET", native.method)
            assertEquals("Bearer synthetic-token", native.header("Authorization"))
            assertFalse(native.url.toString().contains("synthetic-token"))
            assertFalse(http.client.followRedirects)
            assertFalse(http.client.followSslRedirects)
            assertFalse(http.client.retryOnConnectionFailure)
            assertTrue(http.client.interceptors.isEmpty())
            assertTrue(http.client.networkInterceptors.isEmpty())
            assertEquals(20_000, http.client.callTimeoutMillis)
        } finally { http.close() }
    }

    @Test fun insecureCredentialBearingOrUnauthenticatedServersAreRejected() {
        listOf("http://example.test", "https://user:password@example.test", "https://example.test?q=1").forEach { server ->
            assertTrue(runCatching { TransitRouteHttp(AuthSession(server, "synthetic-token", "one")) }.isFailure)
        }
        assertTrue(runCatching { TransitRouteHttp(AuthSession("https://example.test", null, "one")) }.isFailure)
    }

    @Test fun foreignResponseOriginAndAuthPrivacyResponsesAreUncacheable() {
        val http = TransitRouteHttp(AuthSession("https://example.test", "synthetic-token", "one"))
        try {
            for (code in listOf(401, 403, 406)) {
                val response = response(http, "{}".toResponseBody(), code)
                assertFalse(http.decodeResponse(response, request).cacheable)
            }
            val foreign = response(http, "{}".toResponseBody()).newBuilder()
                .request(http.request(request).newBuilder().url("https://other.test/api/v1/polyline/42").build()).build()
            assertFalse(http.decodeResponse(foreign, request).cacheable)
            assertNull(http.decodeResponse(response(http, "{}".toResponseBody(), 302), request).geometry)
        } finally { http.close() }
    }

    @Test fun unknownLengthBodyReadIsBoundedAndMalformedUtf8IsRejected() {
        val http = TransitRouteHttp(AuthSession("https://example.test", "synthetic-token", "one"))
        try {
            val source = Buffer().write(ByteArray(TransitRouteParser.MAX_BODY_BYTES + 100) { 32 })
            val body = object : ResponseBody() {
                override fun contentType(): MediaType = "application/json".toMediaType()
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = source
            }
            assertNull(http.decodeResponse(response(http, body), request).geometry)
            assertEquals(99L, source.size)
            val malformed = byteArrayOf(0xc3.toByte(), 0x28).toResponseBody("application/json".toMediaType())
            assertNull(http.decodeResponse(response(http, malformed), request).geometry)
        } finally { http.close() }
    }

    @Test fun successfulCacheSoftRefreshesAtFourteenMinutes() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { key -> TransitRouteFetchResult(geometry(key, calls.incrementAndGet().toLong())) }
        try {
            assertEquals(1L, store.getRoute(request)?.fetchedAtMillis)
            clock.set(TransitRouteRepository.REFRESH_AFTER_MILLIS - 1)
            assertEquals(1L, store.getRoute(request)?.fetchedAtMillis)
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertEquals(2L, store.getRoute(request)?.fetchedAtMillis)
            assertEquals(2, calls.get())
        } finally { store.close() }
    }

    @Test fun failedSoftRefreshCannotExtendOldGeometryBeyondHardExpiry() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { key ->
            TransitRouteFetchResult(if (calls.incrementAndGet() == 2) null else geometry(key, calls.get().toLong()))
        }
        try {
            assertEquals(1L, store.getRoute(request)?.fetchedAtMillis)
            clock.set(TransitRouteRepository.REFRESH_AFTER_MILLIS)
            assertNull(store.getRoute(request))
            clock.incrementAndGet()
            assertEquals(1L, store.getRoute(request)?.fetchedAtMillis)
            clock.set(TransitRouteRepository.SUCCESS_TTL_MILLIS)
            assertNull(store.getRoute(request))
            assertEquals(2, calls.get())
            clock.set(TransitRouteRepository.REFRESH_AFTER_MILLIS + TransitRouteRepository.FAILURE_TTL_MILLIS)
            assertEquals(3L, store.getRoute(request)?.fetchedAtMillis)
        } finally { store.close() }
    }

    @Test fun failedLookupIsCachedForTwoMinutes() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { calls.incrementAndGet(); TransitRouteFetchResult(null) }
        try {
            assertNull(store.getRoute(request))
            clock.set(TransitRouteRepository.FAILURE_TTL_MILLIS - 1)
            assertNull(store.getRoute(request))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNull(store.getRoute(request))
            assertEquals(2, calls.get())
        } finally { store.close() }
    }

    @Test fun authOrPrivacyFailureCannotPoisonFutureLookupWithNegativeCache() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock) { key ->
            if (calls.incrementAndGet() == 1) TransitRouteFetchResult(null, false) else TransitRouteFetchResult(geometry(key))
        }
        try {
            assertNull(store.getRoute(request))
            assertNotNull(store.getRoute(request))
            assertEquals(2, calls.get())
        } finally { store.close() }
    }

    @Test fun exactTimetableVisitBasisAndTripAreSeparateBoundedCacheKeys() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val store = store(clock, cacheLimit = 2) { key -> calls.incrementAndGet(); TransitRouteFetchResult(geometry(key)) }
        try {
            val changedTime = request.copy(visits = request.visits.map { it.copy(departurePlannedMillis = 3_000) })
            val changedTrip = request.copy(tripIdentity = "different-trip")
            store.getRoute(request)
            store.getRoute(changedTime)
            store.getRoute(request)
            store.getRoute(changedTrip)
            store.getRoute(request)
            assertEquals(3, calls.get())
            store.getRoute(changedTime)
            assertEquals(4, calls.get())
        } finally { store.close() }
    }

    @Test fun differentSessionStoresNeverShareStatusGeometry() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val first = store(clock) { key -> calls.incrementAndGet(); TransitRouteFetchResult(geometry(key, 1)) }
        val second = store(clock) { key -> calls.incrementAndGet(); TransitRouteFetchResult(geometry(key, 2)) }
        try {
            assertEquals(1L, first.getRoute(request)?.fetchedAtMillis)
            assertEquals(2L, second.getRoute(request)?.fetchedAtMillis)
            assertEquals(2, calls.get())
        } finally { first.close(); second.close() }
    }

    @Test fun cancellingOneAwaiterPreservesSharedFollowerAndCachedResult() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(clock) { key ->
            calls.incrementAndGet(); entered.complete(Unit); release.await(); TransitRouteFetchResult(geometry(key))
        }
        try {
            val first = async { store.getRoute(request) }
            entered.await()
            val follower = async { store.getRoute(request) }
            yield()
            first.cancelAndJoin()
            assertFalse(follower.isCancelled)
            release.complete(Unit)
            assertNotNull(follower.await())
            assertNotNull(store.getRoute(request))
            assertEquals(1, calls.get())
        } finally { store.close() }
    }

    @Test fun closingStoreCancelsRequestAndRejectsEvenNonCancellableLateResult() = runBlocking {
        val clock = AtomicLong()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val store = store(clock) { key ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); finished.complete(Unit); TransitRouteFetchResult(geometry(key)) }
        }
        val pending = async { store.getRoute(request) }
        entered.await()
        store.close()
        assertNull(store.getRoute(request))
        release.complete(Unit)
        finished.await()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(store.getRoute(request))
    }

    @Test fun invalidInputAndBoundedInFlightNeverReachProvider() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(clock, inFlightLimit = 1) { key ->
            calls.incrementAndGet(); entered.complete(Unit); release.await(); TransitRouteFetchResult(geometry(key))
        }
        try {
            assertNull(store.getRoute(request.copy(statusId = -1)))
            val pending = async { store.getRoute(request) }
            entered.await()
            assertNull(store.getRoute(request.copy(statusId = 43)))
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertNotNull(pending.await())
        } finally { store.close() }
    }

    @Test fun responseForAnotherVisitBasisIsNotReturnedOrCached() = runBlocking {
        val clock = AtomicLong()
        val store = store(clock) { TransitRouteFetchResult(geometry(request.copy(tripIdentity = "wrong-trip"))) }
        try { assertNull(store.getRoute(request)); assertNull(store.getRoute(request)) } finally { store.close() }
    }

    private fun store(
        clock: AtomicLong,
        cacheLimit: Int = 8,
        inFlightLimit: Int = 4,
        fetch: suspend (TransitRouteRequest) -> TransitRouteFetchResult
    ) = TransitRouteStore(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), fetch, clock::get, cacheLimit, inFlightLimit)

    private fun geometry(request: TransitRouteRequest, fetchedAt: Long = 1234) = TransitRouteGeometry(request,
        listOf(GpsSegmentGeometry(request.visits.first().key, request.visits.last().key,
            RouteGeometry(request.visits.first().point, request.visits.last().point,
                listOf(listOf(request.visits.first().point, RoutePoint(51.05, 7.04), request.visits.last().point)), fetchedAt),
            GpsGeometrySource.TRIP_POLYLINE)), fetchedAt)

    private fun response(http: TransitRouteHttp, body: ResponseBody, code: Int = 200): Response = Response.Builder()
        .request(http.request(request)).protocol(Protocol.HTTP_1_1).code(code).message("synthetic-response").body(body).build()
}
