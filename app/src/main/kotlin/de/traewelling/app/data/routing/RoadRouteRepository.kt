package de.traewelling.app.data.routing

import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Anonymous optional OSRM lookup, exclusively between public stop coordinates. */
object RoadRouteRepository {
    private val store = RoadRouteStore(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        fetch = RoadRouteHttp::fetch,
        limiter = RoadRouteRateLimiter()
    )

    suspend fun getRoute(from: RoutePoint, to: RoutePoint): RoadRouteGeometry? = store.getRoute(from, to)
}

/** Process-wide serialized request starts; cancellation while waiting consumes no slot. */
internal class RoadRouteRateLimiter(
    private val nowNanos: () -> Long = System::nanoTime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val deadlineLock = Any()
    private var lastStartNanos: Long? = null
    private var retryAfterNanos: Long? = null

    suspend fun awaitTurn() = mutex.withLock {
        while (true) {
            val now = nowNanos()
            val rateRemaining = lastStartNanos?.let { MIN_INTERVAL_NANOS - (now - it) } ?: 0L
            val providerRemaining = synchronized(deadlineLock) { retryAfterNanos?.minus(now) ?: 0L }
            val remaining = maxOf(rateRemaining, providerRemaining)
            if (remaining <= 0) break
            waitMillis((remaining + 999_999L) / 1_000_000L)
        }
        lastStartNanos = nowNanos()
    }

    /** Provider backoff affects subsequent public pairs, including callers already waiting. */
    fun postpone(millis: Long) {
        val delay = millis.coerceIn(RoadRouteStore.TRANSIENT_FAILURE_TTL_MILLIS,
            RoadRouteStore.FAILURE_TTL_MILLIS)
        synchronized(deadlineLock) {
            val deadline = nowNanos() + delay * 1_000_000L
            if (retryAfterNanos?.let { it > deadline } != true) retryAfterNanos = deadline
        }
    }

    private companion object { const val MIN_INTERVAL_NANOS = 1_000_000_000L }
}

/** A network outage is not evidence that the public stop pair has no usable route. */
internal data class RoadRouteFetchResult(
    val geometry: RoadRouteGeometry?,
    val transientFailure: Boolean = false,
    val retryAfterMillis: Long? = null
)

/** Bounded monotonic TTL cache with shared jobs independent of any awaiting caller. */
internal class RoadRouteStore(
    private val scope: CoroutineScope,
    private val fetch: suspend (RoutePoint, RoutePoint) -> RoadRouteFetchResult,
    private val limiter: RoadRouteRateLimiter,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cacheLimit: Int = 64,
    private val inFlightLimit: Int = 16,
    private val nowWallMillis: () -> Long = System::currentTimeMillis
) {
    private data class Key(val from: RoutePoint, val to: RoutePoint)
    private data class Entry(val geometry: RoadRouteGeometry?, val expiresAtMillis: Long)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<Key, Deferred<RoadRouteGeometry?>>()

    init { require(cacheLimit > 0 && inFlightLimit > 0) }

    suspend fun getRoute(from: RoutePoint, to: RoutePoint): RoadRouteGeometry? {
        if (!RoadRouteParser.validPoint(from) || !RoadRouteParser.validPoint(to) ||
            RoadRouteParser.distanceMeters(from, to) !in 100.0..RoadRouteParser.MAX_ROUTE_METERS) return null
        val key = Key(from, to)
        val pending = mutex.withLock {
            cache[key]?.let { entry ->
                val wallAge = entry.geometry?.let { nowWallMillis() - it.fetchedAtMillis }
                if (nowMillis() < entry.expiresAtMillis && (entry.geometry == null ||
                    (entry.geometry.fetchedAtMillis > 0 && wallAge != null && wallAge in 0L..SUCCESS_TTL_MILLIS))) {
                    return entry.geometry
                }
                cache.remove(key)
            }
            inFlight[key] ?: run {
                if (inFlight.size >= inFlightLimit) return null
                scope.async {
                    var completed = false
                    var result = RoadRouteFetchResult(null, transientFailure = true)
                    try {
                        limiter.awaitTurn()
                        result = fetch(from, to)
                        result.retryAfterMillis?.let(limiter::postpone)
                        if (result.geometry?.let { it.from != from || it.to != to } == true) {
                            result = RoadRouteFetchResult(null)
                        }
                        completed = true
                        result.geometry
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        completed = true
                        null
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                inFlight.remove(key)
                                if (completed) {
                                    val ttl = when {
                                        result.geometry != null -> SUCCESS_TTL_MILLIS
                                        result.transientFailure -> result.retryAfterMillis
                                            ?.coerceIn(TRANSIENT_FAILURE_TTL_MILLIS, FAILURE_TTL_MILLIS)
                                            ?: TRANSIENT_FAILURE_TTL_MILLIS
                                        else -> FAILURE_TTL_MILLIS
                                    }
                                    cache[key] = Entry(result.geometry, nowMillis() + ttl)
                                    while (cache.size > cacheLimit) cache.remove(cache.keys.first())
                                }
                            }
                        }
                    }
                }.also { inFlight[key] = it }
            }
        }
        return pending.await()
    }

    internal companion object {
        const val SUCCESS_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val FAILURE_TTL_MILLIS = 15 * 60 * 1_000L
        const val TRANSIENT_FAILURE_TTL_MILLIS = 60 * 1_000L
    }
}

