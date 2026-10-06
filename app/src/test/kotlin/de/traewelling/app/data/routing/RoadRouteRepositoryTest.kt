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
import org.junit.Assert.*
import org.junit.Test
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
    ) = RoadRouteStore(scope, fetch, instantLimiter(), clock::get, cacheLimit, inFlightLimit)

    private fun instantLimiter(): RoadRouteRateLimiter {
        val clock = AtomicLong()
        return RoadRouteRateLimiter(clock::get) { clock.addAndGet(it * 1_000_000); Unit }
    }

    private fun geometry(first: RoutePoint, last: RoutePoint) =
        RoadRouteGeometry(first, last, listOf(listOf(first, last)), 1234)
}
