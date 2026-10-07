package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Validated public route shapes; never a nearest-track or timetable-based visit matcher. */
internal object TrackingRouteGeometry {
    data class Path(val points: List<RoutePoint>, val lengths: List<Double>, val length: Double)
    data class Segment(val source: GpsGeometrySource, val paths: List<Path>)
    data class Projection(val fraction: Double, val length: Double, val path: Path, val across: Double)
    data class Result(val projection: Projection?, val ambiguous: Boolean = false, val beforeOrigin: Boolean = false)
    private data class RemainingVertex(val fraction: Double, val point: RoutePoint)
    private data class Candidate(
        val projection: Projection,
        val supportedEndpoint: Boolean,
        val beforeOrigin: Boolean
    )

    fun prepare(geometry: RouteGeometry?, from: TrackingStop, to: TrackingStop,
                source: GpsGeometrySource, nowMillis: Long): Segment? {
        if (geometry == null || !coordinates(from) || !coordinates(to) || geometry.fetchedAtMillis <= 0 ||
            nowMillis < geometry.fetchedAtMillis || nowMillis - geometry.fetchedAtMillis >
                (if (source == GpsGeometrySource.TRIP_POLYLINE) 900_000L else 86_400_000L) ||
            geometry.alternatives.size !in 1..(if (source == GpsGeometrySource.TRIP_POLYLINE) 1 else 3) ||
            !valid(geometry.from) || !valid(geometry.to)) return null
        val first = RoutePoint(from.latitude!!, from.longitude!!)
        val endpoint = RoutePoint(to.latitude!!, to.longitude!!)
        if (distance(geometry.from, first) > 1.0 || distance(geometry.to, endpoint) > 1.0) return null
        val rail = source == GpsGeometrySource.TRIP_POLYLINE
        val chord = distance(first, endpoint)
        val paths = geometry.alternatives.mapNotNull { points ->
            if (points.size !in 2..(if (rail) 40_000 else 5_000) || points.any { !valid(it) } ||
                distance(points.first(), first) > (if (rail) 250.0 else 150.0) ||
                distance(points.last(), endpoint) > (if (rail) 250.0 else 150.0) ||
                (rail && points.zipWithNext().any { (a, b) -> distance(a, b) > 10_000.0 })) return@mapNotNull null
            // Keep physical station arrival zones separate from provider snapping.
            val connected = buildList {
                add(first)
                points.forEach { if (distance(last(), it) > .01) add(it) }
                if (distance(last(), endpoint) > .01) add(endpoint)
            }
            val lengths = connected.zipWithNext().map { (a, b) -> distance(a, b) }
            val length = lengths.sum()
            if (connected.size < 2 || !length.isFinite() || length !in 100.0..(if (rail) 500_000.0 else 50_000.0) ||
                (!rail && length > chord * 5 + 1_000.0)) null else Path(connected, lengths, length)
        }
        return paths.takeIf { it.isNotEmpty() }?.let { Segment(source, it) }
    }

    /** Nearby, nonadjacent branches must agree in chainage; nearest alone can jump through loops. */
    fun project(path: Path, fix: LocationFix, arrivalEndpoint: Boolean = false): Result {
        var cumulative = 0.0
        val candidates = mutableListOf<Candidate>()
        val corridor = max(100.0, fix.accuracyMeters * 2)
        val end = path.points.last()
        val supportedEnd = arrivalEndpoint && distance(RoutePoint(fix.latitude, fix.longitude), end) +
            fix.accuracyMeters <= 120.0
        for (index in path.lengths.indices) {
            val from = path.points[index]
            val to = path.points[index + 1]
            val length = path.lengths[index]
            if (length <= .01) continue
            val longitudeScale = EARTH_RADIUS * cos(Math.toRadians((from.latitude + to.latitude) / 2))
            val x = Math.toRadians(to.longitude - from.longitude) * longitudeScale
            val y = Math.toRadians(to.latitude - from.latitude) * EARTH_RADIUS
            val fx = Math.toRadians(fix.longitude - from.longitude) * longitudeScale
            val fy = Math.toRadians(fix.latitude - from.latitude) * EARTH_RADIUS
            val along = (fx * x + fy * y) / (length * length)
            val clamped = along.coerceIn(0.0, 1.0)
            val across = hypot(fx - clamped * x, fy - clamped * y)
            if (across <= corridor) {
                val supportedEndpoint = !(index == 0 && along < 0.0) &&
                    !(index == path.lengths.lastIndex && along > 1.0 && !supportedEnd)
                candidates += Candidate(Projection((cumulative + clamped * length) / path.length,
                    path.length, path, across), supportedEndpoint, index == 0 && along < 0.0)
            }
            cumulative += length
        }
        if (candidates.isEmpty()) return Result(null)
        // Select against the complete physical path before discarding endpoint
        // extrapolation. Otherwise a short final edge can be discarded while
        // the preceding edge clamps the same beyond-end fix to an inner vertex.
        val nearest = candidates.minWith(compareBy<Candidate> { it.projection.across }
            .thenBy { it.supportedEndpoint })
        val projection = nearest.projection
        val plausible = candidates.filter {
            it.projection.across <= projection.across + max(10.0, fix.accuracyMeters * 2)
        }
        if (plausible.any { abs(it.projection.fraction - projection.fraction) * path.length >
                max(50.0, fix.accuracyMeters * 2) }) {
            return Result(null, ambiguous = true)
        }
        if (!nearest.supportedEndpoint) return Result(null, beforeOrigin = nearest.beforeOrigin)
        return Result(projection)
    }

