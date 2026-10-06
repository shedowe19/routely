package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.sev.SevStopResolver

/** Public physical stop coordinates and ordered visit keys, never a device location. */
internal data class RoadSegmentRequest(
    val fromKey: String,
    val toKey: String,
    val from: RoutePoint,
    val to: RoutePoint
)

/** Keeps station-centre fallbacks and another visit's geometry out of SEV forecasts. */
internal object RoadRouteSelection {
    const val MAX_GEOMETRY_AGE_MILLIS = 24 * 60 * 60 * 1000L

    fun allPairs(
        checkin: CheckinInfo,
        stops: List<StopStation>,
        trackingKeys: List<String>,
        sevStops: Map<String, SevStopInfo>
    ): List<RoadSegmentRequest> {
        if (!SevStopResolver.isReplacementBus(checkin) || stops.size != trackingKeys.size ||
            trackingKeys.any(String::isBlank) || trackingKeys.distinct().size != trackingKeys.size
        ) return emptyList()
        val visits = stops.indices.filter { stops[it].cancelled != true }
        if (visits.map { SevStopResolver.visitKey(stops[it]) }.distinct().size != visits.size) return emptyList()
        return visits.zipWithNext().mapNotNull { (first, second) ->
            val from = verifiedPoint(stops[first], sevStops) ?: return@mapNotNull null
            val to = verifiedPoint(stops[second], sevStops) ?: return@mapNotNull null
            if (from == to) return@mapNotNull null
            RoadSegmentRequest(trackingKeys[first], trackingKeys[second], from, to)
        }
    }

    fun aroundCursor(
        pairs: List<RoadSegmentRequest>,
        cursorKey: String?
    ): List<RoadSegmentRequest> {
        if (cursorKey == null) return emptyList()
        val incoming = pairs.singleOrNull { it.toKey == cursorKey }
        val outgoing = pairs.singleOrNull { it.fromKey == cursorKey }
        // At the origin there is no incoming leg, so preload the following leg too.
        return if (incoming != null) listOfNotNull(incoming, outgoing)
        else listOfNotNull(outgoing, outgoing?.let { current -> pairs.singleOrNull { it.fromKey == current.toKey } })
    }

    fun usable(request: RoadSegmentRequest, geometry: RoadRouteGeometry, nowMillis: Long): Boolean =
        request.from == geometry.from && request.to == geometry.to && geometry.fetchedAtMillis > 0 &&
            nowMillis - geometry.fetchedAtMillis in 0..MAX_GEOMETRY_AGE_MILLIS &&
            geometry.alternatives.isNotEmpty() && geometry.alternatives.all { points ->
                points.size >= 2 && points.all(::validPoint)
            }

    private fun verifiedPoint(stop: StopStation, sevStops: Map<String, SevStopInfo>): RoutePoint? {
        val info = sevStops[SevStopResolver.visitKey(stop)]?.takeIf { it.hasCoordinates } ?: return null
        return RoutePoint(info.latitude ?: return null, info.longitude ?: return null).takeIf(::validPoint)
    }

    private fun validPoint(point: RoutePoint): Boolean = point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude.isFinite() && point.longitude in -180.0..180.0
}
