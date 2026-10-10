package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import java.time.Instant

enum class JourneyTimeSource { GPS_OBSERVED, GPS_ESTIMATE, API_REALTIME, TIMETABLE, MANUAL }

/** A display-only time. This value must never replace an API stopover or edit input. */
data class JourneyTime(
    val millis: Long,
    val source: JourneyTimeSource,
    val plannedMillis: Long?,
    val providerLabel: String? = null
) {
    val sourceLabel: String get() = when (source) {
        JourneyTimeSource.GPS_OBSERVED -> "GPS beobachtet"
        JourneyTimeSource.GPS_ESTIMATE -> "GPS-Schätzung"
        JourneyTimeSource.API_REALTIME -> providerLabel ?: "API-Echtzeit"
        JourneyTimeSource.TIMETABLE -> "Fahrplan"
        JourneyTimeSource.MANUAL -> "Manuell"
    }

    val delayMinutes: Long? get() = plannedMillis?.let { (millis - it) / 60_000L }
}

/** Resolve each event independently so a missing GPS departure can still use API data. */
object JourneyTimeResolver {
    /**
     * Historical/foreign timelines have no local service cursor. Preserve the
     * existing manual clock cursor without allowing GPS forecasts to advance it.
     * The returned projection is never stored or supplied to the edit dialog.
     */
    fun manualTimelineStops(
        stops: List<StopStation>,
        origin: StopStation?,
        destination: StopStation?,
        manualDeparture: String?,
        manualArrival: String?
    ): List<StopStation> {
        val departure = parseMillis(manualDeparture)?.let { Instant.ofEpochMilli(it).toString() }
        val arrival = parseMillis(manualArrival)?.let { Instant.ofEpochMilli(it).toString() }
        if (departure == null && arrival == null) return stops
        val originIndex = stops.indices.filter { stops[it].matchesStopover(origin) }.singleOrNull()
        val destinationIndex = stops.indices.filter { stops[it].matchesStopover(destination) }.singleOrNull()
        return stops.mapIndexed { index, stop ->
            stop.copy(
                departureReal = if (index == originIndex && departure != null) departure else stop.departureReal,
                arrivalReal = if (index == destinationIndex && arrival != null) arrival else stop.arrivalReal
            )
        }
    }

    fun arrival(
        stop: StopStation?,
        gpsTimes: GpsJourneyTimes?,
        nowMillis: Long,
        manualTime: String? = null
    ): JourneyTime? = resolve(stop, gpsTimes, nowMillis, manualTime, arrival = true)

    fun departure(
        stop: StopStation?,
        gpsTimes: GpsJourneyTimes?,
        nowMillis: Long,
        manualTime: String? = null
    ): JourneyTime? = resolve(stop, gpsTimes, nowMillis, manualTime, arrival = false)

    private fun resolve(
        stop: StopStation?,
        gpsTimes: GpsJourneyTimes?,
        nowMillis: Long,
        manualTime: String?,
        arrival: Boolean
    ): JourneyTime? {
        stop ?: return null
        val planned = parseMillis(if (arrival) stop.arrivalPlanned else stop.departurePlanned)
        val gps = gpsTimes?.timeFor(stop, nowMillis)
        val gpsMillis = (if (arrival) gps?.arrivalMillis else gps?.departureMillis)?.takeIf { it > 0 }
        if (gpsMillis != null) {
            val observed = if (arrival) gps?.arrivalObserved == true else gps?.departureObserved == true
            return JourneyTime(gpsMillis,
                if (observed) JourneyTimeSource.GPS_OBSERVED else JourneyTimeSource.GPS_ESTIMATE, planned)
        }
        parseMillis(manualTime)?.let { return JourneyTime(it, JourneyTimeSource.MANUAL, planned) }
        parseMillis(if (arrival) stop.arrivalReal else stop.departureReal)?.let {
            val readInfo = if (arrival) stop.arrivalRealtimeInfo else stop.departureRealtimeInfo
            return JourneyTime(it, JourneyTimeSource.API_REALTIME, planned, readInfo?.displayLabel(nowMillis))
        }
        return planned?.let { JourneyTime(it, JourneyTimeSource.TIMETABLE, planned) }
    }

    private fun parseMillis(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }
}
