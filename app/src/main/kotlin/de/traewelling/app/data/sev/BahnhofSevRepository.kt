package de.traewelling.app.data.sev

import de.traewelling.app.data.model.SevMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Anonymous public-page client. It never shares Träwelling credentials or interceptors. */
object BahnhofSevRepository {
    private const val SUCCESS_TTL_NANOS = 6L * 60 * 60 * 1_000_000_000
    private const val FAILURE_TTL_NANOS = 15L * 60 * 1_000_000_000
    private const val MAX_CACHE_ENTRIES = 64
    private val allowedHosts = setOf("www.bahnhof.de", "bahnhof.de")
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<SevMap?>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun getMap(slug: String): SevMap? {
        if (!BahnhofSevParser.isValidSlug(slug)) return null
        while (true) {
            val (pending, leader) = mutex.withLock {
                val now = System.nanoTime()
                cache[slug]?.let { entry ->
                    val ttl = if (entry.map != null) SUCCESS_TTL_NANOS else FAILURE_TTL_NANOS
                    if (now - entry.createdAtNanos < ttl) return entry.map
                    cache.remove(slug)
                }
                inFlight[slug]?.let { it to false } ?: CompletableDeferred<SevMap?>().let {
                    inFlight[slug] = it
                    it to true
                }
            }
            if (!leader) {
                try {
                    return pending.await()
                } catch (_: CancellationException) {
                    // Another caller may have owned the cancelled download. Only
                    // this caller's own cancellation should stop its station batch.
                    currentCoroutineContext().ensureActive()
                    mutex.withLock {
                        if (inFlight[slug] === pending) inFlight.remove(slug)
                    }
                    continue
                }
            }
            try {
                val result = withContext(Dispatchers.IO) { download(slug) }
                mutex.withLock {
                    cache[slug] = CacheEntry(result, System.nanoTime())
                    while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                }
                pending.complete(result)
                return result
            } catch (cancelled: CancellationException) {
                pending.completeExceptionally(cancelled)
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        cache[slug] = CacheEntry(null, System.nanoTime())
                        while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                    }
                    pending.complete(null)
                }
                currentCoroutineContext().ensureActive()
                return null
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[slug] === pending) inFlight.remove(slug)
                    }
                }
            }
        }
    }

    private suspend fun download(slug: String): SevMap? {
        var url = HttpUrl.Builder().scheme("https").host("www.bahnhof.de")
            .addPathSegment(slug).addPathSegment("karte").build()
        repeat(4) { attempt ->
            if (!isAllowed(url)) return null
            val request = Request.Builder().url(url)
                .header("User-Agent", "Routely-SEV/1.0 (Android)")
                .header("Accept", "text/html")
                .build()
            val result = fetchPage(request)
            if (result.redirect != null) {
                if (attempt == 3) return null
                url = url.resolve(result.redirect)?.takeIf(::isAllowed) ?: return null
            } else {
                val html = result.html ?: return null
                return BahnhofSevParser.parse(html, slug, System.currentTimeMillis())
            }
        }
        return null
    }

    private suspend fun fetchPage(request: Request): PageResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(PageResult())
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use responseUse@ {
                        if (!isAllowed(it.request.url)) return@responseUse PageResult()
                        if (it.code in setOf(301, 302, 303, 307, 308)) {
                            return@responseUse PageResult(redirect = it.header("Location"))
                        }
                        if (!it.isSuccessful) return@responseUse PageResult()
                        val body = it.body ?: return@responseUse PageResult()
                        val contentType = body.contentType()
                        if (contentType?.type != "text" || contentType.subtype != "html") return@responseUse PageResult()
                        if (body.contentLength() > BahnhofSevParser.MAX_HTML_BYTES) return@responseUse PageResult()
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > BahnhofSevParser.MAX_HTML_BYTES) return@responseUse PageResult()
                                output.write(buffer, 0, read)
                            }
                        }
                        PageResult(html = output.toString(Charsets.UTF_8.name()))
                    }
                } catch (_: Exception) { PageResult() }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun isAllowed(url: HttpUrl): Boolean = url.scheme == "https" &&
        url.host in allowedHosts && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    private data class CacheEntry(val map: SevMap?, val createdAtNanos: Long)
    private data class PageResult(val html: String? = null, val redirect: String? = null)
}