    /**
     * Proves that alternatives have the same ordered future shape from their current
     * projections. Different past detours and collinear vertex density do not matter;
     * equal remaining distance alone cannot prove a shared road through a crossing.
     *
     * Compare every pair at the union of their remaining vertices. Interpolation is
     * linear between these breakpoints, so a detour cannot hide between samples.
     */
    fun sharedRemainingPath(projections: List<Projection>): Boolean {
        if (projections.size !in 2..3) return false
        val remaining = projections.map { remainingVertices(it) ?: return false }
        for (first in remaining.indices) {
            for (second in first + 1 until remaining.size) {
                if (!sameRemainingShape(remaining[first], remaining[second])) return false
            }
        }
        return true
    }

    private fun remainingVertices(projection: Projection): List<RemainingVertex>? {
        val path = projection.path
        if (!projection.fraction.isFinite() || projection.fraction !in 0.0..1.0 ||
            !projection.length.isFinite() || !path.length.isFinite() || path.length <= 0.0 ||
            abs(projection.length - path.length) > .01 || path.points.size !in 2..5_002 ||
            path.lengths.size != path.points.size - 1 || path.points.any { !valid(it) } ||
            path.lengths.any { !it.isFinite() || it <= 0.0 } ||
            abs(path.lengths.sum() - path.length) > .01
        ) return null
        val travelled = projection.fraction * path.length
        val remaining = path.length - travelled
        if (remaining <= .01) return null
        val vertices = mutableListOf<RemainingVertex>()
        var cumulative = 0.0
        for (index in path.lengths.indices) {
            val next = cumulative + path.lengths[index]
            if (vertices.isEmpty() && next > travelled) {
                val fraction = ((travelled - cumulative) / path.lengths[index]).coerceIn(0.0, 1.0)
                vertices += RemainingVertex(0.0, interpolate(path.points[index], path.points[index + 1], fraction))
            }
            if (vertices.isNotEmpty()) {
                vertices += RemainingVertex(((next - travelled) / remaining).coerceIn(0.0, 1.0),
                    path.points[index + 1])
            }
            cumulative = next
        }
        return vertices.takeIf { it.size >= 2 }
    }

    private fun sameRemainingShape(first: List<RemainingVertex>, second: List<RemainingVertex>): Boolean {
        val breakpoints = (first.map { it.fraction } + second.map { it.fraction }).distinct().sorted()
        var firstEdge = 0
        var secondEdge = 0
        for (fraction in breakpoints) {
            while (firstEdge < first.lastIndex - 1 && first[firstEdge + 1].fraction < fraction) firstEdge++
            while (secondEdge < second.lastIndex - 1 && second[secondEdge + 1].fraction < fraction) secondEdge++
            val firstPoint = interpolateAt(first, firstEdge, fraction)
            val secondPoint = interpolateAt(second, secondEdge, fraction)
            if (distance(firstPoint, secondPoint) > SHARED_REMAINING_TOLERANCE_METERS) return false
        }
        return true
    }

    private fun interpolateAt(vertices: List<RemainingVertex>, edge: Int, fraction: Double): RoutePoint {
        val from = vertices[edge]
        val to = vertices[edge + 1]
        val span = to.fraction - from.fraction
        val along = if (span > 0.0) ((fraction - from.fraction) / span).coerceIn(0.0, 1.0) else 1.0
        return interpolate(from.point, to.point, along)
    }

    private fun interpolate(from: RoutePoint, to: RoutePoint, fraction: Double): RoutePoint = RoutePoint(
        from.latitude + (to.latitude - from.latitude) * fraction,
        from.longitude + (to.longitude - from.longitude) * fraction
    )

    fun distance(a: RoutePoint, b: RoutePoint): Double = hypot(
        Math.toRadians(b.longitude - a.longitude) * EARTH_RADIUS * cos(Math.toRadians((a.latitude + b.latitude) / 2)),
        Math.toRadians(b.latitude - a.latitude) * EARTH_RADIUS)

    private fun valid(point: RoutePoint): Boolean = point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude.isFinite() && point.longitude in -180.0..180.0

    private fun coordinates(stop: TrackingStop): Boolean = stop.latitude != null && stop.longitude != null &&
        valid(RoutePoint(stop.latitude, stop.longitude))

    private const val EARTH_RADIUS = 6_371_000.0
    private const val SHARED_REMAINING_TOLERANCE_METERS = 1.0
}
