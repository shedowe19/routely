package de.traewelling.app.data.routing

import de.traewelling.app.data.api.ApiServerUrl
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Owned by one authentication session. Neither cached lines nor credentials cross sessions. */
class TransitRouteRepository(session: AuthSession) : Closeable {
    private val http = TransitRouteHttp(session)
    private val store = TransitRouteStore(CoroutineScope(SupervisorJob() + Dispatchers.IO), http::fetch)

    suspend fun getRoute(request: TransitRouteRequest): TransitRouteGeometry? = store.getRoute(request)

    override fun close() {
        store.close()
        http.close()
    }

    companion object {
        const val SUCCESS_TTL_MILLIS = 15 * 60 * 1_000L
        const val FAILURE_TTL_MILLIS = 2 * 60 * 1_000L
        const val REFRESH_AFTER_MILLIS = SUCCESS_TTL_MILLIS - 60 * 1_000L
    }
}

internal data class TransitRouteFetchResult(val geometry: TransitRouteGeometry?, val cacheable: Boolean = true)

/** Bounded RAM-only singleflight. Closing synchronously prevents all late writes and reuse. */
internal class TransitRouteStore(
    private val scope: CoroutineScope,
    private val fetch: suspend (TransitRouteRequest) -> TransitRouteFetchResult,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cacheLimit: Int = 8,
    private val inFlightLimit: Int = 4
) : Closeable {
    private data class Entry(
        val geometry: TransitRouteGeometry?,
        val expiresAtMillis: Long,
        val refreshAtMillis: Long,
        val retryAtMillis: Long
    )
    private val lock = Any()
    private var closed = false
    private val cache = LinkedHashMap<TransitRouteRequest, Entry>(8, 0.75f, true)
    private val inFlight = mutableMapOf<TransitRouteRequest, Deferred<TransitRouteGeometry?>>()

    init { require(cacheLimit > 0 && inFlightLimit > 0) }

    suspend fun getRoute(request: TransitRouteRequest): TransitRouteGeometry? {
        if (!TransitRouteParser.validRequest(request)) return null
        // Snapshot the only collection supplied by the caller before using it as a cache key.
        val key = request.copy(visits = request.visits.toList())
        val pending = synchronized(lock) {
            if (closed) return null
            cache[key]?.let { entry ->
                val now = nowMillis()
                if (now < entry.expiresAtMillis && (now < entry.refreshAtMillis || now < entry.retryAtMillis)) {
                    return entry.geometry
                }
                if (now >= entry.expiresAtMillis && now < entry.retryAtMillis) return null
            }
            inFlight[key] ?: run {
                if (inFlight.size >= inFlightLimit) return null
                scope.async(start = CoroutineStart.LAZY) {
                    var completed = false
                    var result = TransitRouteFetchResult(null)
                    try {
                        result = fetch(key)
                        // A custom fetcher must never poison a different visit basis.
                        if (result.geometry?.request?.let { it != key } == true) result = TransitRouteFetchResult(null)
                        completed = true
                        result.geometry
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        completed = true
                        null
                    } finally {
                        synchronized(lock) {
                            inFlight.remove(key)
                            if (!closed && completed) {
                                val now = nowMillis()
                                val previous = cache[key]
                                when {
                                    !result.cacheable -> cache.remove(key)
                                    result.geometry != null -> cache[key] = Entry(result.geometry,
                                        now + TransitRouteRepository.SUCCESS_TTL_MILLIS,
                                        now + TransitRouteRepository.REFRESH_AFTER_MILLIS, now)
                                    previous?.geometry != null && now < previous.expiresAtMillis -> {
                                        // A failed soft refresh never extends the old geometry's hard expiry.
                                        cache[key] = previous.copy(retryAtMillis = now + TransitRouteRepository.FAILURE_TTL_MILLIS)
                                    }
                                    else -> cache[key] = Entry(null, now + TransitRouteRepository.FAILURE_TTL_MILLIS,
                                        now + TransitRouteRepository.FAILURE_TTL_MILLIS, now + TransitRouteRepository.FAILURE_TTL_MILLIS)
                                }
                                while (cache.size > cacheLimit) cache.remove(cache.keys.first())
                            }
                        }
                    }
                }.also { inFlight[key] = it }
            }
        }
        pending.start()
        return pending.await()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            cache.clear()
            inFlight.clear()
        }
        scope.cancel()
    }
}

/** Bearer only to the selected API origin. No redirects, foreign route service or HTTP logging. */
internal class TransitRouteHttp(session: AuthSession) : Closeable {
    private val baseUrl = (ApiServerUrl.normalize(session.serverUrl) + "/").toHttpUrl()
    private val token = requireNotNull(session.accessToken?.takeIf { it.isNotBlank() }) { "Not authenticated" }
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun request(request: TransitRouteRequest): Request {
        require(TransitRouteParser.validRequest(request))
        val url = baseUrl.newBuilder().addPathSegments("api/v1/polyline")
            .addPathSegment(request.statusId.toString()).build()
        check(sameOrigin(url))
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "Routely/1.0 (+https://github.com/shedowe19/routely)")
            .build()
    }

    suspend fun fetch(request: TransitRouteRequest): TransitRouteFetchResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(this.request(request))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resume(TransitRouteFetchResult(null))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = decodeResponse(response, request)
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    internal fun decodeResponse(response: Response, request: TransitRouteRequest): TransitRouteFetchResult = runCatching {
        response.use {
            if (!sameOrigin(it.request.url)) return@use TransitRouteFetchResult(null, false)
            if (!it.isSuccessful) return@use TransitRouteFetchResult(null,
                it.code != 401 && it.code != 403 && it.code != 406)
            val body = it.body ?: return@use TransitRouteFetchResult(null)
            if (body.contentLength() > TransitRouteParser.MAX_BODY_BYTES) return@use TransitRouteFetchResult(null)
            val source = body.source()
            val buffer = Buffer()
            while (buffer.size <= TransitRouteParser.MAX_BODY_BYTES) {
                val count = source.read(buffer, minOf(8_192L,
                    TransitRouteParser.MAX_BODY_BYTES + 1L - buffer.size))
                if (count == -1L) break
            }
            if (buffer.size > TransitRouteParser.MAX_BODY_BYTES) return@use TransitRouteFetchResult(null)
            val json = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
            TransitRouteFetchResult(TransitRouteParser.parse(json, request, System.currentTimeMillis()))
        }
    }.getOrElse { TransitRouteFetchResult(null) }

    private fun sameOrigin(url: okhttp3.HttpUrl): Boolean = url.isHttps && url.host == baseUrl.host && url.port == baseUrl.port

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
