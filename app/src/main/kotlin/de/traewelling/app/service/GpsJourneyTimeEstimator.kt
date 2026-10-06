package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/** Local observations and forecasts for a specific ordered station visit. */
data class GpsStopTime(
    val stopKey: String,
    val stationId: Int?,
    val plannedArrivalMillis: Long?,
    val plannedDepartureMillis: Long?,
    val arrivalMillis: Long?,
    val departureMillis: Long?,
    val arrivalObserved: Boolean = false,
    val departureObserved: Boolean = false
)

/** Ephemeral GPS information. It must never become an API realtime field or a persisted override. */
data class GpsJourneyTimes(
    val updatedAtMillis: Long,
    val validUntilMillis: Long,
    val stopTimes: List<GpsStopTime>
) {
    /** A station ID or an array index alone cannot distinguish repeated visits. */
    fun timeFor(stop: StopStation, nowMillis: Long): GpsStopTime? {
        if (updatedAtMillis <= 0 || nowMillis < updatedAtMillis ||
            nowMillis > validUntilMillis || nowMillis - updatedAtMillis > GpsJourneyTimeEstimator.MAX_FIX_AGE_MILLIS ||
            stop.cancelled == true
        ) return null
        val arrival = parseMillis(stop.arrivalPlanned)
        val departure = parseMillis(stop.departurePlanned)
        val matches = stopTimes.filter { time ->
            // Engine fallback keys contain colons; real stopover UUIDs do not.
            if (stop.uuid != null && !time.stopKey.contains(':')) {
                stop.uuid == time.stopKey && arrival == time.plannedArrivalMillis &&
                    departure == time.plannedDepartureMillis
            } else {
                val commonArrival = arrival != null && time.plannedArrivalMillis != null
                val commonDeparture = departure != null && time.plannedDepartureMillis != null
                stop.stationId != null && stop.stationId == time.stationId &&
                    (commonArrival || commonDeparture) &&
                    (!commonArrival || arrival == time.plannedArrivalMillis) &&
                    (!commonDeparture || departure == time.plannedDepartureMillis)
            }
        }
        return matches.singleOrNull()?.takeIf {
            (it.arrivalMillis != null || it.departureMillis != null) &&
                (it.arrivalMillis == null || it.arrivalMillis > 0) &&
                (it.departureMillis == null || it.departureMillis > 0)
        }
    }
}

/**
 * Derives a local schedule offset from an already GPS-established ordered visit.
 * Between stations it uses supported progress along a plausible stop-to-stop
 * corridor and the scheduled travel interval, never instantaneous distance/speed.
 * Straight corridors are deliberately conservative: bends and parallel routes may
 * return null and let the caller use the original API/plan times instead.
 * All fixes, observations and offsets live only in this process.
 */
class GpsJourneyTimeEstimator {
    private data class Baseline(
        val key: String, val stationId: Int?, val latitude: Double?, val longitude: Double?,
        val arrival: Long?, val departure: Long?, val cancelled: Boolean,
        val origin: Boolean, val destination: Boolean
    )
    private data class ArrivalCandidate(val first: LocationFix, var latest: LocationFix, var count: Int = 1)
    private data class ObservedVisit(val arrival: Long, var departure: Long? = null)
    private data class SegmentSample(val key: String, val fix: LocationFix, val fraction: Double, val segmentMeters: Double)
    private data class Projection(val fraction: Double, val length: Double)

    private var baseline: List<Baseline>? = null
    private var highestFixTime: Long? = null
    private var lastFix: LocationFix? = null
    private var lastVisitKey: String? = null
    private var cached: GpsJourneyTimes? = null
    private val arrivals = mutableMapOf<String, ArrivalCandidate>()
    private val observed = mutableMapOf<String, ObservedVisit>()
    private val segmentSamples = ArrayDeque<SegmentSample>()

    @Synchronized
    fun reset() {
        baseline = null
        highestFixTime = null
        clearLocationState()
    }

    /** Retain the consumed-fix watermark so an old cached fix cannot revive an offset. */
    @Synchronized
    fun invalidateLocation() {
        clearLocationState()
    }

