package de.traewelling.app.data.routing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Validates the OSRM full GeoJSON response before GPS can use its geometry. */
object RoadRouteParser {
    const val MAX_BODY_BYTES = 2 * 1024 * 1024
    const val MAX_POINTS_PER_ROUTE = 5_000
    const val MAX_ALTERNATIVES = 3
    const val MAX_SNAP_METERS = 150.0
    const val MAX_ROUTE_METERS = 50_000.0

    fun parse(json: String, from: RoutePoint, to: RoutePoint, nowMillis: Long): RoadRouteGeometry? {
        if (!validPoint(from) || !validPoint(to) || nowMillis <= 0 ||
            json.length > MAX_BODY_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        val chord = distanceMeters(from, to)
        if (chord < 100.0 || chord > MAX_ROUTE_METERS) return null
        return runCatching {
            val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: return@runCatching null
            if (root.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != "Ok") {
                return@runCatching null
            }
            val waypoints = root.array("waypoints") ?: return@runCatching null
            if (waypoints.size() != 2 || !validWaypoint(waypoints[0], from) ||
                !validWaypoint(waypoints[1], to)) return@runCatching null
            val routes = root.array("routes") ?: return@runCatching null
            val alternatives = routes.take(MAX_ALTERNATIVES).mapNotNull { route ->
                parseRoute(route, from, to, chord)
            }
            if (alternatives.isEmpty()) null else RoadRouteGeometry(from, to, alternatives, nowMillis)
        }.getOrNull()
    }

    private fun validWaypoint(value: JsonElement, expected: RoutePoint): Boolean {
        if (!value.isJsonObject) return false
        val waypoint = value.asJsonObject
        val distance = waypoint.number("distance") ?: return false
        val location = waypoint.get("location")?.let(::point) ?: return false
        return distance in 0.0..MAX_SNAP_METERS && distanceMeters(expected, location) <= MAX_SNAP_METERS
    }

    private fun parseRoute(value: JsonElement, from: RoutePoint, to: RoutePoint, chord: Double): List<RoutePoint>? {
        if (!value.isJsonObject) return null
        val route = value.asJsonObject
        val declaredDistance = route.number("distance") ?: return null
        val duration = route.number("duration") ?: return null
        if (declaredDistance !in 100.0..MAX_ROUTE_METERS || duration <= 0.0 ||
            duration > 86_400.0 || declaredDistance / duration > 100.0) return null
        val geometry = route.get("geometry")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (geometry.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != "LineString") return null
        val coordinates = geometry.array("coordinates") ?: return null
        if (coordinates.size() !in 2..MAX_POINTS_PER_ROUTE) return null
        val points = ArrayList<RoutePoint>(coordinates.size())
        for (coordinate in coordinates) points.add(point(coordinate) ?: return null)
        if (distanceMeters(from, points.first()) > MAX_SNAP_METERS ||
            distanceMeters(to, points.last()) > MAX_SNAP_METERS) return null
        val total = points.zipWithNext { first, second -> distanceMeters(first, second) }.sum()
        if (!total.isFinite() || total !in 100.0..MAX_ROUTE_METERS ||
            total > chord * 5.0 + 1_000.0 || declaredDistance > chord * 5.0 + 1_000.0 ||
            abs(total - declaredDistance) > max(150.0, total * 0.10)) return null
        return points.toList()
    }

    private fun point(value: JsonElement): RoutePoint? {
        if (!value.isJsonArray || value.asJsonArray.size() != 2) return null
        val pair = value.asJsonArray
        val longitude = pair[0].number() ?: return null
        val latitude = pair[1].number() ?: return null
        return RoutePoint(latitude, longitude).takeIf(::validPoint)
    }

    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.number(name: String): Double? = get(name)?.number()
    private fun JsonElement.number(): Double? = takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
    }?.asDouble?.takeIf { it.isFinite() }

    internal fun validPoint(point: RoutePoint): Boolean = point.latitude.isFinite() &&
        point.longitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    internal fun distanceMeters(from: RoutePoint, to: RoutePoint): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val value = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
        return 6_371_000.0 * 2 * asin(sqrt(value.coerceIn(0.0, 1.0)))
    }
}
