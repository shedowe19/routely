package de.traewelling.app.data.routing

import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RoadRouteRepositoryTest {
    private val from = RoutePoint(51.43, 6.88)
    private val to = RoutePoint(51.45, 7.01)

    @Test fun requestIsAnonymousHttpsUsesLonLatAndBoundedOsrmOptions() {
        val request = RoadRouteHttp.request(from, to)
        assertEquals("https", request.url.scheme)
        assertEquals("routing.openstreetmap.de", request.url.host)
        assertEquals(443, request.url.port)
        assertTrue(request.url.encodedPath.startsWith("/routed-car/route/v1/driving/6.8800000,51.4300000;7.0100000,51.4500000"))
        assertEquals("geojson", request.url.queryParameter("geometries"))
        assertEquals("full", request.url.queryParameter("overview"))
        assertEquals("false", request.url.queryParameter("steps"))
        assertEquals("2", request.url.queryParameter("alternatives"))
        assertEquals("false", request.url.queryParameter("generate_hints"))
        assertNull(request.header("Authorization"))
        assertTrue(requireNotNull(request.header("User-Agent")).contains("github.com/shedowe19/routely"))
        assertFalse(RoadRouteHttp.client.followRedirects)
        assertFalse(RoadRouteHttp.client.followSslRedirects)
        assertFalse(RoadRouteHttp.client.retryOnConnectionFailure)
        assertEquals(20_000, RoadRouteHttp.client.callTimeoutMillis)
        assertTrue(RoadRouteHttp.client.interceptors.isEmpty())
        assertTrue(RoadRouteHttp.client.networkInterceptors.isEmpty())
    }

    @Test fun requestStartsAreAtLeastOneSecondApart() = runBlocking {
        val clock = AtomicLong()
        val waits = mutableListOf<Long>()
        val starts = mutableListOf<Long>()
        val limiter = RoadRouteRateLimiter(clock::get) { millis ->
            waits.add(millis)
            clock.addAndGet(millis * 1_000_000)
        }
        repeat(3) { limiter.awaitTurn(); starts.add(clock.get()) }
        assertEquals(listOf(0L, 1_000_000_000L, 2_000_000_000L), starts)
        assertEquals(listOf(1_000L, 1_000L), waits)
    }

    @Test fun successCacheExpiresAfterTwentyFourHours() = runBlocking {
        val clock = AtomicLong(100)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = store(scope, clock) { first, last -> calls.incrementAndGet(); geometry(first, last) }
            assertNotNull(store.getRoute(from, to))
            clock.addAndGet(RoadRouteStore.SUCCESS_TTL_MILLIS - 1)
            assertNotNull(store.getRoute(from, to))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNotNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun failedLookupIsCachedForFifteenMinutes() = runBlocking {
        val clock = AtomicLong(100)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = store(scope, clock) { _, _ -> calls.incrementAndGet(); null }
            assertNull(store.getRoute(from, to))
            clock.addAndGet(RoadRouteStore.FAILURE_TTL_MILLIS - 1)
            assertNull(store.getRoute(from, to))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun transientNetworkFailureCanRecoverAfterOneMinuteWithoutRestartingTrip() = runBlocking {
        val clock = AtomicLong(100)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = resultStore(scope, clock) { first, last ->
                if (calls.incrementAndGet() == 1) RoadRouteFetchResult(null, transientFailure = true)
                else RoadRouteFetchResult(geometry(first, last))
            }
            assertNull(store.getRoute(from, to))
            clock.addAndGet(59_999)
            assertNull(store.getRoute(from, to))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNotNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
            clock.addAndGet(60_000)
            assertNotNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun thrownNetworkExceptionUsesShortRetryCache() = runBlocking {
        val clock = AtomicLong(100)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = resultStore(scope, clock) { first, last ->
                if (calls.incrementAndGet() == 1) throw IOException("synthetic offline")
                RoadRouteFetchResult(geometry(first, last))
            }
            assertNull(store.getRoute(from, to))
            clock.addAndGet(60_000)
            assertNotNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun retryAfterCacheDoesNotRefetchSamePairBeforeProviderDelay() = runBlocking {
        val clock = AtomicLong(100)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = resultStore(scope, clock) { first, last ->
                if (calls.incrementAndGet() == 1)
                    RoadRouteFetchResult(null, transientFailure = true, retryAfterMillis = 120_000)
                else RoadRouteFetchResult(geometry(first, last))
            }
            assertNull(store.getRoute(from, to))
            clock.addAndGet(119_999)
            assertNull(store.getRoute(from, to))
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            assertNotNull(store.getRoute(from, to))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun providerDelayAppliesToAnotherPublicPairAndStillRespectsOneSecondStarts() = runBlocking {
        val clockNanos = AtomicLong()
        val starts = mutableListOf<Long>()
        val limiter = RoadRouteRateLimiter(clockNanos::get) { clockNanos.addAndGet(it * 1_000_000); Unit }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = RoadRouteStore(scope, { first, last ->
                starts.add(clockNanos.get())
                if (starts.size == 1)
                    RoadRouteFetchResult(null, transientFailure = true, retryAfterMillis = 120_000)
                else RoadRouteFetchResult(geometry(first, last))
            }, limiter, { clockNanos.get() / 1_000_000 }, nowWallMillis = { 1234 })
            assertNull(store.getRoute(from, to))
            assertNotNull(store.getRoute(to, from))
            assertEquals(listOf(0L, 120_000_000_000L), starts)
            assertNotNull(store.getRoute(from, to))
            assertEquals(listOf(0L, 120_000_000_000L, 121_000_000_000L), starts)
        } finally { scope.cancel() }
    }

    @Test fun providerBackoffCanExtendAPreviouslyWaitingRateLimitedRequest() = runBlocking {
        val clock = AtomicLong()
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val limiter = RoadRouteRateLimiter(clock::get) { millis ->
            waits.add(millis)
            if (waits.size == 1) { waiting.complete(Unit); release.await() }
            clock.addAndGet(millis * 1_000_000)
        }
        limiter.awaitTurn()
        val next = async { limiter.awaitTurn(); clock.get() }
        waiting.await()
        limiter.postpone(120_000)
        release.complete(Unit)
        assertEquals(120_000_000_000L, next.await())
        assertEquals(listOf(1_000L, 119_000L), waits)
    }

    @Test fun httpTransportStatusesRetryQuicklyButRejectedResponsesDoNot() {
        for (code in listOf(408, 429, 500, 502, 503, 504, 599)) {
            response(code).use { reply ->
                val result = RoadRouteHttp.parseResponse(reply, from, to, 1234)
                assertNull(result.geometry)
                assertTrue("HTTP $code should be transient", result.transientFailure)
                assertEquals(if (code == 429) 60_000L else null, result.retryAfterMillis)
            }
        }
        for (code in listOf(301, 302, 400, 401, 403, 404)) {
            response(code).use { reply ->
                val result = RoadRouteHttp.parseResponse(reply, from, to, 1234)
                assertNull(result.geometry)
                assertFalse("HTTP $code should not trigger short retry", result.transientFailure)
                assertNull(result.retryAfterMillis)
            }
        }
    }

    @Test fun missingMalformedOrNoRouteGeometryIsNotMistakenForNetworkRecovery() {
        val bodies = listOf(
            "".toResponseBody(),
            "{\"code\":\"NoRoute\"}".toResponseBody(),
            "not json".toResponseBody(),
            byteArrayOf(0xc3.toByte(), 0x28).toResponseBody("application/json".toMediaType()),
            " ".repeat(RoadRouteParser.MAX_BODY_BYTES + 1).toResponseBody()
        )
        bodies.forEach { body ->
            response(body = body).use { reply ->
                val result = RoadRouteHttp.parseResponse(reply, from, to, 1234)
                assertNull(result.geometry)
                assertFalse(result.transientFailure)
            }
        }
    }

    @Test fun responseOriginGuardPrecedesTransientStatusAndRetryAfter() {
        val foreign = RoadRouteHttp.request(from, to).newBuilder().url("https://unrelated.example/route").build()
        response(503).newBuilder().request(foreign).header("Retry-After", "120").build().use { reply ->
            val result = RoadRouteHttp.parseResponse(reply, from, to, 1234)
            assertNull(result.geometry)
            assertFalse(result.transientFailure)
            assertNull(result.retryAfterMillis)
        }
        response(503).newBuilder().header("Retry-After", "120").build().use { reply ->
            assertEquals(120_000L, RoadRouteHttp.parseResponse(reply, from, to, 1234).retryAfterMillis)
        }
    }

    @Test fun retryAfterSupportsSecondsAndHttpDateWithBoundedBackoff() {
        val now = Instant.parse("2026-10-07T05:00:00Z").toEpochMilli()
        assertEquals(120_000L, RoadRouteHttp.retryAfterMillis("120", now))
        assertEquals(120_000L, RoadRouteHttp.retryAfterMillis("Wed, 07 Oct 2026 05:02:00 GMT", now))
        assertEquals(60_000L, RoadRouteHttp.retryAfterMillis("0", now))
        assertEquals(60_000L, RoadRouteHttp.retryAfterMillis("Wed, 07 Oct 2026 04:59:00 GMT", now))
        assertEquals(900_000L, RoadRouteHttp.retryAfterMillis("99999999999999999999", now))
        assertEquals(900_000L, RoadRouteHttp.retryAfterMillis("Wed, 07 Oct 2026 07:00:00 GMT", now))
        assertNull(RoadRouteHttp.retryAfterMillis("-1", now))
        assertNull(RoadRouteHttp.retryAfterMillis("1.5", now))
        assertNull(RoadRouteHttp.retryAfterMillis("not a date", now))
        assertNull(RoadRouteHttp.retryAfterMillis(null, now))
        assertNull(RoadRouteHttp.retryAfterMillis("1".repeat(129), now))
    }

    @Test fun successfulRealPublicSnapshotRemainsValidatedBeforeCaching() {
        val origin = RoutePoint(51.42804102, 6.77808449)
        val destination = RoutePoint(51.43175246, 6.88538831)
        val json = requireNotNull(javaClass.getResourceAsStream("/routing/duisburg-muelheim-osrm.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        response(body = json.toResponseBody()).use { reply ->
            val result = RoadRouteHttp.parseResponse(reply, origin, destination, 1234)
            assertEquals(origin, requireNotNull(result.geometry).from)
            assertEquals(destination, result.geometry.to)
            assertEquals(listOf(315, 382), result.geometry.alternatives.map { it.size })
            assertFalse(result.transientFailure)
        }
        response(body = json.toResponseBody()).use { reply ->
            assertNull(RoadRouteHttp.parseResponse(reply, destination, origin, 1234).geometry)
        }
    }

    @Test fun successfulCacheRecoversWhenWallClockMakesGeometryFutureDatedOrTooOld() = runBlocking {
        val clock = AtomicLong(100)
        val wallClock = AtomicLong(2_000)
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = RoadRouteStore(scope, { first, last ->
                calls.incrementAndGet()
                RoadRouteFetchResult(geometry(first, last).copy(fetchedAtMillis = wallClock.get()))
            }, instantLimiter(), clock::get, nowWallMillis = wallClock::get)
            assertEquals(2_000L, requireNotNull(store.getRoute(from, to)).fetchedAtMillis)
            wallClock.set(1_000)
            assertEquals(1_000L, requireNotNull(store.getRoute(from, to)).fetchedAtMillis)
            assertEquals(2, calls.get())
            wallClock.addAndGet(RoadRouteStore.SUCCESS_TTL_MILLIS + 1)
            assertEquals(wallClock.get(), requireNotNull(store.getRoute(from, to)).fetchedAtMillis)
            assertEquals(3, calls.get())
            assertNotNull(store.getRoute(from, to))
            assertEquals(3, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun mismatchingFetchCannotPoisonAnotherPublicPair() = runBlocking {
        val clock = AtomicLong(100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = resultStore(scope, clock) { first, last -> RoadRouteFetchResult(geometry(last, first)) }
            assertNull(store.getRoute(from, to))
            assertNull(store.getRoute(from, to))
        } finally { scope.cancel() }
    }

    @Test fun orderedCoordinatesAreCacheIdentityAndCacheIsBounded() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = store(scope, clock, cacheLimit = 2) { first, last -> calls.incrementAndGet(); geometry(first, last) }
            store.getRoute(from, to)
            store.getRoute(to, from)
            store.getRoute(from, to) // Refresh first pair's LRU position.
            store.getRoute(from, to.copy(latitude = to.latitude + 0.001))
            assertEquals(3, calls.get())
            store.getRoute(from, to)
            assertEquals(3, calls.get())
            store.getRoute(to, from) // Least-recently-used pair was evicted.
            assertEquals(4, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun cancellingFirstAwaiterDoesNotCancelSharedFollowerRequest() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = store(scope, clock) { first, last ->
                calls.incrementAndGet(); entered.complete(Unit); release.await(); geometry(first, last)
            }
            val first = async { store.getRoute(from, to) }
            entered.await()
            val follower = async { store.getRoute(from, to) }
            yield()
            first.cancelAndJoin()
            assertFalse(follower.isCancelled)
            release.complete(Unit)
            assertNotNull(follower.await())
            assertEquals(1, calls.get())
            assertNotNull(store.getRoute(from, to))
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun pendingJobsAreBoundedAndInvalidInputsNeverCallProvider() = runBlocking {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = store(scope, clock, inFlightLimit = 1) { first, last ->
                calls.incrementAndGet(); entered.complete(Unit); release.await(); geometry(first, last)
            }
            assertNull(store.getRoute(from.copy(latitude = Double.NaN), to))
            assertNull(store.getRoute(from, from))
            val pending = async { store.getRoute(from, to) }
            entered.await()
            assertNull(store.getRoute(to, from))
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertNotNull(pending.await())
        } finally { scope.cancel() }
    }

    private fun store(
        scope: CoroutineScope,
        clock: AtomicLong,
        cacheLimit: Int = 64,
        inFlightLimit: Int = 16,
        fetch: suspend (RoutePoint, RoutePoint) -> RoadRouteGeometry?
    ) = resultStore(scope, clock, cacheLimit, inFlightLimit) { first, last ->
        RoadRouteFetchResult(fetch(first, last))
    }

    private fun resultStore(
        scope: CoroutineScope,
        clock: AtomicLong,
        cacheLimit: Int = 64,
        inFlightLimit: Int = 16,
        fetch: suspend (RoutePoint, RoutePoint) -> RoadRouteFetchResult
    ) = RoadRouteStore(scope, fetch, instantLimiter(), clock::get, cacheLimit, inFlightLimit,
        nowWallMillis = { 1234 })

    private fun response(code: Int = 200, body: ResponseBody = "{}".toResponseBody()): Response = Response.Builder()
        .request(RoadRouteHttp.request(from, to)).protocol(Protocol.HTTP_1_1).code(code)
        .message("synthetic response").body(body).build()

    private fun instantLimiter(): RoadRouteRateLimiter {
        val clock = AtomicLong()
        return RoadRouteRateLimiter(clock::get) { clock.addAndGet(it * 1_000_000); Unit }
    }

    private fun geometry(first: RoutePoint, last: RoutePoint) =
        RoadRouteGeometry(first, last, listOf(listOf(first, last)), 1234)
}
