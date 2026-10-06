package de.traewelling.app.data.routing

import com.google.gson.Gson
import de.traewelling.app.data.model.RoutePoint
import org.junit.Assert.*
import org.junit.Test

class RoadRouteParserTest {
    private val from = RoutePoint(51.43, 6.88)
    private val to = RoutePoint(51.45, 7.01)
    private val middle = RoutePoint(51.445, 6.94)
    private val path = listOf(from, middle, to)
    private val gson = Gson()

    @Test fun geoJsonUsesLongitudeLatitudeAndKeepsOrderedPublicEndpoints() {
        val parsed = requireNotNull(parse(response(listOf(route(path)))))
        assertEquals(from, parsed.from)
        assertEquals(to, parsed.to)
        assertEquals(path, parsed.alternatives.single())
        assertEquals(1234L, parsed.fetchedAtMillis)
    }

    @Test fun realMuelheimEssenResponseKeepsBothCurvedRoadAlternatives() {
        assertPublicSnapshot("muelheim-essen-osrm.json",
            RoutePoint(51.43175246, 6.88538831), RoutePoint(51.45018831, 7.0101172),
            RoutePoint(51.431794, 6.885396), RoutePoint(51.450154, 7.010129), listOf(299, 309))
    }

    @Test fun realDuisburgMuelheimResponseKeepsBothCurvedRoadAlternatives() {
        assertPublicSnapshot("duisburg-muelheim-osrm.json",
            RoutePoint(51.42804102, 6.77808449), RoutePoint(51.43175246, 6.88538831),
            RoutePoint(51.428035, 6.778191), RoutePoint(51.431794, 6.885396), listOf(315, 382))
    }

    @Test fun alternativesAreBoundedToThreeCandidates() {
        val parsed = requireNotNull(parse(response(List(5) { route(path) })))
        assertEquals(3, parsed.alternatives.size)
    }

    @Test fun invalidCandidateDoesNotDiscardAnotherValidAlternative() {
        val parsed = requireNotNull(parse(response(listOf(route(path, declaredDistance = 999.0), route(path)))))
        assertEquals(listOf(path), parsed.alternatives)
    }

    @Test fun missingOrErrorCodeAndMalformedJsonHaveNoGeometry() {
        assertNull(parse("not json"))
        assertNull(parse("{}"))
        assertNull(parse(response(listOf(route(path))).replace("\"Ok\"", "\"NoRoute\"")))
        assertNull(parse("[]"))
    }

    @Test fun bothWaypointOffsetsAndLocationsMustBeNearPublicStops() {
        assertNull(parse(response(listOf(route(path)), firstSnapDistance = 151.0)))
        assertNull(parse(response(listOf(route(path)), secondSnapDistance = 151.0)))
        assertNull(parse(response(listOf(route(path)), firstWaypoint = to)))
        assertNull(parse(response(listOf(route(path)), secondWaypoint = from)))
        assertNull(parse(response(listOf(route(path)), firstSnapDistance = -1.0)))
    }

    @Test fun routeGeometryEndpointsMustRemainInRequestedOrder() {
        assertNull(parse(response(listOf(route(path.reversed())))))
        assertNull(parse(response(listOf(route(listOf(middle, to))))))
        assertNull(parse(response(listOf(route(listOf(from, middle))))))
    }

    @Test fun missingWaypointsAndWrongGeometryTypesAreRejected() {
        val valid = response(listOf(route(path)))
        assertNull(parse(valid.replace("\"waypoints\"", "\"notWaypoints\"")))
        assertNull(parse(valid.replace("LineString", "MultiLineString")))
        assertNull(parse(valid.replace("\"distance\":0.0", "\"distance\":\"0\"")))
    }

    @Test fun reportedDistanceMustAgreeWithGeometricLength() {
        assertNull(parse(response(listOf(route(path, declaredDistance = 100.0)))))
        assertNull(parse(response(listOf(route(path, declaredDistance = 50_001.0)))))
        assertNull(parse(response(listOf(route(path, duration = 0.0)))))
        assertNull(parse(response(listOf(route(path, duration = 1.0)))))
    }

