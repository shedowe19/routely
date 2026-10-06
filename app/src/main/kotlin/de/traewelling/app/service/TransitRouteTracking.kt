package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import de.traewelling.app.util.AuthSession
import java.time.Instant
import java.util.Locale

/** Builds an entire checked-in public route, never a cursor window or a device trace. */
internal object TransitRouteSelection {
    const val MAX_GEOMETRY_AGE_MILLIS = 15 * 60 * 1000L
    const val REFRESH_AGE_MILLIS = MAX_GEOMETRY_AGE_MILLIS - 60_000L
    private val railCategories = setOf("nationalexpress", "national", "regionalexp", "regional", "suburban", "subway", "tram")

    fun request(
        statusId: Int,
        checkin: CheckinInfo,
        fullStops: List<StopStation>,
        checkedInStops: List<StopStation>,
        trackingKeys: List<String>
    ): TransitRouteRequest? {
        val category = checkin.category?.trim()?.lowercase(Locale.ROOT)
        if (category !in railCategories || checkin.mode?.trim()?.equals("bus", true) == true ||
            statusId <= 0 || checkin.trip == null || checkin.trip <= 0 ||
            checkedInStops.size !in 2..256 || checkedInStops.size != trackingKeys.size ||
            trackingKeys.any(String::isBlank) || trackingKeys.distinct().size != trackingKeys.size
        ) return null
        // A repeated physical station is valid, but the selected boundary visits must be unique.
        val origins = fullStops.indices.filter { fullStops[it].matchesStopover(checkin.origin) }
        val destinations = fullStops.indices.filter { fullStops[it].matchesStopover(checkin.destination) }
        val first = origins.singleOrNull() ?: return null
        val last = destinations.singleOrNull()?.takeIf { it > first } ?: return null
        val fullRange = fullStops.subList(first, last + 1)
        if (fullRange.size != checkedInStops.size) return null
        val visits = checkedInStops.mapIndexed { index, stop ->
            if (stop.uuid != null && stop.uuid != trackingKeys[index]) return null
            val mappedVisit = visit(stop, trackingKeys[index]) ?: return null
            // Manual/realtime changes do not alter the source's route identity.
            if (visit(fullRange[index], trackingKeys[index]) != mappedVisit) return null
            mappedVisit
        }
        return TransitRouteRequest(statusId, "${checkin.trip}:${checkin.tripUuid.orEmpty()}", visits)
    }

    fun usable(request: TransitRouteRequest, geometry: TransitRouteGeometry, nowMillis: Long): Boolean {
        if (geometry.request != request || geometry.fetchedAtMillis <= 0 ||
            nowMillis - geometry.fetchedAtMillis !in 0..MAX_GEOMETRY_AGE_MILLIS || geometry.segments.isEmpty()
        ) return false
        val pairs = request.visits.filterNot { it.cancelled }.zipWithNext()
            .associate { (from, to) -> (from.key to to.key) to (from.point to to.point) }
        val segmentKeys = geometry.segments.map { it.fromKey to it.toKey }
        if (segmentKeys.distinct().size != segmentKeys.size) return false
        return geometry.segments.all { segment ->
            val endpoints = pairs[segment.fromKey to segment.toKey] ?: return@all false
            segment.source == GpsGeometrySource.TRIP_POLYLINE &&
                segment.geometry.from == endpoints.first && segment.geometry.to == endpoints.second &&
                segment.geometry.fetchedAtMillis == geometry.fetchedAtMillis &&
                segment.geometry.alternatives.size == 1 && segment.geometry.alternatives.single().let { points ->
                    points.size >= 2 && points.all(::validPoint)
                }
        }
    }

    private fun visit(stop: StopStation, key: String): TransitRouteVisit? {
        val station = stop.station ?: return null
        val point = RoutePoint(station.latitude ?: return null, station.longitude ?: return null)
            .takeIf(::validPoint) ?: return null
        val arrival = plannedTime(stop.arrivalPlanned)
        val departure = plannedTime(stop.departurePlanned)
        if ((stop.arrivalPlanned != null && arrival == null) || (stop.departurePlanned != null && departure == null)) return null
        return TransitRouteVisit(key, station.id, station.uuid, arrival, departure, point, stop.cancelled == true)
    }

    private fun plannedTime(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli().takeIf { millis -> millis > 0 } }.getOrNull()
    }

    private fun validPoint(point: RoutePoint): Boolean = point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude.isFinite() && point.longitude in -180.0..180.0
}

/** A delayed response can only fill the still-owned generation and complete public route. */
internal class TransitRouteTrackingCache {
    data class Lease(val statusId: Int, val generation: Long, val session: AuthSession, val request: TransitRouteRequest)

    var lease: Lease? = null
        private set
    private var geometry: TransitRouteGeometry? = null

    fun bind(owner: Lease?): Boolean {
        if (lease == owner) return false
        lease = owner
        geometry = null
        return true
    }

    fun adopt(owner: Lease, loaded: TransitRouteGeometry, nowMillis: Long): Boolean {
        if (lease != owner || !TransitRouteSelection.usable(owner.request, loaded, nowMillis)) return false
        geometry = loaded
        return true
    }

    fun segments(nowMillis: Long): List<GpsSegmentGeometry> {
        val owner = lease ?: return emptyList()
        return geometry?.takeIf { TransitRouteSelection.usable(owner.request, it, nowMillis) }?.segments.orEmpty()
    }

    fun needsRefresh(nowMillis: Long): Boolean {
        val owner = lease ?: return false
        val loaded = geometry ?: return true
        return !TransitRouteSelection.usable(owner.request, loaded, nowMillis) ||
            nowMillis - loaded.fetchedAtMillis >= TransitRouteSelection.REFRESH_AGE_MILLIS
    }
}