    @Synchronized
    fun update(
        route: List<TrackingStop>,
        progress: TrackingProgress,
        source: TrackingSource,
        fix: LocationFix?,
        nowMillis: Long
    ): GpsJourneyTimes? {
        val currentBaseline = route.map {
            Baseline(it.key, it.stationId, it.latitude, it.longitude, it.plannedArrivalMillis,
                it.plannedDepartureMillis, it.cancelled, it.isOrigin, it.isDestination)
        }
        if (baseline != currentBaseline) {
            clearLocationState()
            baseline = currentBaseline
        }
        val index = progress.nextStopKey?.let { key ->
            route.indices.filter { route[it].key == key }.singleOrNull()
        }
        if (source != TrackingSource.GPS || !progress.gpsEstablished || fix == null ||
            !reliable(fix, nowMillis) || index == null || index != progress.nextIndex ||
            route[index].cancelled || !coordinates(route[index]) || !uniqueVisits(route)
        ) {
            invalidateLocation()
            return null
        }
        val highWatermark = highestFixTime
        if (highWatermark != null && fix.timeMillis < highWatermark) {
            invalidateLocation()
            return null
        }
        if (highWatermark == fix.timeMillis) {
            // Clock ticks/API refreshes do not count as new GPS movement or dwell.
            return cached?.takeIf { lastFix == fix && lastVisitKey == route[index].key &&
                nowMillis <= it.validUntilMillis }
        }
        highestFixTime = fix.timeMillis
        val previousFix = lastFix?.takeIf { fix.timeMillis - it.timeMillis in 1..MAX_FIX_AGE_MILLIS }
        if (lastFix != null && previousFix == null) clearLocationState()
        if (previousFix != null && distance(previousFix.latitude, previousFix.longitude,
                fix.latitude, fix.longitude) / ((fix.timeMillis - previousFix.timeMillis) / 1000.0) > MAX_TRAVEL_SPEED) {
            invalidateLocation()
            return null
        }
        val stop = route[index]
        val previousIndex = (index - 1 downTo 0).firstOrNull { !route[it].cancelled }
        val previousStop = previousIndex?.let(route::get)
        val insideStation = distance(fix.latitude, fix.longitude, stop.latitude!!, stop.longitude!!) +
            fix.accuracyMeters <= ARRIVAL_RADIUS_METERS
        val observedArrival = observeArrival(stop, progress.arrivedAtCurrent, fix, insideStation)
        var offset: Long? = null
        var compatiblePosition = false
        if (observedArrival != null && insideStation && progress.arrivedAtCurrent && !stop.isOrigin) {
            // A departure-only visit establishes no measured arrival offset.
            // Origin waiting is excluded above: its departure needs movement.
            val plan = stop.plannedArrivalMillis
            if (plan != null) {
                // An early arrival does not prove an early departure. Services
                // may wait until their advertised departure before continuing.
                offset = max(0L, observedArrival.arrival - plan)
                // The first arrival is frozen, while a train still dwelling beyond
                // its expected departure must push the forecast for later stops.
                stop.plannedDepartureMillis?.let { departure ->
                    offset = max(offset!!, fix.timeMillis - departure)
                }
            }
            segmentSamples.clear()
        } else if (previousStop != null) {
            // An arriving train can stop just beyond the stop centroid. Only an
            // established approach to this same visit may use the inner arrival
            // zone while its slow-fix/dwell observation is being confirmed.
            val arrivalEndpoint = progress.arrivedAtCurrent && insideStation && lastVisitKey == stop.key
            val projection = project(previousStop, stop, fix, arrivalEndpoint)
            val departure = previousStop.plannedDepartureMillis
            val arrival = stop.plannedArrivalMillis
            if (projection != null && departure != null && arrival != null &&
                arrival - departure in MIN_TRAVEL_MILLIS..MAX_TRAVEL_MILLIS &&
                compatibleProgress(previousStop, stop, previousFix, fix, projection, arrivalEndpoint)
            ) {
                compatiblePosition = true
                // A station exit can precede the forecast window on a long
                // segment; capture that event at the engine's visit transition.
                observeDeparture(previousStop, stop, previousFix, fix, projection)
                if (!progress.arrivedAtCurrent && projection.fraction in MIN_SEGMENT_FRACTION..MAX_SEGMENT_FRACTION) {
                    offset = observeSegment(stop.key, fix, projection)?.let { supported ->
                        fix.timeMillis - (departure + ((arrival - departure) * supported.fraction).roundToLong())
                    }
                } else segmentSamples.clear()
            } else {
                segmentSamples.clear()
            }
        } else {
            segmentSamples.clear()
        }
        lastFix = fix
        lastVisitKey = stop.key
        val supportedOffset = offset
        if (supportedOffset == null) {
            // Acquisition needs a meaningful movement window; maintaining an
            // established forecast does not re-run that requirement for every
            // braking fix or station handover. Only a compatible fresh position
            // may retain it, and its original support/expiry is never extended.
            return cached?.takeIf { compatiblePosition && nowMillis <= it.validUntilMillis }
                .also { cached = it }
        }
        if (abs(supportedOffset) > MAX_OFFSET_MILLIS) {
            cached = null
            return null
        }
        val times = route.mapIndexedNotNull { visitIndex, visit ->
            if (visit.cancelled) return@mapIndexedNotNull null
            val actual = observed[visit.key]
            val forecast = visitIndex >= index
            val arrival = actual?.arrival?.takeIf { visit.plannedArrivalMillis != null }
                ?: visit.plannedArrivalMillis?.takeIf { forecast }?.let { shifted(it, supportedOffset) }
            val departure = actual?.departure
                ?: visit.plannedDepartureMillis?.takeIf { forecast }?.let { shifted(it, supportedOffset) }
            if (arrival == null && departure == null) null else GpsStopTime(
                visit.key, visit.stationId, visit.plannedArrivalMillis, visit.plannedDepartureMillis,
                arrival, departure, actual != null && visit.plannedArrivalMillis != null,
                actual?.departure != null
            )
        }
        if (times.isEmpty()) {
            cached = null
            return null
        }
        return GpsJourneyTimes(fix.timeMillis, fix.timeMillis + MAX_FIX_AGE_MILLIS, times).also { cached = it }
    }