/** Separate client: no account interceptor, bearer token, redirects or HTTP fallback. */
internal object RoadRouteHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun request(from: RoutePoint, to: RoutePoint): Request {
        require(RoadRouteParser.validPoint(from) && RoadRouteParser.validPoint(to))
        val coordinates = String.format(Locale.ROOT, "%.7f,%.7f;%.7f,%.7f",
            from.longitude, from.latitude, to.longitude, to.latitude)
        val url = "https://routing.openstreetmap.de/routed-car/route/v1/driving/$coordinates"
            .toHttpUrl().newBuilder()
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("overview", "full")
            .addQueryParameter("steps", "false")
            .addQueryParameter("alternatives", "2")
            .addQueryParameter("generate_hints", "false")
            .build()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Routely/1.0 (+https://github.com/shedowe19/routely)")
            .build()
    }

    suspend fun fetch(from: RoutePoint, to: RoutePoint): RoadRouteFetchResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(from, to))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resume(RoadRouteFetchResult(null, transientFailure = true))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { parseResponse(it, from, to, System.currentTimeMillis()) }
                } catch (_: IOException) {
                    RoadRouteFetchResult(null, transientFailure = true)
                } catch (_: Exception) {
                    RoadRouteFetchResult(null)
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    /** Only transport/server failures retry quickly; invalid geometry never weakens its parser. */
    internal fun parseResponse(response: Response, from: RoutePoint, to: RoutePoint,
                               nowMillis: Long): RoadRouteFetchResult {
        val responseUrl = response.request.url
        if (responseUrl.scheme != "https" || responseUrl.host != "routing.openstreetmap.de" ||
            responseUrl.port != 443) return RoadRouteFetchResult(null)
        if (response.code == 408 || response.code == 429 || response.code in 500..599) {
            val retryAfter = retryAfterMillis(response.header("Retry-After"), nowMillis)
                ?: RoadRouteStore.TRANSIENT_FAILURE_TTL_MILLIS.takeIf { response.code == 429 }
            return RoadRouteFetchResult(null, transientFailure = true, retryAfterMillis = retryAfter)
        }
        if (!response.isSuccessful) return RoadRouteFetchResult(null)
        val body = response.body ?: return RoadRouteFetchResult(null)
        if (body.contentLength() > RoadRouteParser.MAX_BODY_BYTES) return RoadRouteFetchResult(null)
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size <= RoadRouteParser.MAX_BODY_BYTES) {
            val count = source.read(buffer, minOf(8_192L,
                RoadRouteParser.MAX_BODY_BYTES + 1L - buffer.size))
            if (count == -1L) break
        }
        if (buffer.size > RoadRouteParser.MAX_BODY_BYTES) return RoadRouteFetchResult(null)
        val json = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
        }.getOrNull() ?: return RoadRouteFetchResult(null)
        return RoadRouteFetchResult(RoadRouteParser.parse(json, from, to, nowMillis))
    }

    /** RFC 9110 seconds or HTTP date, bounded so an optional route lookup cannot stall forever. */
    internal fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val delay = if (header.all { it in '0'..'9' }) {
            val seconds = header.toLongOrNull() ?: return RoadRouteStore.FAILURE_TTL_MILLIS
            seconds.coerceAtMost(RoadRouteStore.FAILURE_TTL_MILLIS / 1_000L) * 1_000L
        } else {
            val deadline = runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: return null
            (deadline - nowMillis).coerceAtLeast(0L)
        }
        return delay.coerceIn(RoadRouteStore.TRANSIENT_FAILURE_TTL_MILLIS, RoadRouteStore.FAILURE_TTL_MILLIS)
    }
}
