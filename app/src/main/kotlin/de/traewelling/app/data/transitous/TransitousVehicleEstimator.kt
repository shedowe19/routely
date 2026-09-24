package de.traewelling.app.data.transitous

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Derives approximate vehicle positions from the active stop-to-stop segments. */
internal object TransitousVehicleEstimator {
    fun estimate(
        segments: List<TransitousTripSegmentDto>,
        at: Instant,
        visibleBounds: TransitousBounds,
        polylinePrecision: Int,
        maxMarkers: Int,
    ): List<TransitousVehicleMarker> {
        val markers = LinkedHashMap<String, TransitousVehicleMarker>()
        for (segment in segments) {
            val trip = segment.trips?.firstOrNull() ?: continue
            val tripId = trip.tripId?.takeIf(String::isNotBlank) ?: continue
            if (tripId in markers) continue
            val departure = segment.departure.toInstantOrNull() ?: continue
            val arrival = segment.arrival.toInstantOrNull() ?: continue
            // Future departures and past arrivals are not vehicles on the map.
            if (at < departure || at >= arrival) continue
            val durationMillis = Duration.between(departure, arrival).toMillis()
            if (durationMillis <= 0L) continue
            val path = TransitousPolyline.decode(segment.polyline, polylinePrecision)
            if (path.isEmpty()) continue
            val elapsedMillis = Duration.between(departure, at).toMillis()
            val progress = elapsedMillis.toDouble() / durationMillis
            val position = TransitousPolyline.atFraction(path, progress)
            if (!visibleBounds.contains(position)) continue

            val delayMinutes = if (segment.realTime == true) {
                val scheduledDeparture = segment.scheduledDeparture.toInstantOrNull()
                val scheduled = scheduledDeparture ?: segment.scheduledArrival.toInstantOrNull()
                scheduled?.let {
                    Duration.between(it, if (scheduledDeparture != null) departure else arrival)
                        .toMinutes().toInt()
                }
            } else {
                null
            }

            markers[tripId] = TransitousVehicleMarker(
                tripId = tripId,
                displayName = trip.displayName?.takeIf(String::isNotBlank) ?: tripId,
                mode = segment.mode ?: "TRANSIT",
                routeColor = segment.routeColor,
                position = position,
                fromName = segment.from?.name.orEmpty(),
                toName = segment.to?.name.orEmpty(),
                departure = departure,
                arrival = arrival,
                realTime = segment.realTime == true,
                delayMinutes = delayMinutes,
            )
            if (markers.size >= maxMarkers) break
        }
        return markers.values.toList()
    }

    private fun String?.toInstantOrNull(): Instant? =
        this?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** Google encoded polyline geometry, with request-selected precision (2..5). */
internal object TransitousPolyline {
    private const val MAX_POINTS = 8_192

    fun decode(encoded: String?, precision: Int): List<TransitousCoordinate> {
        if (encoded.isNullOrEmpty() || precision !in 0..6) return emptyList()
        val divisor = (1..precision).fold(1.0) { acc, _ -> acc * 10.0 }
        val coordinates = ArrayList<TransitousCoordinate>()
        var latitude = 0L
        var longitude = 0L
        var index = 0
        while (index < encoded.length && coordinates.size < MAX_POINTS) {
            val latDelta = readDelta(encoded, index) ?: return emptyList()
            index = latDelta.second
            val lonDelta = readDelta(encoded, index) ?: return emptyList()
            index = lonDelta.second
            latitude += latDelta.first
            longitude += lonDelta.first
            val point = TransitousCoordinate(latitude / divisor, longitude / divisor)
            if (!point.isValid()) return emptyList()
            coordinates.add(point)
        }
        // A truncated long polyline would place vehicles on the wrong segment.
        return if (index == encoded.length) coordinates else emptyList()
    }

    /** Interpolate by travelled polyline distance, never by point index. */
    fun atFraction(path: List<TransitousCoordinate>, fraction: Double): TransitousCoordinate {
        require(path.isNotEmpty())
        if (path.size == 1) return path.first()
        val distances = path.zipWithNext(::distanceMeters)
        val total = distances.sum()
        if (total <= 0.0) return path.first()
        var remaining = total * fraction.coerceIn(0.0, 1.0)
        distances.forEachIndexed { index, meters ->
            if (remaining <= meters && meters > 0.0) {
                val part = remaining / meters
                val from = path[index]
                val to = path[index + 1]
                return TransitousCoordinate(
                    from.latitude + (to.latitude - from.latitude) * part,
                    from.longitude + (to.longitude - from.longitude) * part,
                )
            }
            remaining -= meters
        }
        return path.last()
    }

    private fun readDelta(polyline: String, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var value = 0L
        do {
            if (index >= polyline.length || shift > 35) return null
            val next = polyline[index++].code - 63
            if (next !in 0..63) return null
            value = value or ((next and 31).toLong() shl shift)
            shift += 5
            if (next < 32) break
        } while (true)
        val delta = if ((value and 1L) == 0L) value shr 1 else (value shr 1).inv()
        return delta to index
    }

    private fun distanceMeters(a: TransitousCoordinate, b: TransitousCoordinate): Double {
        val latitudeDifference = Math.toRadians(b.latitude - a.latitude)
        val longitudeDifference = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(latitudeDifference / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(longitudeDifference / 2).pow(2)
        return 2.0 * 6_371_000.0 * asin(sqrt(min(1.0, haversine)))
    }
}
