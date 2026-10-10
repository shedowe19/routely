package de.traewelling.app.data.dbf

import android.content.Context
import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StopRealtimeInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/** Anonymous, optional DBF/IRIS station-board data. Träwelling owns trip and stopover identity. */
class DbfRealtimeRepository internal constructor(
    private val enabledProvider: suspend () -> Boolean,
    private val store: DbfBoardStore = sharedStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(PreferencesManager(context.applicationContext)::getPublicRailRealtimeEnabled)

    suspend fun enrich(stops: List<StopStation>, checkin: CheckinInfo): List<StopStation> {
        if (!isRail(checkin) || (checkin.journeyNumber ?: 0) <= 0 || stops.isEmpty() || !isEnabled()) return stops
        val now = nowMillis()
        val candidates = stops.withIndex().mapNotNull { (index, stop) ->
            val eva = stop.station?.ibnr?.toString()?.takeIf(EVA::matches) ?: return@mapNotNull null
            val planned = listOfNotNull(parseInstant(stop.arrivalPlanned), parseInstant(stop.departurePlanned))
            if (planned.isEmpty()) return@mapNotNull null
            // A delayed current train may have no usable primary realtime. Its bounded
            // planned marker must still permit fetching the independently matched board.
            val markers = (planned + listOfNotNull(parseInstant(stop.arrivalReal), parseInstant(stop.departureReal))).distinct()
            val event = markers.filter { it.toEpochMilli() in (now - PAST_WINDOW_MILLIS)..(now + FUTURE_WINDOW_MILLIS) }
                .minByOrNull { abs(it.toEpochMilli() - now) } ?: return@mapNotNull null
            Candidate(index, eva, event.toEpochMilli(), stop.matchesStopover(checkin.destination))
        }
        // Current/next visit first, then the exit if its event is inside the board horizon.
        val sorted = candidates.sortedBy { abs(it.eventMillis - now) }
        val selectedEvas = buildList {
            sorted.firstOrNull()?.let { add(it.eva) }
            sorted.firstOrNull { it.destination && it.eva !in this }?.let { add(it.eva) }
            sorted.firstOrNull { it.eva !in this }?.let { if (size < MAX_STATIONS) add(it.eva) }
        }.take(MAX_STATIONS)
        val selected = candidates.filter { it.eva in selectedEvas }.take(MAX_VISITS)
        if (selected.isEmpty()) return stops
        val result = stops.toMutableList()
        // Parallel boards allow two fresh stations without serially blocking the primary snapshot.
        // A timed-out caller cannot cancel a shared board that another screen/service awaits.
        withTimeoutOrNull(ENRICHMENT_TIMEOUT_MILLIS) {
            coroutineScope {
                selected.groupBy { it.eva }.map { (eva, visits) -> async {
                    val board = store.get(eva) ?: return@async
                    val fetchedAt = board.fetchedAt.toEpochMilli()
                    if (fetchedAt > nowMillis() || nowMillis() - fetchedAt > LIVE_TTL_MILLIS) return@async
                    for (candidate in visits) {
                        currentCoroutineContext().ensureActive()
                        val stop = stops[candidate.index]
                        val delta = DbfMatcher.match(board, eva, checkin.journeyNumber?.toString(),
                            parseInstant(stop.arrivalPlanned), parseInstant(stop.departurePlanned)) ?: continue
                        result[candidate.index] = applyDelta(stop, delta)
                    }
                } }.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        // Disabling optional public requests also discards a still-running result.
        return if (isEnabled()) result else stops
    }

    private suspend fun isEnabled(): Boolean = try {
        enabledProvider()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private data class Candidate(val index: Int, val eva: String, val eventMillis: Long, val destination: Boolean)

    companion object {
        const val LIVE_TTL_MILLIS = 60_000L
        internal const val FAILURE_TTL_MILLIS = 60_000L
        internal const val ENRICHMENT_TIMEOUT_MILLIS = 8_000L
        internal const val MAX_STATIONS = 2
        private const val MAX_VISITS = 6
        private const val PAST_WINDOW_MILLIS = 60 * 60_000L
        private const val FUTURE_WINDOW_MILLIS = 2 * 60 * 60_000L
        private val EVA = Regex("[0-9]{7}")
        private val RAIL_CATEGORIES = setOf("nationalexpress", "national", "regionalexp", "regional", "suburban")
        private val sharedStore: DbfBoardStore by lazy { DbfBoardStore(DbfBoardHttp()::fetch) }

        private fun isRail(checkin: CheckinInfo): Boolean {
            val mode = checkin.mode?.trim()?.lowercase(Locale.ROOT)
            val category = checkin.category?.trim()?.lowercase(Locale.ROOT)
            return mode != "bus" && category in RAIL_CATEGORIES
        }

        private fun parseInstant(value: String?): Instant? = value?.let {
            runCatching { Instant.parse(it).also { instant -> instant.toEpochMilli() } }.getOrNull()
        }

        /** Each field retains its own read age; a platform-only update cannot renew an old time. */
        internal fun applyDelta(stop: StopStation, delta: DbfStopDelta): StopStation {
            fun <T> DbfField<T>?.valueOrNull(): T? = (this as? DbfField.Present<T>)?.value
            val arrivalTime = delta.arrival?.realTime.valueOrNull()
            val departureTime = delta.departure?.realTime.valueOrNull()
            fun platformPair(event: DbfEventDelta?): Pair<String, String>? {
                val planned = event?.scheduledPlatform.valueOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
                val actual = event?.platform.valueOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
                return planned to actual
            }
            val arrivalPlatforms = platformPair(delta.arrival)
            val departurePlatforms = platformPair(delta.departure)
            val cancelled = delta.cancelled.valueOrNull()
            if (arrivalTime == null && departureTime == null && arrivalPlatforms == null && departurePlatforms == null &&
                cancelled == null) return stop
            val info = StopRealtimeInfo(delta.fetchedAt.toEpochMilli(), "DBF · IRIS", delta.providerUpdatedAt?.toEpochMilli())
            return stop.copy(
                arrivalReal = arrivalTime?.toString() ?: stop.arrivalReal,
                departureReal = departureTime?.toString() ?: stop.departureReal,
                arrivalPlatformPlanned = arrivalPlatforms?.first ?: stop.arrivalPlatformPlanned,
                arrivalPlatformReal = arrivalPlatforms?.second ?: stop.arrivalPlatformReal,
                departurePlatformPlanned = departurePlatforms?.first ?: stop.departurePlatformPlanned,
                departurePlatformReal = departurePlatforms?.second ?: stop.departurePlatformReal,
                cancelled = cancelled ?: stop.cancelled,
                arrivalRealtimeInfo = if (arrivalTime != null) info else stop.arrivalRealtimeInfo,
                departureRealtimeInfo = if (departureTime != null) info else stop.departureRealtimeInfo,
                arrivalPlatformRealtimeInfo = if (arrivalPlatforms != null) info else stop.arrivalPlatformRealtimeInfo,
                departurePlatformRealtimeInfo = if (departurePlatforms != null) info else stop.departurePlatformRealtimeInfo,
                cancellationRealtimeInfo = if (cancelled != null) info else stop.cancellationRealtimeInfo
            )
        }
    }
}

internal data class DbfFetchResult(val board: DbfBoard?, val retryAfterMillis: Long = 0L)

/** Process-wide singleflight and exact provider quotas, including failed requests. */
internal class DbfBoardStore(
    private val fetch: suspend (String) -> DbfFetchResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cacheLimit: Int = 96,
    private val inFlightLimit: Int = 4
) : Closeable {
    private data class Entry(val board: DbfBoard?, val expiresAtMillis: Long)
    private val lock = Any()
    private var closed = false
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val pending = mutableMapOf<String, Deferred<DbfBoard?>>()
    private val starts = ArrayDeque<Long>()
    private val stationStarts = mutableMapOf<String, Long>()
    private var pauseUntil = 0L

    init { require(cacheLimit > 0 && inFlightLimit > 0) }

    suspend fun get(eva: String): DbfBoard? {
        if (!Regex("[0-9]{7}").matches(eva)) return null
        val task = synchronized(lock) {
            if (closed) return null
            val now = nowMillis()
            entries[eva]?.let { if (now < it.expiresAtMillis) return it.board else entries.remove(eva) }
            pending[eva] ?: run {
                if (pending.size >= inFlightLimit || !reserveStart(eva, now)) return null
                scope.async(start = CoroutineStart.LAZY) {
                    var finished = false
                    var fetched = DbfFetchResult(null)
                    try {
                        fetched = fetch(eva)
                        // The requested EVA scopes the anonymous board; do not cache a mismatched fixture/source.
                        if (fetched.board?.eva?.let { it != eva } == true) fetched = DbfFetchResult(null)
                        finished = true
                        fetched.board
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        finished = true
                        null
                    } finally {
                        synchronized(lock) {
                            pending.remove(eva)
                            if (!closed && finished) {
                                val completed = nowMillis()
                                if (fetched.retryAfterMillis > 0) pauseUntil = maxOf(pauseUntil,
                                    expiry(completed, fetched.retryAfterMillis.coerceAtLeast(60_000L)))
                                val ttl = if (fetched.board == null) maxOf(DbfRealtimeRepository.FAILURE_TTL_MILLIS,
                                    fetched.retryAfterMillis) else DbfRealtimeRepository.LIVE_TTL_MILLIS
                                entries[eva] = Entry(fetched.board, expiry(completed, ttl))
                                while (entries.size > cacheLimit) entries.remove(entries.keys.first())
                            }
                        }
                    }
                }.also { pending[eva] = it }
            }
        }
        task.start()
        return task.await()
    }

    /** Never wait for a quota slot: optional data falls back promptly to the primary provider. */
    private fun reserveStart(eva: String, now: Long): Boolean {
        while (starts.firstOrNull()?.let { now - it >= WINDOW_MILLIS } == true) starts.removeFirst()
        stationStarts.entries.removeAll { now - it.value >= WINDOW_MILLIS }
        if (now < pauseUntil || starts.size >= MAX_STARTS || eva in stationStarts) return false
        starts.addLast(now)
        stationStarts[eva] = now
        return true
    }

    override fun close() {
        synchronized(lock) { closed = true; entries.clear(); pending.clear(); starts.clear(); stationStarts.clear() }
        scope.cancel()
    }

    companion object {
        internal const val WINDOW_MILLIS = 60_000L
        internal const val MAX_STARTS = 10
        private fun expiry(now: Long, duration: Long): Long =
            if (now >= 0 && duration > Long.MAX_VALUE - now) Long.MAX_VALUE else now + duration
    }
}

/** No account interceptors, keys, cookies, redirects, retries, or Träwelling bearer credentials. */
internal class DbfBoardHttp(
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    internal fun request(eva: String): Request {
        require(Regex("[0-9]{7}").matches(eva))
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("$eva.json")
            .addQueryParameter("version", "3")
            // Without this flag DBF combines nearby stations, and v3 rows contain no station ID.
            .addQueryParameter("no_related", "1")
            .addQueryParameter("past", "1").build()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Routely/1.0 (+https://github.com/shedowe19/routely)")
            .build()
    }

    suspend fun fetch(eva: String): DbfFetchResult = suspendCancellableCoroutine { continuation ->
        val request = request(eva)
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resume(DbfFetchResult(null))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = decodeResponse(response, request, eva)
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    internal fun decodeResponse(response: Response, request: Request, eva: String): DbfFetchResult = runCatching {
        response.use {
            if (it.request.url != request.url) return@use DbfFetchResult(null)
            if (!it.isSuccessful) return@use DbfFetchResult(null,
                if (it.code in setOf(429, 503)) retryAfter(it.header("Retry-After")) ?: 60_000L else 0L)
            // v3 contains HH:mm but no service date. A clearly old HTTP response must not
            // be rebound to today's matching train, and HTTP Date is not prediction provenance.
            if (staleHttpMetadata(it)) return@use DbfFetchResult(null)
            val body = it.body ?: return@use DbfFetchResult(null)
            if (body.contentLength() > MAX_BODY_BYTES) return@use DbfFetchResult(null)
            val buffer = Buffer()
            while (buffer.size <= MAX_BODY_BYTES) {
                val count = body.source().read(buffer, minOf(8_192L, MAX_BODY_BYTES + 1L - buffer.size))
                if (count == -1L) break
            }
            if (buffer.size > MAX_BODY_BYTES) return@use DbfFetchResult(null)
            val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
            DbfFetchResult(DbfJsonParser.parse(json, eva, Instant.ofEpochMilli(nowMillis())))
        }
    }.getOrElse { DbfFetchResult(null) }

    private fun staleHttpMetadata(response: Response): Boolean {
        response.header("Age")?.let { value ->
            val age = value.trim().toLongOrNull() ?: return true
            if (age !in 0L..MAX_HTTP_AGE_SECONDS) return true
        }
        response.header("Date")?.let { value ->
            val date = runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: return true
            val age = nowMillis() - date
            if (age > MAX_HTTP_AGE_SECONDS * 1_000L || age < -60_000L) return true
        }
        return false
    }

    private fun retryAfter(value: String?): Long? {
        value ?: return null
        val seconds = value.trim().toLongOrNull()?.takeIf { it >= 0 }
        val millis = if (seconds != null) {
            if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        } else runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis()
        }.getOrNull() ?: return null
        return millis.coerceAtLeast(60_000L)
    }

    companion object {
        internal const val MAX_BODY_BYTES = 2 * 1024 * 1024
        internal const val BASE_URL = "https://dbf.finalrewind.org/"
        private const val MAX_HTTP_AGE_SECONDS = 180L
    }
}
