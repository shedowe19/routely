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
    private var lastStartNanos: Long? = null

    suspend fun awaitTurn() = mutex.withLock {
        lastStartNanos?.let { last ->
            val remaining = MIN_INTERVAL_NANOS - (nowNanos() - last)
            if (remaining > 0) waitMillis((remaining + 999_999L) / 1_000_000L)
        }
        lastStartNanos = nowNanos()
    }

    private companion object { const val MIN_INTERVAL_NANOS = 1_000_000_000L }
}

/** Bounded monotonic TTL cache with shared jobs independent of any awaiting caller. */
internal class RoadRouteStore(
    private val scope: CoroutineScope,
    private val fetch: suspend (RoutePoint, RoutePoint) -> RoadRouteGeometry?,
    private val limiter: RoadRouteRateLimiter,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cacheLimit: Int = 64,
    private val inFlightLimit: Int = 16
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
                if (nowMillis() < entry.expiresAtMillis) return entry.geometry
                cache.remove(key)
            }
            inFlight[key] ?: run {
                if (inFlight.size >= inFlightLimit) return null
                scope.async {
                    var completed = false
                    var geometry: RoadRouteGeometry? = null
                    try {
                        limiter.awaitTurn()
                        geometry = fetch(from, to)
                        completed = true
                        geometry
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
                                    cache[key] = Entry(geometry, nowMillis() +
                                        if (geometry == null) FAILURE_TTL_MILLIS else SUCCESS_TTL_MILLIS)
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

    suspend fun fetch(from: RoutePoint, to: RoutePoint): RoadRouteGeometry? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(from, to))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val geometry = runCatching {
                    response.use {
                        val responseUrl = it.request.url
                        if (!it.isSuccessful || responseUrl.scheme != "https" ||
                            responseUrl.host != "routing.openstreetmap.de" || responseUrl.port != 443) return@use null
                        val body = it.body ?: return@use null
                        if (body.contentLength() > RoadRouteParser.MAX_BODY_BYTES) return@use null
                        val source = body.source()
                        val buffer = Buffer()
                        while (buffer.size <= RoadRouteParser.MAX_BODY_BYTES) {
                            val count = source.read(buffer, minOf(8_192L,
                                RoadRouteParser.MAX_BODY_BYTES + 1L - buffer.size))
                            if (count == -1L) break
                        }
                        if (buffer.size > RoadRouteParser.MAX_BODY_BYTES) return@use null
                        val json = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
                        RoadRouteParser.parse(json, from, to, System.currentTimeMillis())
                    }
                }.getOrNull()
                if (continuation.isActive) continuation.resume(geometry)
            }
        })
    }
}