    private fun observeArrival(stop: TrackingStop, arrived: Boolean, fix: LocationFix, inside: Boolean): ObservedVisit? {
        observed[stop.key]?.let { return it }
        val speed = fix.speedMetersPerSecond
        val lowSpeed = speed != null && speed.isFinite() && speed in 0.0..MAX_STATIONARY_SPEED
        if (!inside || (speed != null && !lowSpeed)) {
            arrivals.remove(stop.key)
            return null
        }
        val candidate = arrivals[stop.key]
        val stable = candidate != null && fix.timeMillis - candidate.latest.timeMillis <= MAX_FIX_AGE_MILLIS &&
            distance(candidate.first.latitude, candidate.first.longitude, fix.latitude, fix.longitude) <=
            max(20.0, minOf(30.0, candidate.first.accuracyMeters, fix.accuracyMeters))
        val arrival = if (stable) candidate!!.apply { latest = fix; count++ }
            else ArrivalCandidate(fix, fix).also { arrivals[stop.key] = it }
        val firstSpeed = arrival.first.speedMetersPerSecond
        val twoSlowFixes = lowSpeed && firstSpeed != null && firstSpeed.isFinite() &&
            firstSpeed in 0.0..MAX_STATIONARY_SPEED && arrival.count >= 2
        val dwell = arrival.count >= 2 && fix.timeMillis - arrival.first.timeMillis >= MIN_DWELL_MILLIS
        if (arrived && (twoSlowFixes || dwell)) {
            return ObservedVisit(arrival.first.timeMillis).also { observed[stop.key] = it }
        }
        return null
    }

