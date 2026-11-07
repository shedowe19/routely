package de.traewelling.app.ui.components

import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.ui.screens.StopTimelineProgress
import de.traewelling.app.ui.screens.TimelinePositionSource
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos

internal enum class JourneyMapStopRole { STOP, ORIGIN, DESTINATION }

internal enum class JourneyMapRouteKind { DETAILED, PARTIAL, SCHEMATIC }

internal data class JourneyMapStop(
    val originalIndex: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val arrivalMillis: Long?,
    val departureMillis: Long?,
    val role: JourneyMapStopRole,
    val cancelled: Boolean,
)

internal data class JourneyMapRouteLeg(
    val fromOriginalIndex: Int,
    val toOriginalIndex: Int,
    val points: List<RoutePoint>,
)

internal data class JourneyMapPosition(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

internal data class JourneyMapModel(
    val stops: List<JourneyMapStop>,
    val route: List<RoutePoint>,
    val routeLegs: List<JourneyMapRouteLeg>,
    val position: JourneyMapPosition?,
    val sourceLabel: String,
    val routeKind: JourneyMapRouteKind,
)

/**
 * Builds the map from the checked-in visit range only. The marker is a timetable
 * estimate; it never claims a device or vehicle GPS position.
 */
internal fun buildJourneyMapModel(
    stops: List<StopStation>,
    progress: StopTimelineProgress,
    nowMillis: Long,
    originIndex: Int,
    destinationIndex: Int,
    geometry: TransitRouteGeometry?,
    hasServiceCursor: Boolean = false,
): JourneyMapModel? {
    if (originIndex !in stops.indices || destinationIndex !in stops.indices || destinationIndex <= originIndex) {
        return null
    }
    val range = stops.subList(originIndex, destinationIndex + 1)
    val mapStops = range.mapIndexedNotNull { offset, stop ->
        val station = stop.station ?: return@mapIndexedNotNull null
        val latitude = station.latitude ?: return@mapIndexedNotNull null
        val longitude = station.longitude ?: return@mapIndexedNotNull null
        if (!validCoordinate(latitude, longitude)) return@mapIndexedNotNull null
        JourneyMapStop(
            originalIndex = originIndex + offset,
            name = stop.stationName ?: "Unbekannte Station",
            latitude = latitude,
            longitude = longitude,
            arrivalMillis = epochMillis(stop.effectiveArrival),
            departureMillis = epochMillis(stop.effectiveDeparture),
            role = when (originIndex + offset) {
                originIndex -> JourneyMapStopRole.ORIGIN
                destinationIndex -> JourneyMapStopRole.DESTINATION
                else -> JourneyMapStopRole.STOP
            },
            cancelled = stop.cancelled == true,
        )
    }
    if (mapStops.size < 2) return null

    val assembly = geometry?.routeAssembly()
    val routeKind = when {
        assembly == null || assembly.covered == 0 || assembly.points.size < 2 -> JourneyMapRouteKind.SCHEMATIC
        assembly.complete -> JourneyMapRouteKind.DETAILED
        else -> JourneyMapRouteKind.PARTIAL
    }
    val route = assembly?.points?.takeIf {
        routeKind != JourneyMapRouteKind.SCHEMATIC && it.size >= 2
    } ?: mapStops.map { RoutePoint(it.latitude, it.longitude) }
    val routeLegs = assembly?.legs.orEmpty().map { leg ->
        JourneyMapRouteLeg(
            fromOriginalIndex = originIndex + leg.fromVisitIndex,
            toOriginalIndex = originIndex + leg.toVisitIndex,
            points = leg.points,
        )
    }

    // Cancelled visits stay visible, but must never pull the live marker onto a skipped stop.
    val positionStops = mapStops.filterNot { it.cancelled }
    val serviceCursor = hasServiceCursor &&
        (progress.currentIndex != null || progress.nextIndex != null || progress.passedThroughIndex >= 0)
    val rawPosition = when {
        progress.source == TimelinePositionSource.WAITING -> null
        progress.source == TimelinePositionSource.GPS ->
            cursorPosition(positionStops, progress, route, routeLegs, nowMillis)
        serviceCursor -> cursorPosition(positionStops, progress, route, routeLegs, nowMillis)
            ?: timetablePosition(positionStops, progress, nowMillis, route, routeLegs)
        else -> timetablePosition(positionStops, progress, nowMillis, route, routeLegs)
    }

    val currentIndex = progress.currentIndex
    val nextIndex = progress.nextIndex
    val progressLabel = when {
        progress.source == TimelinePositionSource.WAITING -> null
        currentIndex != null && currentIndex in originIndex..destinationIndex ->
            stops.getOrNull(currentIndex)?.stationName?.let { "Halt: $it" }
        nextIndex != null && nextIndex in originIndex..destinationIndex ->
            stops.getOrNull(nextIndex)?.stationName?.let { name ->
                if (progress.passedThroughIndex < 0 && nextIndex == originIndex) "Start: $name"
                else "Unterwegs nach $name"
            }
        else -> null
    }
    val position = rawPosition?.let { current ->
        progressLabel?.let { current.copy(label = it) } ?: current
    }
    return JourneyMapModel(
        stops = mapStops,
        route = route,
        routeLegs = routeLegs,
        position = position,
        sourceLabel = when (progress.source) {
            TimelinePositionSource.GPS -> "Position per GPS-Fortschritt"
            TimelinePositionSource.WAITING -> "Position wird ermittelt"
            TimelinePositionSource.TIMETABLE -> "Position aus Fahrplan geschätzt"
        },
        routeKind = routeKind,
    )
}

/** Current/next cursor position, used for GPS and for a retained timetable fallback cursor. */
private fun cursorPosition(
    stops: List<JourneyMapStop>,
    progress: StopTimelineProgress,
    route: List<RoutePoint>,
    legs: List<JourneyMapRouteLeg>,
    nowMillis: Long,
): JourneyMapPosition? {
    val current = stops.firstOrNull { it.originalIndex == progress.currentIndex }
    if (current != null) {
        return JourneyMapPosition(current.latitude, current.longitude, "Halt: ${current.name}")
    }
    val nextIndex = stops.indexOfFirst { it.originalIndex == progress.nextIndex }
    if (nextIndex < 0) {
        val last = stops.lastOrNull()
        return if (last != null && progress.passedThroughIndex >= last.originalIndex) {
            JourneyMapPosition(last.latitude, last.longitude, "Ziel: ${last.name}")
        } else {
            null
        }
    }
    val next = stops[nextIndex]
    val previous = stops.getOrNull(nextIndex - 1)
    if (previous == null) {
        return JourneyMapPosition(next.latitude, next.longitude, "Start: ${next.name}")
    }
    return between(previous, next, nowMillis, route, legs, next.name)
}

private fun timetablePosition(
    stops: List<JourneyMapStop>,
    progress: StopTimelineProgress,
    nowMillis: Long,
    route: List<RoutePoint>,
    legs: List<JourneyMapRouteLeg>,
): JourneyMapPosition? {
    val first = stops.firstOrNull() ?: return null
    val firstArrival = first.arrivalMillis ?: first.departureMillis
    if (firstArrival != null && nowMillis < firstArrival) {
        return JourneyMapPosition(first.latitude, first.longitude, "Start: ${first.name}")
    }
    // The timeline resolves overlapping dwells to the last matching visit.
    val current = stops.firstOrNull { it.originalIndex == progress.currentIndex }
    if (current != null) {
        return JourneyMapPosition(current.latitude, current.longitude, "Halt: ${current.name}")
    }
    val dwell = stops.lastOrNull { stop ->
        val arrival = stop.arrivalMillis ?: stop.departureMillis
        val departure = stop.departureMillis ?: arrival
        arrival != null && departure != null && nowMillis in arrival..departure
    }
    if (dwell != null) {
        return JourneyMapPosition(dwell.latitude, dwell.longitude, "Halt: ${dwell.name}")
    }
    for (index in 0 until stops.lastIndex) {
        val from = stops[index]
        val to = stops[index + 1]
        val start = from.departureMillis ?: from.arrivalMillis ?: continue
        val end = to.arrivalMillis ?: to.departureMillis ?: continue
        if (nowMillis in start..end) {
            return between(from, to, nowMillis, route, legs, to.name)
        }
    }
    val last = stops.last()
    return JourneyMapPosition(last.latitude, last.longitude, "Ziel: ${last.name}")
}

private fun between(
    from: JourneyMapStop,
    to: JourneyMapStop,
    nowMillis: Long,
    route: List<RoutePoint>,
    legs: List<JourneyMapRouteLeg>,
    nextName: String,
): JourneyMapPosition {
    val start = from.departureMillis ?: from.arrivalMillis
    val end = to.arrivalMillis ?: to.departureMillis
    val fraction = if (start != null && end != null && end > start) {
        ((nowMillis - start).toDouble() / (end - start).toDouble()).coerceIn(0.0, 1.0)
    } else {
        0.5
    }
    val raw = RoutePoint(
        from.latitude + (to.latitude - from.latitude) * fraction,
        from.longitude + (to.longitude - from.longitude) * fraction,
    )
    val snapped = snapToRoute(raw, pathForPair(from, to, route, legs))
    return JourneyMapPosition(snapped.latitude, snapped.longitude, "Unterwegs nach $nextName")
}

/** Only the current stop-to-stop leg may capture the marker; a looping later leg must not. */
private fun pathForPair(
    from: JourneyMapStop,
    to: JourneyMapStop,
    route: List<RoutePoint>,
    legs: List<JourneyMapRouteLeg>,
): List<RoutePoint> {
    val matched = legs
        .filter { it.fromOriginalIndex >= from.originalIndex && it.toOriginalIndex <= to.originalIndex }
        .sortedBy { it.fromOriginalIndex }
    if (matched.isEmpty()) {
        return listOf(
            RoutePoint(from.latitude, from.longitude),
            RoutePoint(to.latitude, to.longitude),
        )
    }
    val points = ArrayList<RoutePoint>()
    matched.forEach { leg ->
        leg.points.forEach { point ->
            if (points.lastOrNull() != point) points.add(point)
        }
    }
    return points.takeIf { it.size >= 2 } ?: route
}

private data class AssembledRouteLeg(
    val fromVisitIndex: Int,
    val toVisitIndex: Int,
    val points: List<RoutePoint>,
)

private data class RouteAssembly(
    val legs: List<AssembledRouteLeg>,
    val covered: Int,
    val complete: Boolean,
) {
    val points: List<RoutePoint>
        get() {
            val result = ArrayList<RoutePoint>()
            legs.forEach { leg ->
                leg.points.forEach { point ->
                    if (result.lastOrNull() != point) result.add(point)
                }
            }
            return result
        }
}

/**
 * Fills missing provider segments with straight stop-to-stop connections. A partial
 * response must not silently drop a whole leg or shrink the camera to the first leg.
 */
private fun TransitRouteGeometry.routeAssembly(): RouteAssembly? {
    // Keep each visit's original request index so cancelled stops cannot shift later legs.
    val active = request.visits.mapIndexedNotNull { index, visit ->
        if (visit.cancelled) null else index to visit
    }
    val pairs = active.zipWithNext()
    if (pairs.isEmpty()) return null
    val byPair = segments.associateBy { it.fromKey to it.toKey }
    var covered = 0
    val legs = pairs.map { (fromEntry, toEntry) ->
        val (fromIndex, from) = fromEntry
        val (toIndex, to) = toEntry
        val path = byPair[from.key to to.key]?.geometry?.alternatives?.firstOrNull()
        if (path != null && path.size >= 2) {
            covered++
            AssembledRouteLeg(fromIndex, toIndex, path)
        } else {
            AssembledRouteLeg(fromIndex, toIndex, listOf(from.point, to.point))
        }
    }
    return RouteAssembly(legs, covered, covered == pairs.size)
}

private fun snapToRoute(point: RoutePoint, route: List<RoutePoint>): RoutePoint {
    if (route.size < 2) return point
    var best = point
    var bestDistance = Double.MAX_VALUE
    for (index in 0 until route.lastIndex) {
        val projected = project(point, route[index], route[index + 1])
        val dx = (projected.longitude - point.longitude) * cos(Math.toRadians(point.latitude))
        val dy = projected.latitude - point.latitude
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = projected
        }
    }
    return best
}

private fun project(point: RoutePoint, from: RoutePoint, to: RoutePoint): RoutePoint {
    val scaleX = cos(Math.toRadians(point.latitude))
    val ax = (from.longitude - point.longitude) * scaleX
    val ay = from.latitude - point.latitude
    val bx = (to.longitude - point.longitude) * scaleX
    val by = to.latitude - point.latitude
    val dx = bx - ax
    val dy = by - ay
    val denominator = dx * dx + dy * dy
    if (denominator <= 0.0) return from
    val fraction = (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
    return RoutePoint(
        from.latitude + (to.latitude - from.latitude) * fraction,
        from.longitude + (to.longitude - from.longitude) * fraction,
    )
}

private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(abs(latitude) < 1e-9 && abs(longitude) < 1e-9)

private fun epochMillis(value: String?): Long? = value?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
}
