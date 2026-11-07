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

internal data class JourneyMapPosition(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

internal data class JourneyMapModel(
    val stops: List<JourneyMapStop>,
    val route: List<RoutePoint>,
    val position: JourneyMapPosition?,
    val sourceLabel: String,
    val detailedRoute: Boolean,
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
        val arrival = epochMillis(stop.effectiveArrival)
        val departure = epochMillis(stop.effectiveDeparture)
        JourneyMapStop(
            originalIndex = originIndex + offset,
            name = stop.stationName ?: "Unbekannte Station",
            latitude = latitude,
            longitude = longitude,
            arrivalMillis = arrival,
            departureMillis = departure,
            role = when (originIndex + offset) {
                originIndex -> JourneyMapStopRole.ORIGIN
                destinationIndex -> JourneyMapStopRole.DESTINATION
                else -> JourneyMapStopRole.STOP
            },
            cancelled = stop.cancelled == true,
        )
    }
    if (mapStops.size < 2) return null

    val detailedRoute = geometry?.polyline().orEmpty().takeIf { it.size >= 2 }
    val route = detailedRoute ?: mapStops.map { RoutePoint(it.latitude, it.longitude) }
    val rawPosition = when (progress.source) {
        TimelinePositionSource.WAITING -> null
        TimelinePositionSource.GPS -> gpsPosition(mapStops, progress, route, nowMillis)
        TimelinePositionSource.TIMETABLE -> timetablePosition(mapStops, nowMillis, route)
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
        position = position,
        sourceLabel = when (progress.source) {
            TimelinePositionSource.GPS -> "Position per GPS-Fortschritt"
            TimelinePositionSource.WAITING -> "Position wird ermittelt"
            TimelinePositionSource.TIMETABLE -> "Position aus Fahrplan geschätzt"
        },
        detailedRoute = detailedRoute != null,
    )
}

private fun gpsPosition(
    stops: List<JourneyMapStop>,
    progress: StopTimelineProgress,
    route: List<RoutePoint>,
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
    return between(previous, next, nowMillis, route, next.name)
}

private fun timetablePosition(
    stops: List<JourneyMapStop>,
    nowMillis: Long,
    route: List<RoutePoint>,
): JourneyMapPosition? {
    val first = stops.firstOrNull() ?: return null
    val firstArrival = first.arrivalMillis ?: first.departureMillis
    if (firstArrival != null && nowMillis < firstArrival) {
        return JourneyMapPosition(first.latitude, first.longitude, "Start: ${first.name}")
    }
    for (stop in stops) {
        val arrival = stop.arrivalMillis ?: stop.departureMillis
        val departure = stop.departureMillis ?: arrival
        if (arrival != null && departure != null && nowMillis in arrival..departure) {
            return JourneyMapPosition(stop.latitude, stop.longitude, "Halt: ${stop.name}")
        }
    }
    for (index in 0 until stops.lastIndex) {
        val from = stops[index]
        val to = stops[index + 1]
        val start = from.departureMillis ?: from.arrivalMillis ?: continue
        val end = to.arrivalMillis ?: to.departureMillis ?: continue
        if (nowMillis in start..end) {
            return between(from, to, nowMillis, route, to.name)
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
    val snapped = snapToRoute(raw, route)
    return JourneyMapPosition(snapped.latitude, snapped.longitude, "Unterwegs nach $nextName")
}

private fun TransitRouteGeometry.polyline(): List<RoutePoint> {
    val byPair = segments.associateBy { it.fromKey to it.toKey }
    val points = ArrayList<RoutePoint>()
    request.visits.filterNot { it.cancelled }.zipWithNext().forEach { (from, to) ->
        val segment = byPair[from.key to to.key] ?: return@forEach
        val path = segment.geometry.alternatives.firstOrNull() ?: return@forEach
        path.forEach { point ->
            if (points.lastOrNull() != point) points.add(point)
        }
    }
    return points
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