    private fun observeSegment(key: String, fix: LocationFix, projection: Projection): SegmentSample? {
        val previous = segmentSamples.lastOrNull()
        if (previous != null && previous.key != key) segmentSamples.clear()
        val preceding = segmentSamples.lastOrNull()
        if (preceding != null && (
                fix.timeMillis - preceding.fix.timeMillis > MAX_FIX_AGE_MILLIS ||
                (preceding.fraction - projection.fraction) * projection.length >
                max(10.0, (preceding.fix.accuracyMeters + fix.accuracyMeters) / 2.0)
            )) {
            segmentSamples.clear()
            cached = null
            return null
        }
        val sample = SegmentSample(key, fix, projection.fraction, projection.length)
        // Keep evidence by elapsed time instead of eight callback occurrences.
        // A 1 Hz location provider previously could never span the required 8 s.
        // Downsampling bounds memory even for much faster callbacks; the newest
        // fix still participates in the directed-progress calculation below.
        if (segmentSamples.lastOrNull()?.let {
                fix.timeMillis - it.fix.timeMillis >= MIN_SAMPLE_INTERVAL_MILLIS
            } != false) segmentSamples.addLast(sample)
        while (segmentSamples.size > MAX_SEGMENT_SAMPLES || segmentSamples.firstOrNull()?.let {
                fix.timeMillis - it.fix.timeMillis > MAX_FIX_AGE_MILLIS
            } == true) segmentSamples.removeFirst()
        val first = segmentSamples.first()
        val movement = (sample.fraction - first.fraction) * projection.length
        return sample.takeIf {
            segmentSamples.size >= 3 && fix.timeMillis - first.fix.timeMillis >= MIN_MOVEMENT_MILLIS &&
                movement >= max(50.0, 3.0 * max(first.fix.accuracyMeters, fix.accuracyMeters))
        }
    }

    private fun compatibleProgress(
        from: TrackingStop, to: TrackingStop, previous: LocationFix?,
        fix: LocationFix, projection: Projection, arrivalEndpoint: Boolean = false
    ): Boolean {
        if (previous == null) return true
        val sameVisit = lastVisitKey == to.key
        val departedVisit = lastVisitKey == from.key
        if (!sameVisit && !departedVisit) return false
        val prior = project(from, to, previous, arrivalEndpoint)
        if (prior == null) {
            // On a genuine visit transition the last inner-station fix can lie
            // just before the next segment. It must be close to its origin.
            return departedVisit && distance(previous.latitude, previous.longitude,
                from.latitude!!, from.longitude!!) + previous.accuracyMeters <= ARRIVAL_RADIUS_METERS
        }
        return (prior.fraction - projection.fraction) * projection.length <=
            max(10.0, (previous.accuracyMeters + fix.accuracyMeters) / 2.0)
    }

    private fun observeDeparture(
        previousStop: TrackingStop, currentStop: TrackingStop, previous: LocationFix?,
        fix: LocationFix, projection: Projection
    ) {
        val actual = observed[previousStop.key] ?: return
        if (actual.departure != null || previous == null || lastVisitKey != previousStop.key ||
            previousStop.plannedDepartureMillis == null || projection.fraction <= 0.0) return
        val prior = project(previousStop, currentStop, previous) ?: return
        val movement = (projection.fraction - prior.fraction) * projection.length
        val fromPrevious = distance(fix.latitude, fix.longitude, previousStop.latitude!!, previousStop.longitude!!)
        if (movement >= max(35.0, previous.accuracyMeters + fix.accuracyMeters) &&
            fromPrevious > ARRIVAL_RADIUS_METERS + fix.accuracyMeters) {
            // This is the time of a supported departure observation, not the
            // exact moment a vehicle's doors closed or its wheels first moved.
            actual.departure = fix.timeMillis
        }
    }

    private fun clearLocationState() {
        cached = null
        lastFix = null
        lastVisitKey = null
        arrivals.clear()
        observed.clear()
        segmentSamples.clear()
    }

    private fun uniqueVisits(route: List<TrackingStop>): Boolean {
        if (route.any { (it.plannedArrivalMillis != null && it.plannedArrivalMillis <= 0) ||
                (it.plannedDepartureMillis != null && it.plannedDepartureMillis <= 0) ||
                (it.plannedArrivalMillis != null && it.plannedDepartureMillis != null &&
                    it.plannedDepartureMillis < it.plannedArrivalMillis) }) return false
        if (route.any { it.key.isBlank() } || route.map { it.key }.distinct().size != route.size) return false
        val fallback = route.filter { it.key.contains(':') && !it.cancelled }
        return fallback.none { stop -> stop.stationId == null ||
            (stop.plannedArrivalMillis == null && stop.plannedDepartureMillis == null) ||
            fallback.count { other -> other.stationId == stop.stationId &&
                other.plannedArrivalMillis == stop.plannedArrivalMillis &&
                other.plannedDepartureMillis == stop.plannedDepartureMillis } > 1 }
    }

