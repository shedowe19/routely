package de.traewelling.app.service

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.TripDetails
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** A possible service and one specific boarding visit. No device positions are stored here. */
data class RecognizableRide(
    val departure: DepartureTrip,
    val trip: TripDetails,
    val originIndex: Int,
    val fetchedAtMillis: Long
) {
    val origin: StopStation? get() = trip.stopovers?.getOrNull(originIndex)
    val id: String get() = listOf(
        departure.tripId, departure.line?.name.orEmpty(),
        origin?.uuid ?: "${origin?.stationId}:${origin?.departurePlanned}"
    ).joinToString("|")
}

data class RecognizedRide(
    val ride: RecognizableRide,
    val latestFixMillis: Long,
    val nextStationName: String,
    val progressMeters: Int,
    val sessionId: Long = 0L
) { val id: String get() = ride.id }

enum class RideRecognitionPhase { OFF, WAITING_FOR_LOCATION, SEARCHING, OBSERVING, MATCHES, ERROR }

data class RideRecognitionState(
    val phase: RideRecognitionPhase = RideRecognitionPhase.OFF,
    val message: String = "Fahrterkennung ist ausgeschaltet.",
    val candidates: List<RecognizedRide> = emptyList(),
    val sessionId: Long = 0L,
    val authSessionRevision: String? = null
)

/**
 * Conservative GPS matching against stop coordinates, not a vehicle identification claim.
 * A match needs an observed boarding station, several accurate fixes, directed travel and a
 * compatible concrete stopover time. Indistinguishable services remain separate suggestions.
 * Curves between sparse stop coordinates can prevent a match; no route shape is invented.
 */
class RideRecognitionEngine {
    private val fixes = ArrayDeque<LocationFix>()
    private val routes = linkedMapOf<String, RecognizableRide>()
    private var latestFixReliable = false
    private var latestObservedFixMillis: Long? = null

    fun updateRides(rides: List<RecognizableRide>, nowMillis: Long) {
        prune(nowMillis)
        rides.filter { it.fetchedAtMillis in (nowMillis - ROUTE_TTL_MILLIS)..nowMillis }
            .take(MAX_ROUTES).forEach { routes[it.id] = it }
        while (routes.size > MAX_ROUTES) routes.remove(routes.keys.first())
    }

    fun onLocation(fix: LocationFix, nowMillis: Long): List<RecognizedRide> {
        // Delayed batches must not rewind reliable movement or resurrect older candidates.
        if (latestObservedFixMillis?.let { fix.timeMillis <= it } == true) return matches(nowMillis)
        // A newer inaccurate fix still ends the earlier reliable observation.
        // A delayed older precise sample must not undo that loss of confidence.
        // Future timestamps are never consumed, so they cannot freeze the session.
        if (fix.timeMillis <= nowMillis) latestObservedFixMillis = fix.timeMillis
        latestFixReliable = isReliable(fix, nowMillis)
        if (!latestFixReliable) return emptyList()
        if (fixes.lastOrNull()?.let { fix.timeMillis - it.timeMillis > MAX_FIX_AGE_MILLIS } == true) {
            fixes.clear()
        }
        fixes.addLast(fix)
        prune(nowMillis)
        return matches(nowMillis)
    }

    fun matches(nowMillis: Long): List<RecognizedRide> {
        prune(nowMillis)
        if (!latestFixReliable || fixes.size < 3) return emptyList()
        val latest = fixes.lastOrNull() ?: return emptyList()
        if (!isReliable(latest, nowMillis)) return emptyList()
        return routes.values.mapNotNull { match(it, latest, nowMillis) }
            .sortedWith(compareByDescending<RecognizedRide> { it.progressMeters }.thenBy { it.id })
    }

    fun reliableLatestFix(nowMillis: Long): LocationFix? =
        fixes.lastOrNull()?.takeIf { latestFixReliable && isReliable(it, nowMillis) }

    fun removeTrips(tripIds: Set<String>) {
        routes.entries.removeAll { it.value.departure.tripId in tripIds }
    }

    fun clear() { fixes.clear(); routes.clear(); latestFixReliable = false; latestObservedFixMillis = null }

    internal val storedFixCount: Int get() = fixes.size
    internal val storedRouteCount: Int get() = routes.size

    private fun match(ride: RecognizableRide, latest: LocationFix, nowMillis: Long): RecognizedRide? {
        if (ride.departure.cancelled == true) return null
        val stops = ride.trip.stopovers.orEmpty()
        val origin = stops.getOrNull(ride.originIndex)?.takeUnless { it.cancelled == true } ?: return null
        val next = stops.drop(ride.originIndex + 1).firstOrNull { it.cancelled != true } ?: return null
        val departureMillis = parseTime(ride.departure.realWhen) ?: parseTime(origin.effectiveDeparture)
            ?: parseTime(ride.departure.plannedWhen) ?: return null
        // Real-time, when present, takes precedence. A plan alone is only a bounded hypothesis.
        if (nowMillis !in (departureMillis - 2 * MINUTE)..(departureMillis + 8 * MINUTE)) return null
        parseTime(next.effectiveArrival)?.let { if (nowMillis > it + 5 * MINUTE) return null }
        val start = origin.station ?: return null
        val finish = next.station ?: return null
        val segment = localPoint(finish, start) ?: return null
        val length = hypot(segment.first, segment.second)
        if (length < 100.0 || length > 50_000.0) return null
        val observation = fixes.toList()
        val anchorIndices = observation.indices.reversed().filter { index ->
            val fix = observation[index]
            index <= observation.size - 3 && fix.timeMillis <= latest.timeMillis - MIN_OBSERVATION_MILLIS &&
                localPoint(fix.latitude, fix.longitude, start)?.let { hypot(it.first, it.second) }?.let {
                    it <= 300.0 + fix.accuracyMeters
                } == true
        }
        return anchorIndices.firstNotNullOfOrNull { anchorIndex ->
            matchObservation(ride, next, latest, start, segment, length, observation.drop(anchorIndex))
        }
    }

