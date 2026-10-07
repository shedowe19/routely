package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.GpsGeometrySource
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/** Why fresh GPS progress cannot currently support a forecast of future times. */
enum class GpsTimeUnavailableReason {
    NO_FRESH_LOCATION, INACCURATE_LOCATION, VISIT_UNCONFIRMED, ROUTE_UNSUPPORTED,
    WAITING_AT_ORIGIN, OUTSIDE_CORRIDOR, INSUFFICIENT_MOVEMENT, UNPLAUSIBLE_MOVEMENT,
    ROUTE_GEOMETRY_UNAVAILABLE, AMBIGUOUS_ROUTE
}

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
                // A UI refresh can precede the service's baseline update. A
                // marker being added or removed is also a changed visit basis,
                // even while the other marker and station still agree.
                stop.stationId != null && stop.stationId == time.stationId &&
                    (arrival != null || departure != null) &&
                    arrival == time.plannedArrivalMillis && departure == time.plannedDepartureMillis
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
 * Between stations it prefers an ordered trip polyline when available, requires
 * a road path for SEV, otherwise uses the conservative stop corridor and scheduled travel
 * interval, never instantaneous distance/speed. Corridors are conservative: parallel routes may
 * leave future events to the original API/plan times. Confirmed actual events can
 * still be published independently while the location and ordered visit stay valid.
 * All fixes, observations and offsets live only in this process.
 */
class GpsJourneyTimeEstimator {
    private data class Baseline(
        val key: String, val stationId: Int?, val latitude: Double?, val longitude: Double?,
        val arrival: Long?, val departure: Long?, val cancelled: Boolean,
        val origin: Boolean, val destination: Boolean
    )
    private data class ArrivalCandidate(val first: LocationFix, var latest: LocationFix, var count: Int = 1)
    private data class ObservedVisit(
        val arrival: Long,
        var departureAnchor: LocationFix,
        var departure: Long? = null
    )
    private data class SegmentSample(
        val key: String, val fix: LocationFix, val fraction: Double, val segmentMeters: Double,
        val path: TrackingRouteGeometry.Path? = null, val uniqueRoadCandidate: Boolean = false
    )
    private data class Projection(
        val fraction: Double, val length: Double, val path: TrackingRouteGeometry.Path? = null,
        val across: Double = 0.0, val uniqueRoadCandidate: Boolean = false
    )
    private data class RoadShape(val from: RoutePoint, val to: RoutePoint, val alternatives: List<List<RoutePoint>>,
                                 val source: GpsGeometrySource)
    private data class RoadProjection(val projection: Projection?, val ambiguous: Boolean = false)
    private data class DepartureBasis(
        val fromKey: String, val toKey: String, val from: RoutePoint, val to: RoutePoint,
        val points: List<RoutePoint>?
    )

    private var baseline: List<Baseline>? = null
    private var highestFixTime: Long? = null
    private var lastFix: LocationFix? = null
    private var lastVisitKey: String? = null
    private var cached: GpsJourneyTimes? = null
    private var cachedHasForecast = false
    private var cachedRequiresRoadGeometry = false
    private var roadMode: GpsGeometrySource? = null
    private var geometrySegmentKey: Pair<String, String>? = null
    private var geometryShape: RoadShape? = null
    private var selectedRoadPath: TrackingRouteGeometry.Path? = null
    private var lockedRoadPath: TrackingRouteGeometry.Path? = null
    private var activeGeometrySource: GpsGeometrySource? = null
    private var preparedGeometry: RoadRouteGeometry? = null
    private var preparedSegment: TrackingRouteGeometry.Segment? = null
    private var preparedEndpoints: Pair<RoutePoint, RoutePoint>? = null
    private var forecastUnavailableReason: GpsTimeUnavailableReason? = GpsTimeUnavailableReason.NO_FRESH_LOCATION
    private val arrivals = mutableMapOf<String, ArrivalCandidate>()
    private val observed = mutableMapOf<String, ObservedVisit>()
    private val segmentSamples = ArrayDeque<SegmentSample>()
    // A close-stop cursor handover can occur while still inside the preceding
    // arrival radius. Its physical exit must remain provable by later fixes.
    private var pendingDepartureBasis: DepartureBasis? = null