    private fun project(from: TrackingStop, to: TrackingStop, fix: LocationFix, arrivalEndpoint: Boolean = false): Projection? {
        if (!coordinates(from) || !coordinates(to)) return null
        val fromLatitude = from.latitude ?: return null
        val fromLongitude = from.longitude ?: return null
        val toLatitude = to.latitude ?: return null
        val toLongitude = to.longitude ?: return null
        val meanLatitude = Math.toRadians((fromLatitude + toLatitude) / 2)
        val longitudeScale = EARTH_RADIUS_METERS * cos(meanLatitude)
        val x = Math.toRadians(toLongitude - fromLongitude) * longitudeScale
        val y = Math.toRadians(toLatitude - fromLatitude) * EARTH_RADIUS_METERS
        val length = hypot(x, y)
        if (!length.isFinite() || length !in MIN_SEGMENT_METERS..MAX_SEGMENT_METERS) return null
        val fx = Math.toRadians(fix.longitude - fromLongitude) * longitudeScale
        val fy = Math.toRadians(fix.latitude - fromLatitude) * EARTH_RADIUS_METERS
        val fraction = (fx * x + fy * y) / (length * length)
        val across = abs(fx * y - fy * x) / length
        val supportedEndpoint = arrivalEndpoint && distance(fix.latitude, fix.longitude,
            toLatitude, toLongitude) + fix.accuracyMeters <= ARRIVAL_RADIUS_METERS
        if (!fraction.isFinite() || fraction < 0.0 || (fraction > 1.0 && !supportedEndpoint) ||
            across > max(100.0, fix.accuracyMeters * 2)) return null
        return Projection(fraction, length)
    }

    companion object {
        const val MAX_FIX_AGE_MILLIS = 30_000L
        private const val MAX_ACCURACY_METERS = 75.0
        private const val ARRIVAL_RADIUS_METERS = 120.0
        private const val MAX_STATIONARY_SPEED = 3.0
        private const val MIN_DWELL_MILLIS = 8_000L
        private const val MIN_MOVEMENT_MILLIS = 8_000L
        private const val MIN_SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val MAX_SEGMENT_SAMPLES = 32
        private const val MIN_TRAVEL_MILLIS = 15_000L
        private const val MAX_TRAVEL_MILLIS = 5_400_000L
        private const val MAX_OFFSET_MILLIS = 21_600_000L
        private const val MIN_SEGMENT_METERS = 100.0
        private const val MAX_SEGMENT_METERS = 50_000.0
        private const val MIN_SEGMENT_FRACTION = 0.05
        private const val MAX_SEGMENT_FRACTION = 0.98
        private const val MAX_TRAVEL_SPEED = 100.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private fun reliable(fix: LocationFix, nowMillis: Long): Boolean =
            validCoordinates(fix.latitude, fix.longitude) && fix.accuracyMeters.isFinite() &&
                fix.accuracyMeters in 0.0..MAX_ACCURACY_METERS && fix.timeMillis > 0 &&
                nowMillis >= fix.timeMillis && nowMillis - fix.timeMillis <= MAX_FIX_AGE_MILLIS &&
                (fix.speedMetersPerSecond == null ||
                    (fix.speedMetersPerSecond.isFinite() && fix.speedMetersPerSecond in 0.0..MAX_TRAVEL_SPEED))

        private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

        private fun coordinates(stop: TrackingStop): Boolean = stop.latitude != null && stop.longitude != null &&
            validCoordinates(stop.latitude, stop.longitude)

        private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val meanLatitude = Math.toRadians((lat1 + lat2) / 2)
            return hypot(Math.toRadians(lon2 - lon1) * EARTH_RADIUS_METERS * cos(meanLatitude),
                Math.toRadians(lat2 - lat1) * EARTH_RADIUS_METERS)
        }

        private fun shifted(planned: Long, offset: Long): Long? =
            runCatching { Math.addExact(planned, offset) }.getOrNull()?.takeIf { it > 0 }
    }
}

private fun parseMillis(value: String?): Long? = value?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()?.takeIf { time -> time > 0 }
}
