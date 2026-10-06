package de.traewelling.app.data.routing

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Native status lines can contain station chords. Only uniquely bound curved sections are used. */
object TransitRouteParser {
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    const val MAX_POINTS = 40_000
    const val MAX_VISITS = 256
    const val MAX_FULL_METERS = 3_000_000.0
    const val MAX_SEGMENT_METERS = 500_000.0
    const val MAX_EDGE_METERS = 10_000.0
    const val MAX_STOP_SNAP_METERS = 250.0
    const val STOP_DISTANCE_WINDOW_METERS = 15.0
    private const val MAX_STOP_CANDIDATES = 16
    private const val CANDIDATE_CLUSTER_METERS = 50.0
    private const val CURVE_EVIDENCE_METERS = 15.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    internal fun validRequest(request: TransitRouteRequest): Boolean =
        request.statusId > 0 && request.tripIdentity.isNotBlank() && request.tripIdentity.length <= 1_024 &&
            request.visits.size in 2..MAX_VISITS && request.visits.map { it.key }.distinct().size == request.visits.size &&
            request.visits.all { it.key.isNotBlank() && it.key.length <= 512 && RoadRouteParser.validPoint(it.point) } &&
            !request.visits.first().cancelled && !request.visits.last().cancelled

    fun parse(json: String, request: TransitRouteRequest, nowMillis: Long): TransitRouteGeometry? {
        if (!validRequest(request) || nowMillis <= 0 || json.length > MAX_BODY_BYTES ||
            json.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        return runCatching {
            val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
            val data = root.obj("data") ?: return@runCatching null
            if (data.string("type") != "FeatureCollection") return@runCatching null
            val features = data.get("features")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@runCatching null
            if (features.size() != 1 || !features[0].isJsonObject) return@runCatching null
            val feature = features[0].asJsonObject
            if (feature.string("type") != "Feature" ||
                feature.obj("properties")?.get("statusId")?.let(::integer) != request.statusId) return@runCatching null
            val geometry = feature.obj("geometry") ?: return@runCatching null
            if (geometry.string("type") != "LineString") return@runCatching null
            val coordinates = geometry.get("coordinates")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@runCatching null
            if (coordinates.size() !in 3..MAX_POINTS) return@runCatching null
            val points = ArrayList<RoutePoint>(coordinates.size())
            for (coordinate in coordinates) {
                val point = point(coordinate) ?: return@runCatching null
                if (points.isEmpty() || RoadRouteParser.distanceMeters(points.last(), point) >= 0.01) points.add(point)
            }
            if (points.size < 3) return@runCatching null
            if (RoadRouteParser.distanceMeters(request.visits.first().point, points.first()) > MAX_STOP_SNAP_METERS ||
                RoadRouteParser.distanceMeters(request.visits.last().point, points.last()) > MAX_STOP_SNAP_METERS) return@runCatching null
            val chainages = DoubleArray(points.size)
            for (index in 1 until points.size) {
                val edge = RoadRouteParser.distanceMeters(points[index - 1], points[index])
                if (!edge.isFinite() || edge > MAX_EDGE_METERS) return@runCatching null
                chainages[index] = chainages[index - 1] + edge
            }
            if (chainages.last() !in 100.0..MAX_FULL_METERS) return@runCatching null
            val candidates = request.visits.mapIndexed { index, visit ->
                val found = findCandidates(visit.point, points, chainages) ?: return@runCatching null
                val eligible = found.filter { candidate ->
                    (index != 0 || candidate.chainage <= MAX_STOP_SNAP_METERS) &&
                        (index != request.visits.lastIndex || chainages.last() - candidate.chainage <= MAX_STOP_SNAP_METERS)
                }.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val nearest = eligible.minOf { it.distance }
                eligible.filter { it.distance <= nearest + STOP_DISTANCE_WINDOW_METERS }
            }
            val bindings = uniqueBinding(candidates) ?: return@runCatching null
            val active = request.visits.indices.filter { !request.visits[it].cancelled }
            val segments = active.zipWithNext().mapNotNull { (fromIndex, toIndex) ->
                val from = bindings[fromIndex]
                val to = bindings[toIndex]
                val length = to.chainage - from.chainage
                if (length !in 100.0..MAX_SEGMENT_METERS) return@mapNotNull null
                // A bend formed solely by a cancelled station's chord is not route evidence.
                if ((fromIndex until toIndex).any { index ->
                        !hasCurve(slice(bindings[index], bindings[index + 1], points, chainages))
                    }) return@mapNotNull null
                val path = slice(from, to, points, chainages)
                if (path.size < 3) return@mapNotNull null
                GpsSegmentGeometry(
                    request.visits[fromIndex].key,
                    request.visits[toIndex].key,
                    RouteGeometry(request.visits[fromIndex].point, request.visits[toIndex].point,
                        listOf(path), nowMillis),
                    GpsGeometrySource.TRIP_POLYLINE
                )
            }
            if (segments.isEmpty()) null else TransitRouteGeometry(request, segments, nowMillis)
        }.getOrNull()
    }

    private data class Projection(val point: RoutePoint, val chainage: Double, val distance: Double)

    /** Local minima are distinct possible visits; nearby vertices of one minimum are one candidate. */
    private fun findCandidates(stop: RoutePoint, points: List<RoutePoint>, chainages: DoubleArray): List<Projection>? {
        val result = ArrayList<Projection>()
        var before: Projection? = null
        var previous: Projection? = null
        fun addMinimum(current: Projection, previousDistance: Double, nextDistance: Double): Boolean {
            if (current.distance > MAX_STOP_SNAP_METERS || current.distance > previousDistance + 0.001 ||
                current.distance > nextDistance + 0.001) return true
            val last = result.lastOrNull()
            if (last != null && current.chainage - last.chainage <= CANDIDATE_CLUSTER_METERS) {
                if (current.distance < last.distance) result[result.lastIndex] = current
            } else result.add(current)
            return result.size <= MAX_STOP_CANDIDATES
        }
        for (index in 0 until points.lastIndex) {
            val projected = project(stop, points[index], points[index + 1], chainages[index], chainages[index + 1])
            previous?.let { if (!addMinimum(it, before?.distance ?: Double.POSITIVE_INFINITY, projected.distance)) return null }
            before = previous
            previous = projected
        }
        previous?.let { if (!addMinimum(it, before?.distance ?: Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)) return null }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Count complete ordered assignments, capped at two: greedy nearest can select the wrong loop. */
    private fun uniqueBinding(candidates: List<List<Projection>>): List<Projection>? {
        val counts = candidates.map { IntArray(it.size) }
        val predecessors = candidates.map { IntArray(it.size) { -1 } }
        counts.first().fill(1)
        for (visit in 1 until candidates.size) {
            for (current in candidates[visit].indices) {
                for (previous in candidates[visit - 1].indices) {
                    if (candidates[visit][current].chainage <= candidates[visit - 1][previous].chainage + 1.0 ||
                        counts[visit - 1][previous] == 0) continue
                    if (counts[visit][current] == 0 && counts[visit - 1][previous] == 1) {
                        predecessors[visit][current] = previous
                    } else predecessors[visit][current] = -1
                    counts[visit][current] = minOf(2, counts[visit][current] + counts[visit - 1][previous])
                }
            }
        }
        if (counts.last().sum() != 1) return null
        var candidate = counts.last().indexOf(1)
        val result = ArrayList<Projection>(candidates.size)
        for (visit in candidates.indices.reversed()) {
            result.add(candidates[visit][candidate])
            if (visit > 0) candidate = predecessors[visit][candidate].takeIf { it >= 0 } ?: return null
        }
        return result.asReversed()
    }

    private fun slice(from: Projection, to: Projection, points: List<RoutePoint>, chainages: DoubleArray): List<RoutePoint> {
        val result = ArrayList<RoutePoint>()
        result.add(from.point)
        for (index in points.indices) {
            if (chainages[index] > from.chainage + 0.01 && chainages[index] < to.chainage - 0.01) result.add(points[index])
        }
        if (RoadRouteParser.distanceMeters(result.last(), to.point) >= 0.01) result.add(to.point)
        return result.toList()
    }

    private fun hasCurve(path: List<RoutePoint>): Boolean {
        if (path.size < 3) return false
        return path.subList(1, path.lastIndex).any { point ->
            project(point, path.first(), path.last(), 0.0, 1.0).distance >= CURVE_EVIDENCE_METERS &&
                greatCircleCrossTrackMeters(point, path.first(), path.last()) >= CURVE_EVIDENCE_METERS
        }
    }

    /** Densifying a spherical station chord is still a chord, even when it bends in lon/lat. */
    private fun greatCircleCrossTrackMeters(point: RoutePoint, first: RoutePoint, last: RoutePoint): Double {
        if (RoadRouteParser.distanceMeters(first, last) < 1.0) return RoadRouteParser.distanceMeters(first, point)
        val angularDistance = RoadRouteParser.distanceMeters(first, point) / EARTH_RADIUS_METERS
        fun bearing(to: RoutePoint): Double {
            val radians = Math.PI / 180.0
            val latitude1 = first.latitude * radians
            val latitude2 = to.latitude * radians
            val longitude = longitudeDelta(to.longitude - first.longitude) * radians
            return atan2(sin(longitude) * cos(latitude2),
                cos(latitude1) * sin(latitude2) - sin(latitude1) * cos(latitude2) * cos(longitude))
        }
        return abs(asin((sin(angularDistance) * sin(bearing(point) - bearing(last))).coerceIn(-1.0, 1.0))) * EARTH_RADIUS_METERS
    }

    private fun project(stop: RoutePoint, first: RoutePoint, last: RoutePoint,
                        startChainage: Double, endChainage: Double): Projection {
        val radians = Math.PI / 180.0
        val scaleX = EARTH_RADIUS_METERS * cos(stop.latitude * radians) * radians
        val scaleY = EARTH_RADIUS_METERS * radians
        val ax = longitudeDelta(first.longitude - stop.longitude) * scaleX
        val ay = (first.latitude - stop.latitude) * scaleY
        val bx = longitudeDelta(last.longitude - stop.longitude) * scaleX
        val by = (last.latitude - stop.latitude) * scaleY
        val dx = bx - ax
        val dy = by - ay
        val denominator = dx * dx + dy * dy
        val fraction = if (denominator <= 0.0) 0.0 else (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
        val longitude = longitudeDelta(first.longitude + longitudeDelta(last.longitude - first.longitude) * fraction)
        val projected = RoutePoint(first.latitude + (last.latitude - first.latitude) * fraction, longitude)
        return Projection(projected, startChainage + (endChainage - startChainage) * fraction,
            hypot(ax + dx * fraction, ay + dy * fraction))
    }

    private fun longitudeDelta(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    private fun point(value: JsonElement): RoutePoint? {
        if (!value.isJsonArray || value.asJsonArray.size() != 2) return null
        val longitude = number(value.asJsonArray[0]) ?: return null
        val latitude = number(value.asJsonArray[1]) ?: return null
        return RoutePoint(latitude, longitude).takeIf(RoadRouteParser::validPoint)
    }
    private fun number(value: JsonElement): Double? = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asDouble?.takeIf(Double::isFinite)
    private fun integer(value: JsonElement): Int? = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toIntOrNull()
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
