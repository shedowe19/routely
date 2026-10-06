package de.traewelling.app.data.sev

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.StopStation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Public station metadata only: neither device positions nor account credentials are sent. */
object SevJourneyEnricher {
    private val requestSlots = Semaphore(3)

    suspend fun loadMaps(checkin: CheckinInfo, fullRoute: List<StopStation>): Map<String, SevMap> {
        if (!SevStopResolver.isReplacementBus(checkin)) return emptyMap()
        val origin = fullRoute.indexOfFirst { it.matchesStopover(checkin.origin) }
        val destination = fullRoute.indexOfFirst { it.matchesStopover(checkin.destination) }
        if (origin < 0 || destination < origin) return emptyMap()
        val slugs = fullRoute.subList(origin, destination + 1)
            .filter { it.cancelled != true }
            .mapNotNull { it.station?.let(SevStopResolver::stationSlug) }
            .distinct().take(64)
        val maps = linkedMapOf<String, SevMap>()
        val resultMutex = Mutex()
        // The normal API/position pipeline does not wait for this optional enrichment.
        // Keep successful station results even if the remainder exceeds the batch deadline.
        withTimeoutOrNull(45_000L) {
            coroutineScope {
                slugs.map { slug ->
                    async {
                        requestSlots.withPermit {
                            BahnhofSevRepository.getMap(slug)?.let { map ->
                                resultMutex.withLock { maps[slug] = map }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return maps.toMap()
    }

    suspend fun enrich(
        checkin: CheckinInfo,
        fullRoute: List<StopStation>,
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, SevStopInfo> {
        val maps = loadMaps(checkin, fullRoute)
        // Fetch timestamps are created during the request, after the caller's UI tick.
        return SevStopResolver.resolve(checkin, fullRoute, maps, maxOf(nowMillis, System.currentTimeMillis()))
    }
}