    @Test fun extremeDetoursAndLongRoutesAreRejected() {
        val shortTarget = RoutePoint(51.431, 6.88)
        val detour = listOf(from, RoutePoint(51.45, 6.88), shortTarget)
        assertNull(RoadRouteParser.parse(response(listOf(route(detour)), target = shortTarget), from, shortTarget, 1234))
        val longPath = listOf(from, RoutePoint(51.7, 6.88), RoutePoint(51.7, 7.01), to)
        assertNull(parse(response(listOf(route(longPath)))))
    }

    @Test fun invalidCoordinatesAndDegenerateSegmentsAreRejected() {
        assertNull(RoadRouteParser.parse(response(listOf(route(path))), from.copy(latitude = Double.NaN), to, 1234))
        assertNull(RoadRouteParser.parse(response(listOf(route(path))), from, to.copy(longitude = 181.0), 1234))
        assertNull(RoadRouteParser.parse(response(listOf(route(path))), from, from, 1234))
        assertNull(RoadRouteParser.parse(response(listOf(route(path))), from, to, 0))
        val invalid = response(listOf(route(path))).replace("[6.94,51.445]", "[6.94,91.0]")
        assertNull(parse(invalid))
    }

    @Test fun pointCountAndResponseBytesAreBoundedBeforeUse() {
        fun straightPath(count: Int) = List(count) { index ->
            val ratio = index.toDouble() / (count - 1)
            RoutePoint(from.latitude + (to.latitude - from.latitude) * ratio,
                from.longitude + (to.longitude - from.longitude) * ratio)
        }
        assertNotNull(parse(response(listOf(route(straightPath(RoadRouteParser.MAX_POINTS_PER_ROUTE))))))
        assertNull(parse(response(listOf(route(straightPath(RoadRouteParser.MAX_POINTS_PER_ROUTE + 1))))))
        assertNull(parse(" ".repeat(RoadRouteParser.MAX_BODY_BYTES + 1)))
        assertNull(parse("é".repeat(RoadRouteParser.MAX_BODY_BYTES / 2 + 1)))
    }

    private fun parse(json: String) = RoadRouteParser.parse(json, from, to, 1234)

    private fun assertPublicSnapshot(
        filename: String,
        origin: RoutePoint,
        destination: RoutePoint,
        snappedOrigin: RoutePoint,
        snappedDestination: RoutePoint,
        pointCounts: List<Int>
    ) {
        val json = requireNotNull(javaClass.getResourceAsStream("/routing/$filename"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = requireNotNull(RoadRouteParser.parse(json, origin, destination, 1234))
        assertEquals(origin, parsed.from)
        assertEquals(destination, parsed.to)
        assertEquals(2, parsed.alternatives.size)
        assertEquals(pointCounts, parsed.alternatives.map { it.size })
        parsed.alternatives.forEach { points ->
            assertEquals(snappedOrigin, points.first())
            assertEquals(snappedDestination, points.last())
            assertTrue(points.all { RoadRouteParser.validPoint(it) })
            val length = points.zipWithNext { first, second -> RoadRouteParser.distanceMeters(first, second) }.sum()
            assertTrue(length > RoadRouteParser.distanceMeters(origin, destination) * 1.05)
        }
    }

    private fun route(points: List<RoutePoint>, declaredDistance: Double? = null, duration: Double? = null): Map<String, Any> {
        val length = points.zipWithNext { first, second -> RoadRouteParser.distanceMeters(first, second) }.sum()
        return mapOf("distance" to (declaredDistance ?: length), "duration" to (duration ?: length / 12.0),
            "geometry" to mapOf("type" to "LineString", "coordinates" to points.map { listOf(it.longitude, it.latitude) }))
    }

    private fun response(
        routes: List<Map<String, Any>>,
        target: RoutePoint = to,
        firstSnapDistance: Double = 0.0,
        secondSnapDistance: Double = 0.0,
        firstWaypoint: RoutePoint = from,
        secondWaypoint: RoutePoint = target
    ): String = gson.toJson(mapOf("code" to "Ok", "routes" to routes,
        "waypoints" to listOf(
            mapOf("distance" to firstSnapDistance, "location" to listOf(firstWaypoint.longitude, firstWaypoint.latitude)),
            mapOf("distance" to secondSnapDistance, "location" to listOf(secondWaypoint.longitude, secondWaypoint.latitude)))))
}
