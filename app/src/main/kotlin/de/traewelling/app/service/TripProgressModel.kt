package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The current boarding visit uses its departure platform; later visits use arrival. */
internal fun trackingPlatform(stop: StopStation?, isOrigin: Boolean, isReplacementBus: Boolean): String? {
    if (isReplacementBus || stop == null) return null
    val values = if (isOrigin) listOf(stop.departurePlatformReal, stop.departurePlatformPlanned, stop.platform)
        else listOf(stop.arrivalPlatformReal, stop.arrivalPlatformPlanned, stop.platform)
    return values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}

/**
 * Progress counts ordered stopover visits. Arrival times use the shared resolver.
 * The origin is excluded and a cancelled stop is never counted as a remaining halt.
 */
data class TripProgressModel(
    val destinationName: String,
    val nextStopName: String?,
    val arrivedAtCurrent: Boolean,
    val remainingStops: Int?,
    val totalStops: Int,
    val passedStops: Int?,
    val completed: Boolean,
    val approximate: Boolean,
    val destinationCancelled: Boolean,
    val arrivalText: String?,
    val sourceLabel: String
) {
    val progressMax: Int get() = totalStops.coerceAtLeast(1) * UNITS_PER_STOP
    val progress: Int? get() = passedStops?.let {
        // A clock cursor at/past the last stop is not evidence of destination arrival.
        (it * UNITS_PER_STOP).coerceIn(0, if (completed) progressMax else progressMax - 1)
    }
    val shouldPromote: Boolean get() = !completed && totalStops > 0 && remainingStops != null

    val remainingText: String get() = when {
        completed -> "Ziel erreicht"
        destinationCancelled -> "Zielhalt entfällt"
        remainingStops == null -> "Fortschritt wird ermittelt"
        remainingStops == 0 -> "Am Ziel · Ankunft wird geprüft"
        remainingStops == 1 -> "Noch 1 Halt bis zum Ziel"
        else -> "Noch $remainingStops Halte bis zum Ziel"
    }

    val expandedText: String get() = listOfNotNull(
        nextStopName?.let { "${if (arrivedAtCurrent) "Aktuell" else "Nächster Halt"}: $it" },
        remainingText,
        arrivalText,
        sourceLabel
    ).joinToString("\n")

    companion object {
        const val UNITS_PER_STOP = 100

        fun from(
            stops: List<StopStation>,
            tracking: TrackingLiveState,
            destinationName: String? = null,
            zoneId: ZoneId = ZoneId.systemDefault(),
            manualDestinationArrival: String? = null,
            nowMillis: Long = System.currentTimeMillis()
        ): TripProgressModel {
            val destination = stops.lastOrNull()
            val countedIndices = stops.indices.filter { it > 0 && stops[it].cancelled != true }
            // Never use a bare numeric cursor to identify a visit: the same station
            // can occur twice, and a route refresh can alter its index.
            val match = tracking.nextStopKey?.let { key ->
                stops.indices.filter { stopKey(stops[it], it) == key }.singleOrNull()
            } ?: stops.indices.filter { stops[it].matchesStopover(tracking.stop) }.singleOrNull()
            val visitIndex = match?.let { index ->
                stops.indices.firstOrNull { it >= index && stops[it].cancelled != true }
            }
            val arrived = tracking.arrivedAtCurrent && visitIndex == match
            val passed = when {
                tracking.completed -> countedIndices.size
                visitIndex == null -> null
                else -> countedIndices.count { it < visitIndex || (arrived && it == visitIndex) }
            }
            val remaining = when {
                tracking.completed -> 0
                passed == null -> null
                else -> countedIndices.size - passed
            }
            val arrival = destination?.takeUnless { it.cancelled == true }?.let {
                val gps = tracking.gpsTimes.takeIf { tracking.source == TrackingSource.GPS }
                val resolved = JourneyTimeResolver.arrival(it, gps, nowMillis, manualDestinationArrival)
                    ?: JourneyTimeResolver.departure(it, gps, nowMillis)
                resolved?.let { time ->
                    "Ankunft Ziel: ${Instant.ofEpochMilli(time.millis).atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))} " +
                        "(${time.sourceLabel})"
                }
            }
            return TripProgressModel(
                destinationName = destinationName?.takeIf { it.isNotBlank() }
                    ?: destination?.stationName ?: "Ziel",
                nextStopName = visitIndex?.let { stops[it].stationName },
                arrivedAtCurrent = arrived,
                remainingStops = remaining,
                totalStops = countedIndices.size,
                passedStops = passed,
                completed = tracking.completed,
                approximate = tracking.source != TrackingSource.GPS,
                destinationCancelled = destination?.cancelled == true,
                arrivalText = arrival,
                sourceLabel = if (tracking.source == TrackingSource.GPS) "GPS" else "Fahrplan · ungefähr"
            )
        }

        private fun stopKey(stop: StopStation, index: Int): String = stop.uuid
            ?: "${stop.stationId ?: "unknown"}:${stop.arrivalPlanned}:${stop.departurePlanned}:$index"

    }
}