    private fun matchObservation(
        ride: RecognizableRide,
        next: StopStation,
        latest: LocationFix,
        start: TrainStation,
        segment: Pair<Double, Double>,
        length: Double,
        samples: List<LocationFix>
    ): RecognizedRide? {
        if (samples.size < 3) return null
        val duration = (latest.timeMillis - samples.first().timeMillis) / 1000.0
        if (duration < MIN_OBSERVATION_MILLIS / 1000.0) return null
        val points = samples.map { fix -> localPoint(fix.latitude, fix.longitude, start) ?: return null }
        // A plausible total average must not hide an instantaneous GPS jump.
        // Accuracy remains a positional tolerance, not additional vehicle speed.
        if (samples.indices.drop(1).any { index ->
            val previous = samples[index - 1]
            val current = samples[index]
            val seconds = (current.timeMillis - previous.timeMillis) / 1000.0
            val distance = hypot(points[index].first - points[index - 1].first,
                points[index].second - points[index - 1].second)
            seconds <= 0.0 || distance > MAX_MOVEMENT_METERS_PER_SECOND * seconds +
                previous.accuracyMeters + current.accuracyMeters
        }) return null
        val projections = samples.mapIndexed { index, fix ->
            val point = points[index]
            val along = (point.first * segment.first + point.second * segment.second) / length
            val lateral = abs(point.first * segment.second - point.second * segment.first) / length
            if (lateral > max(180.0, fix.accuracyMeters * 3.0)) return null
            along
        }
        val progress = projections.last() - projections.first()
        val accuracy = samples.maxOf { it.accuracyMeters }
        if (progress < max(80.0, accuracy * 4.0) || progress / duration !in 2.8..95.0) return null
        if (projections.last() < 30.0 || projections.last() > length + 150.0) return null
        if (projections.zipWithNext().any { (first, second) -> second < first - max(35.0, accuracy * 2) }) return null
        return RecognizedRide(ride, latest.timeMillis, next.stationName ?: "nächster Halt", progress.toInt())
    }

    private fun prune(nowMillis: Long) {
        while (fixes.firstOrNull()?.let { nowMillis - it.timeMillis > HISTORY_MILLIS } == true || fixes.size > MAX_FIXES) {
            fixes.removeFirst()
        }
        routes.entries.removeAll { nowMillis - it.value.fetchedAtMillis > ROUTE_TTL_MILLIS }
    }

    companion object {
        const val MAX_FIX_AGE_MILLIS = 30_000L
        const val ROUTE_TTL_MILLIS = 5 * 60_000L
        const val MAX_ROUTES = 12
        const val MAX_FIXES = 24
        private const val HISTORY_MILLIS = 120_000L
        private const val MIN_OBSERVATION_MILLIS = 8_000L
        private const val MAX_MOVEMENT_METERS_PER_SECOND = 100.0
        private const val MINUTE = 60_000L

        fun isReliable(fix: LocationFix, nowMillis: Long): Boolean =
            fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
                fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
                fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0.0..65.0 &&
                nowMillis - fix.timeMillis in 0..MAX_FIX_AGE_MILLIS

        fun resolveOriginIndex(stops: List<StopStation>, departure: DepartureTrip, fallback: TrainStation): Int {
            val station = departure.station ?: fallback
            val matching = stops.indices.filter { stops[it].stationId == station.id && station.id != null }
            return matching.firstOrNull { index ->
                val stop = stops[index]
                sameTime(stop.departurePlanned, departure.plannedWhen) ||
                    sameTime(stop.effectiveDeparture, departure.realWhen)
            } ?: matching.singleOrNull() ?: -1
        }

        fun parseTime(time: String?): Long? = time?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
        private fun sameTime(first: String?, second: String?): Boolean =
            first != null && second != null && parseTime(first)?.let { it == parseTime(second) } == true

        private fun localPoint(station: TrainStation, origin: TrainStation): Pair<Double, Double>? {
            val lat = station.latitude ?: return null
            val lon = station.longitude ?: return null
            return localPoint(lat, lon, origin)
        }
        private fun localPoint(lat: Double, lon: Double, origin: TrainStation): Pair<Double, Double>? {
            val originLat = origin.latitude ?: return null
            val originLon = origin.longitude ?: return null
            if (!lat.isFinite() || !lon.isFinite() || !originLat.isFinite() || !originLon.isFinite()) return null
            return (lon - originLon) * 111_320.0 * cos(Math.toRadians(originLat)) to (lat - originLat) * 111_320.0
        }
    }
}