    @Synchronized
    fun reset() {
        baseline = null
        highestFixTime = null
        clearLocationState()
        forecastUnavailableReason = GpsTimeUnavailableReason.NO_FRESH_LOCATION
    }

    /** Retain the consumed-fix watermark so an old cached fix cannot revive an offset. */
    @Synchronized
    fun invalidateLocation() {
        clearLocationState()
        forecastUnavailableReason = GpsTimeUnavailableReason.NO_FRESH_LOCATION
    }

    /** Observed event times may still be available while this forecast reason is non-null. */
    @Synchronized
    fun unavailableReason(): GpsTimeUnavailableReason? = forecastUnavailableReason

    /** The currently supported incoming projection, never a merely prefetched shape. */
    @Synchronized
    fun geometrySource(): GpsGeometrySource? = activeGeometrySource

    @Synchronized
    fun update(
        route: List<TrackingStop>,
        progress: TrackingProgress,
        source: TrackingSource,
        fix: LocationFix?,
        nowMillis: Long,
        segmentGeometries: List<GpsSegmentGeometry> = emptyList(),
        useRoadGeometry: Boolean = false
    ): GpsJourneyTimes? {
        val currentBaseline = route.map {
            Baseline(it.key, it.stationId, it.latitude, it.longitude, it.plannedArrivalMillis,
                it.plannedDepartureMillis, it.cancelled, it.isOrigin, it.isDestination)
        }
        if (baseline != currentBaseline) {
            clearLocationState()
            baseline = currentBaseline
            forecastUnavailableReason = GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
        }
        val index = progress.nextStopKey?.let { key ->
            route.indices.filter { route[it].key == key }.singleOrNull()
        }
        val rejectedReason = when {
            fix == null || fix.timeMillis <= 0 || nowMillis < fix.timeMillis ||
                nowMillis - fix.timeMillis > MAX_FIX_AGE_MILLIS -> GpsTimeUnavailableReason.NO_FRESH_LOCATION
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0.0..MAX_ACCURACY_METERS ->
                GpsTimeUnavailableReason.INACCURATE_LOCATION
            !reliable(fix, nowMillis) -> GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT
            source != TrackingSource.GPS || !progress.gpsEstablished || index == null || index != progress.nextIndex ->
                GpsTimeUnavailableReason.VISIT_UNCONFIRMED
            route[index].cancelled || !coordinates(route[index]) || !uniqueVisits(route) ->
                GpsTimeUnavailableReason.ROUTE_UNSUPPORTED
            else -> null
        }
        if (rejectedReason != null) {
            invalidateLocation()
            forecastUnavailableReason = rejectedReason
            return null
        }
        // The rejection checks establish both values before any observation is accepted.
        val currentFix = fix ?: return null
        val currentIndex = index ?: return null
        val stop = route[currentIndex]
        val previousIndex = (currentIndex - 1 downTo 0).firstOrNull { !route[it].cancelled }
        val previousStop = previousIndex?.let(route::get)
        val requestedSource = if (useRoadGeometry) GpsGeometrySource.ROAD_MODEL else GpsGeometrySource.TRIP_POLYLINE
        val candidateGeometry = previousStop?.let { previous ->
            segmentGeometries.filter { it.fromKey == previous.key && it.toKey == stop.key &&
                it.source == requestedSource }.singleOrNull()?.geometry
        }
        val roadSegment = previousStop?.let { validateRoad(candidateGeometry, it, stop, requestedSource, nowMillis) }
        val projectionSource = if (useRoadGeometry) GpsGeometrySource.ROAD_MODEL else roadSegment?.source
        val usesPolyline = projectionSource != null
        updateRoadBasis(projectionSource, previousStop?.let { it.key to stop.key },
            candidateGeometry.takeIf { roadSegment != null })
        if (usesPolyline && previousStop != null && roadSegment == null && cachedRequiresRoadGeometry) {
            clearPredictionState()
            forecastUnavailableReason = GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE
        }
        val highWatermark = highestFixTime
        if (highWatermark != null && currentFix.timeMillis < highWatermark) {
            invalidateLocation()
            forecastUnavailableReason = GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT
            return null
        }
        if (highWatermark == currentFix.timeMillis) {
            // Clock ticks/API refreshes do not count as new GPS movement or dwell.
            val sameFixAndVisit = lastFix == currentFix && lastVisitKey == route[currentIndex].key
            val retained = cached?.takeIf { sameFixAndVisit && nowMillis <= it.validUntilMillis }
            if (retained != null) return retained
            if (forecastUnavailableReason == null) {
                forecastUnavailableReason = GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
            }
            // Fresh actual events may outlive older prediction support; their
            // expiry is tied to this same fix, never to the advancing clock.
            return if (sameFixAndVisit) publishObservedOnly(route, currentIndex, currentFix) else null
        }
        highestFixTime = currentFix.timeMillis
        activeGeometrySource = null
        val previousFix = lastFix?.takeIf { currentFix.timeMillis - it.timeMillis in 1..MAX_FIX_AGE_MILLIS }
        if (lastFix != null && previousFix == null) clearLocationState()
        if (previousFix != null && distance(previousFix.latitude, previousFix.longitude,
                currentFix.latitude, currentFix.longitude) / ((currentFix.timeMillis - previousFix.timeMillis) / 1000.0) > MAX_TRAVEL_SPEED) {
            invalidateLocation()
            forecastUnavailableReason = GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT
            return null
        }
        val insideStation = distance(currentFix.latitude, currentFix.longitude, stop.latitude!!, stop.longitude!!) +
            currentFix.accuracyMeters <= ARRIVAL_RADIUS_METERS
        // Physical observation has no dependency on the timed forecast's plan
        // markers or 90-minute limit. It still needs a compatible fresh pair
        // on the ordered incoming section and a confirmed preceding dwell.
        val arrivalEndpoint = progress.arrivedAtCurrent && insideStation && lastVisitKey == stop.key
        val roadProjection = if (usesPolyline && roadSegment != null)
            projectRoad(roadSegment, currentFix, arrivalEndpoint) else null
        val projection = if (usesPolyline) roadProjection?.projection
            else previousStop?.let { project(it, stop, currentFix, arrivalEndpoint) }
        val compatible = previousStop != null && projection != null &&
            compatibleProgress(previousStop, stop, previousFix, currentFix, projection, arrivalEndpoint)
        if (previousStop != null && projection != null && compatible) {
            observeDeparture(previousStop, stop, previousFix, currentFix, projection)
        } else pendingDepartureBasis = null
        val observedArrival = observeArrival(stop, progress.arrivedAtCurrent, currentFix, insideStation)
        var offset: Long? = null
        var offsetFromSegment = false
        var compatiblePosition = false
        var unavailable = GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
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
                    offset = max(offset!!, currentFix.timeMillis - departure)
                }
            } else unavailable = GpsTimeUnavailableReason.ROUTE_UNSUPPORTED
            segmentSamples.clear()
        } else if (previousStop != null) {
            val departure = previousStop.plannedDepartureMillis
            val arrival = stop.plannedArrivalMillis
            val supportedRoute = coordinates(previousStop) && departure != null && arrival != null &&
                arrival - departure in MIN_TRAVEL_MILLIS..MAX_TRAVEL_MILLIS &&
                (if (usesPolyline) roadSegment != null else
                    distance(previousStop.latitude!!, previousStop.longitude!!, stop.latitude!!, stop.longitude!!) in
                        MIN_SEGMENT_METERS..MAX_SEGMENT_METERS)
            unavailable = when {
                usesPolyline && roadSegment == null -> GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE
                !supportedRoute -> GpsTimeUnavailableReason.ROUTE_UNSUPPORTED
                roadProjection?.ambiguous == true -> GpsTimeUnavailableReason.AMBIGUOUS_ROUTE
                projection == null -> GpsTimeUnavailableReason.OUTSIDE_CORRIDOR
                !compatible -> GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT
                else -> GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
            }
            if (supportedRoute && projection != null && departure != null && arrival != null && compatible) {
                if (usesPolyline && selectedRoadPath != null && selectedRoadPath != projection.path) {
                    // A different candidate road must build its own movement support.
                    // Confirmed physical arrivals/departures are independent of that choice.
                    clearPredictionState()
                }
                selectedRoadPath = projection.path
                activeGeometrySource = projectionSource
                compatiblePosition = true
                if (!progress.arrivedAtCurrent && projection.fraction in MIN_SEGMENT_FRACTION..MAX_SEGMENT_FRACTION) {
                    offset = observeSegment(stop.key, currentFix, projection)?.let { supported ->
                        currentFix.timeMillis - (departure + ((arrival - departure) * supported.fraction).roundToLong())
                    }
                    offsetFromSegment = offset != null
                } else segmentSamples.clear()
            } else {
                activeGeometrySource = null
                if (usesPolyline && !compatible) clearPredictionState() else segmentSamples.clear()
            }
        } else {
            segmentSamples.clear()
            unavailable = if (stop.isOrigin && progress.arrivedAtCurrent && insideStation)
                GpsTimeUnavailableReason.WAITING_AT_ORIGIN else GpsTimeUnavailableReason.VISIT_UNCONFIRMED
        }
        lastFix = currentFix
        lastVisitKey = stop.key
        val supportedOffset = offset
        if (supportedOffset == null) {
            // Acquisition needs a meaningful movement window; maintaining an
            // established forecast does not re-run that requirement for every
            // braking fix or station handover. Only a compatible fresh position
            // may retain it, and its original support/expiry is never extended.
            val retained = cached?.takeIf {
                cachedHasForecast && compatiblePosition && nowMillis <= it.validUntilMillis
            }
            if (retained != null) {
                // Merge new actual events without refreshing the old forecast's support or expiry.
                forecastUnavailableReason = null
                return mergeObserved(retained, observedTimes(route, currentIndex)).also { cached = it }
            }
            forecastUnavailableReason = unavailable
            return publishObservedOnly(route, currentIndex, currentFix)
        }
        if (abs(supportedOffset) > MAX_OFFSET_MILLIS) {
            forecastUnavailableReason = GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT
            return publishObservedOnly(route, currentIndex, currentFix)
        }
        val times = route.mapIndexedNotNull { visitIndex, visit ->
            if (visit.cancelled) return@mapIndexedNotNull null
            val actual = observed[visit.key]
            val forecast = visitIndex >= currentIndex
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
            cachedHasForecast = false
            forecastUnavailableReason = GpsTimeUnavailableReason.ROUTE_UNSUPPORTED
            return null
        }
        cachedHasForecast = times.any { (it.arrivalMillis != null && !it.arrivalObserved) ||
            (it.departureMillis != null && !it.departureObserved) }
        cachedRequiresRoadGeometry = cachedHasForecast && usesPolyline && offsetFromSegment
        forecastUnavailableReason = if (cachedHasForecast) null else unavailable
        return GpsJourneyTimes(currentFix.timeMillis, currentFix.timeMillis + MAX_FIX_AGE_MILLIS, times).also { cached = it }
    }

    /** Actual events do not need a straight road/rail segment or a future-time offset. */
    private fun observedTimes(route: List<TrackingStop>, currentIndex: Int): List<GpsStopTime> =
        route.mapIndexedNotNull { index, stop ->
            if (stop.cancelled || index > currentIndex) return@mapIndexedNotNull null
            val actual = observed[stop.key] ?: return@mapIndexedNotNull null
            val arrival = actual.arrival.takeIf { stop.plannedArrivalMillis != null }
            val departure = actual.departure
            if (arrival == null && departure == null) null else GpsStopTime(
                stop.key, stop.stationId, stop.plannedArrivalMillis, stop.plannedDepartureMillis,
                arrival, departure, arrivalObserved = arrival != null, departureObserved = departure != null
            )
        }

    private fun publishObservedOnly(route: List<TrackingStop>, currentIndex: Int, fix: LocationFix): GpsJourneyTimes? {
        cachedHasForecast = false
        cachedRequiresRoadGeometry = false
        val times = observedTimes(route, currentIndex)
        return times.takeIf { it.isNotEmpty() }?.let {
            GpsJourneyTimes(fix.timeMillis, fix.timeMillis + MAX_FIX_AGE_MILLIS, it)
        }.also { cached = it }
    }

    private fun mergeObserved(snapshot: GpsJourneyTimes, actualTimes: List<GpsStopTime>): GpsJourneyTimes {
        val merged = snapshot.stopTimes.associateBy { it.stopKey }.toMutableMap()
        actualTimes.forEach { actual ->
            val existing = merged[actual.stopKey]
            merged[actual.stopKey] = if (existing == null) actual else existing.copy(
                arrivalMillis = actual.arrivalMillis ?: existing.arrivalMillis,
                departureMillis = actual.departureMillis ?: existing.departureMillis,
                arrivalObserved = actual.arrivalObserved || existing.arrivalObserved,
                departureObserved = actual.departureObserved || existing.departureObserved
            )
        }
        return snapshot.copy(stopTimes = merged.values.toList())
    }

    private fun observeArrival(stop: TrackingStop, arrived: Boolean, fix: LocationFix, inside: Boolean): ObservedVisit? {
        val speed = fix.speedMetersPerSecond
        val lowSpeed = speed != null && speed.isFinite() && speed in 0.0..MAX_STATIONARY_SPEED
        observed[stop.key]?.let { actual ->
            // Keep a real inner-station reference throughout a supported dwell.
            // Subsequent moving callbacks must not replace it: their small
            // pairwise displacement may accumulate into a genuine departure.
            val anchor = actual.departureAnchor
            if (actual.departure == null && arrived && inside && (speed == null || lowSpeed) &&
                distance(anchor.latitude, anchor.longitude, fix.latitude, fix.longitude) <=
                    max(20.0, minOf(30.0, anchor.accuracyMeters, fix.accuracyMeters))) {
                actual.departureAnchor = fix
            }
            return actual
        }
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
            return ObservedVisit(arrival.first.timeMillis, arrival.latest).also { observed[stop.key] = it }
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
        val sample = SegmentSample(key, fix, projection.fraction, projection.length,
            projection.path, projection.uniqueRoadCandidate)
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
        }?.also {
            if (projection.path != null && projection.uniqueRoadCandidate) {
                val unique = segmentSamples.filter { observed ->
                    observed.path == projection.path && observed.uniqueRoadCandidate
                }
                val firstUnique = unique.firstOrNull()
                if (firstUnique != null && unique.size >= 3 &&
                    fix.timeMillis - firstUnique.fix.timeMillis >= MIN_MOVEMENT_MILLIS &&
                    (projection.fraction - firstUnique.fraction) * projection.length >=
                        max(50.0, 3.0 * max(firstUnique.fix.accuracyMeters, fix.accuracyMeters))
                ) lockedRoadPath = projection.path
            }
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
        val priorRoad = projection.path?.let { projectPath(it, previous, arrivalEndpoint) }
        if (priorRoad?.ambiguous == true) return false
        val prior = if (projection.path != null) priorRoad?.projection
            else project(from, to, previous, arrivalEndpoint)
        if (prior == null) {
            // On a genuine visit transition the last inner-station fix can lie
            // just before the next segment. It must be close to its origin.
            return departedVisit && distance(previous.latitude, previous.longitude,
                from.latitude!!, from.longitude!!) + previous.accuracyMeters <= ARRIVAL_RADIUS_METERS &&
                (projection.path == null || projection.fraction * projection.length <=
                    MAX_TRAVEL_SPEED * ((fix.timeMillis - previous.timeMillis) / 1000.0) +
                        previous.accuracyMeters + fix.accuracyMeters)
        }
        val directedMeters = (projection.fraction - prior.fraction) * projection.length
        return directedMeters >= -max(10.0, (previous.accuracyMeters + fix.accuracyMeters) / 2.0) &&
            (projection.path == null || directedMeters <=
                MAX_TRAVEL_SPEED * ((fix.timeMillis - previous.timeMillis) / 1000.0) +
                    previous.accuracyMeters + fix.accuracyMeters)
    }

    private fun observeDeparture(
        previousStop: TrackingStop, currentStop: TrackingStop, previous: LocationFix?,
        fix: LocationFix, projection: Projection
    ) {
        val actual = observed[previousStop.key] ?: run {
            pendingDepartureBasis = null
            return
        }
        if (actual.departure != null || previous == null || previousStop.plannedDepartureMillis == null ||
            projection.fraction <= 0.0) {
            pendingDepartureBasis = null
            return
        }
        val basis = DepartureBasis(previousStop.key, currentStop.key,
            RoutePoint(previousStop.latitude!!, previousStop.longitude!!),
            RoutePoint(currentStop.latitude!!, currentStop.longitude!!), projection.path?.points)
        if (lastVisitKey == previousStop.key) {
            pendingDepartureBasis = basis
        } else if (lastVisitKey != currentStop.key || pendingDepartureBasis != basis) {
            pendingDepartureBasis = null
            return
        }
        val immediatePrior = projectSamePath(previousStop, currentStop, previous, projection) ?: return
        if (projection.fraction <= immediatePrior.fraction) return
        val anchor = actual.departureAnchor
        val prior = departureAnchorProjection(previousStop, currentStop, anchor, projection) ?: return
        val movement = (projection.fraction - prior.fraction) * projection.length
        val fromPrevious = distance(fix.latitude, fix.longitude, previousStop.latitude!!, previousStop.longitude!!)
        if (movement >= max(35.0, anchor.accuracyMeters + fix.accuracyMeters) &&
            fromPrevious > ARRIVAL_RADIUS_METERS + fix.accuracyMeters) {
            // This is the time of a supported departure observation, not the
            // exact moment a vehicle's doors closed or its wheels first moved.
            actual.departure = fix.timeMillis
            pendingDepartureBasis = null
        }
    }

    private fun departureAnchorProjection(
        from: TrackingStop, to: TrackingStop, anchor: LocationFix, current: Projection
    ): Projection? {
        projectSamePath(from, to, anchor, current)?.let { return it }
        val fromLatitude = from.latitude ?: return null
        val fromLongitude = from.longitude ?: return null
        val toLatitude = to.latitude ?: return null
        val toLongitude = to.longitude ?: return null
        // The confirmed physical dwell can be just before the segment's start,
        // as with a platform centroid. Attribute only zero outgoing chainage;
        // never invent extrapolated movement or resolve a nearby route branch.
        if (distance(anchor.latitude, anchor.longitude, fromLatitude, fromLongitude) +
            anchor.accuracyMeters > ARRIVAL_RADIUS_METERS) return null
        if (current.path != null) {
            val result = TrackingRouteGeometry.project(current.path, anchor)
            if (result.ambiguous || !result.beforeOrigin) return null
        } else {
            val scale = EARTH_RADIUS_METERS * cos(Math.toRadians((fromLatitude + toLatitude) / 2))
            val x = Math.toRadians(toLongitude - fromLongitude) * scale
            val y = Math.toRadians(toLatitude - fromLatitude) * EARTH_RADIUS_METERS
            val fx = Math.toRadians(anchor.longitude - fromLongitude) * scale
            val fy = Math.toRadians(anchor.latitude - fromLatitude) * EARTH_RADIUS_METERS
            if (fx * x + fy * y >= 0.0) return null
        }
        return current.copy(fraction = 0.0, across = 0.0)
    }

    private fun clearLocationState() {
        clearPredictionState()
        lastFix = null
        lastVisitKey = null
        arrivals.clear()
        observed.clear()
        pendingDepartureBasis = null
    }

    /** A projection basis changes forecasts, never the separately confirmed stop events. */
    private fun clearPredictionState() {
        cached = null
        cachedHasForecast = false
        cachedRequiresRoadGeometry = false
        segmentSamples.clear()
        selectedRoadPath = null
        lockedRoadPath = null
        activeGeometrySource = null
    }

    private fun updateRoadBasis(source: GpsGeometrySource?, key: Pair<String, String>?, geometry: RoadRouteGeometry?) {
        val shape = geometry?.takeIf { source != null }?.let { route ->
            RoadShape(route.from, route.to, route.alternatives.map { it.toList() }, source!!)
        }
        if (source != roadMode || (key == geometrySegmentKey && shape != geometryShape)) {
            clearPredictionState()
            forecastUnavailableReason = GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT
        } else if (key != geometrySegmentKey) {
            // Ordered handovers rebuild movement without renewing the old forecast's TTL.
            segmentSamples.clear()
            selectedRoadPath = null
            lockedRoadPath = null
            activeGeometrySource = null
        }
        roadMode = source
        geometrySegmentKey = key
        geometryShape = shape
    }

    private fun validateRoad(
        geometry: RoadRouteGeometry?, from: TrackingStop, to: TrackingStop,
        source: GpsGeometrySource, nowMillis: Long
    ): TrackingRouteGeometry.Segment? {
        val ageLimit = if (source == GpsGeometrySource.TRIP_POLYLINE) 900_000L else 86_400_000L
        if (geometry == null || !coordinates(from) || !coordinates(to) || geometry.fetchedAtMillis <= 0 ||
            nowMillis - geometry.fetchedAtMillis !in 0..ageLimit) return null
        val endpoints = RoutePoint(from.latitude!!, from.longitude!!) to RoutePoint(to.latitude!!, to.longitude!!)
        // Prepared segment lengths are reused across callbacks, not recomputed per fix.
        if (preparedGeometry != geometry || preparedSegment?.source != source ||
            preparedEndpoints != endpoints || geometrySegmentKey != (from.key to to.key)) {
            preparedSegment = TrackingRouteGeometry.prepare(geometry, from, to, source, nowMillis)
            preparedGeometry = geometry
            preparedEndpoints = endpoints
        }
        return preparedSegment
    }

    private fun projectRoad(segment: TrackingRouteGeometry.Segment, fix: LocationFix, arrivalEndpoint: Boolean): RoadProjection {
        lockedRoadPath?.takeIf { it in segment.paths }?.let { locked ->
            val result = projectPath(locked, fix, arrivalEndpoint)
            if (result.projection != null) {
                return result.copy(projection = result.projection.copy(uniqueRoadCandidate = true))
            }
            clearPredictionState()
            if (result.ambiguous) return result
        }
        val results = segment.paths.map { projectPath(it, fix, arrivalEndpoint) }
        if (results.any { it.ambiguous }) return RoadProjection(null, ambiguous = true)
        val supported = results.mapNotNull { it.projection }
        if (supported.isEmpty()) return RoadProjection(null)
        val tolerance = max(50.0, fix.accuracyMeters * 2)
        val reference = supported.first()
        if (supported.any {
                abs(it.fraction - reference.fraction) * max(it.length, reference.length) > tolerance ||
                    abs(it.fraction * it.length - reference.fraction * reference.length) > tolerance
            }) return RoadProjection(null, ambiguous = true)
        val chosen = supported.firstOrNull { it.path == selectedRoadPath } ?: reference
        return RoadProjection(chosen.copy(uniqueRoadCandidate = supported.size == 1))
    }

    private fun projectPath(path: TrackingRouteGeometry.Path, fix: LocationFix,
                            arrivalEndpoint: Boolean = false): RoadProjection {
        val result = TrackingRouteGeometry.project(path, fix, arrivalEndpoint)
        return RoadProjection(result.projection?.let {
            Projection(it.fraction, it.length, it.path, it.across)
        }, result.ambiguous)
    }

    private fun projectSamePath(
        from: TrackingStop, to: TrackingStop, fix: LocationFix, current: Projection, arrivalEndpoint: Boolean = false
    ): Projection? = current.path?.let { projectPath(it, fix, arrivalEndpoint).projection }
        ?: if (current.path == null) project(from, to, fix, arrivalEndpoint) else null

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
