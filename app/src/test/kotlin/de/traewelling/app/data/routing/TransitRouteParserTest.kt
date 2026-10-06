package de.traewelling.app.data.routing

import com.google.gson.JsonParser
import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TransitRouteParserTest {
    private val start = point(0.0, 0.0)
    private val middle = point(1_000.0, 0.0)
    private val end = point(2_000.0, 0.0)
    private val curved = listOf(start, point(500.0, 200.0), middle, point(1_500.0, -250.0), end)
    private val request = request(start, middle, end)

    @Test fun curvedNativeLineIsBoundToExactOrderedVisitsAndPhysicalEndpoints() {
        val result = requireNotNull(parse(curved))
        assertEquals(request, result.request)
        assertEquals(1234L, result.fetchedAtMillis)
        assertEquals(listOf("visit-0" to "visit-1", "visit-1" to "visit-2"),
            result.segments.map { it.fromKey to it.toKey })
        result.segments.forEach { assertEquals(GpsGeometrySource.TRIP_POLYLINE, it.source) }
        assertEquals(start, result.segments.first().geometry.from)
        assertEquals(middle, result.segments.first().geometry.to)
    }

    @Test fun realPublicIceGeometryIncludesLargeCurvedLegsBeyondRoadLimits() {
        val request = fixtureRequest("public-ice-929", 9258278, "8975982")
        val result = requireNotNull(TransitRouteParser.parse(resource("public-ice-929-polyline.json"), request, 1234))
        assertEquals(4, result.segments.size)
        val lengths = result.segments.map { segment ->
            segment.geometry.alternatives.single().zipWithNext { first, last -> RoadRouteParser.distanceMeters(first, last) }.sum()
        }
        assertTrue(lengths.count { it > RoadRouteParser.MAX_ROUTE_METERS } >= 2)
        assertTrue(lengths.sum() in 226_000.0..227_000.0)
    }

    @Test fun realPublicTramUsesNearestDistanceWindowBeforeGlobalBinding() {
        val request = fixtureRequest("public-tram-7", 9258322, "8978390")
        val result = requireNotNull(TransitRouteParser.parse(resource("public-tram-7-polyline.json"), request, 1234))
        assertEquals(11, result.segments.size)
        val keys = request.visits.map { it.key }
        result.segments.forEach { segment ->
            assertEquals(keys.indexOf(segment.fromKey) + 1, keys.indexOf(segment.toKey))
        }
    }

    @Test fun absentWrongAndDuplicateStatusFeaturesAreRejected() {
        assertNull(TransitRouteParser.parse("{\"data\":{\"type\":\"FeatureCollection\",\"features\":[]}}", request, 1234))
        assertNull(TransitRouteParser.parse(json(curved, statusId = 43), request, 1234))
        val root = JsonParser.parseString(json(curved)).asJsonObject
        val features = root.getAsJsonObject("data").getAsJsonArray("features")
        features.add(features[0].deepCopy())
        assertNull(TransitRouteParser.parse(root.toString(), request, 1234))
        assertNull(TransitRouteParser.parse(json(curved).replace("\"statusId\":42", "\"statusId\":\"42\""), request, 1234))
    }

    @Test fun lineStringAndNumericLonLatAreRequired() {
        assertNull(TransitRouteParser.parse(json(curved).replace("LineString", "MultiLineString"), request, 1234))
        assertNull(TransitRouteParser.parse(json(curved).replace("[0.0,0.0]", "[\"0\",0.0]"), request, 1234))
        assertNull(parse(curved.map { it.copy(latitude = 91.0) }))
        assertNull(TransitRouteParser.parse("not-json", request, 1234))
    }

    @Test fun reversedOrMissingEndpointAndMissingIntermediateStopAreRejected() {
        assertNull(parse(curved.reversed()))
        assertNull(parse(curved.drop(1)))
        val missing = request.copy(visits = request.visits.toMutableList().also {
            it[1] = it[1].copy(point = point(1_000.0, 600.0))
        })
        assertNull(parse(curved, missing))
    }

    @Test fun duplicateVisitKeysAndInvalidRequestsAreRejected() {
        assertNull(parse(curved, request.copy(statusId = 0)))
        assertNull(parse(curved, request.copy(tripIdentity = "")))
        assertNull(parse(curved, request.copy(visits = request.visits.map { it.copy(key = "same") })))
        assertNull(parse(curved, request.copy(visits = listOf(request.visits.first()))))
        assertNull(parse(curved, request.copy(visits = request.visits.map { it.copy(point = start.copy(latitude = Double.NaN)) })))
        assertNull(TransitRouteParser.parse(json(curved), request, 0))
    }

    @Test fun intermediateVisitsCannotBeGreedilyMappedInReversedOrder() {
        val stop1 = point(800.0, 0.0)
        val stop2 = point(1_600.0, 0.0)
        val last = point(2_400.0, 0.0)
        val path = listOf(start, point(400.0, 200.0), stop1, point(1_200.0, -200.0), stop2,
            point(2_000.0, 200.0), last)
        assertNull(parse(path, request(start, stop2, stop1, last)))
    }

    @Test fun pureAndDensifiedStationChordsAreNotAdvertisedAsTripShapes() {
        assertNull(parse(listOf(start, middle, end)))
        assertNull(parse(listOf(start, point(500.0, 0.0), middle, point(1_500.0, 0.0), end)))
        assertNull(parse(listOf(start, end), request(start, end)))
    }

    @Test fun densifiedGreatCircleIsNotMistakenForRailCurveThroughEarthCurvature() {
        val first = RoutePoint(52.0, 8.0)
        val last = RoutePoint(52.0, 10.0)
        val angle = RoadRouteParser.distanceMeters(first, last) / 6_371_000.0
        val radians = Math.PI / 180.0
        val path = (0..40).map { index ->
            val fraction = index / 40.0
            val a = kotlin.math.sin((1.0 - fraction) * angle) / kotlin.math.sin(angle)
            val b = kotlin.math.sin(fraction * angle) / kotlin.math.sin(angle)
            val x = a * kotlin.math.cos(first.latitude * radians) * kotlin.math.cos(first.longitude * radians) +
                b * kotlin.math.cos(last.latitude * radians) * kotlin.math.cos(last.longitude * radians)
            val y = a * kotlin.math.cos(first.latitude * radians) * kotlin.math.sin(first.longitude * radians) +
                b * kotlin.math.cos(last.latitude * radians) * kotlin.math.sin(last.longitude * radians)
            val z = a * kotlin.math.sin(first.latitude * radians) + b * kotlin.math.sin(last.latitude * radians)
            RoutePoint(kotlin.math.atan2(z, kotlin.math.hypot(x, y)) / radians, kotlin.math.atan2(y, x) / radians)
        }
        // This looks like a substantial curve in lon/lat, but is exactly a station great-circle chord.
        assertTrue(path[20].latitude - first.latitude > 0.001)
        assertNull(parse(path, request(first, last)))
    }

    @Test fun bendsOnlyAtCancelledStationDoNotCreateCurveEvidence() {
        val cancelled = point(1_000.0, 500.0)
        val route = request(start, cancelled, end).copy(visits = request(start, cancelled, end).visits.mapIndexed { index, visit ->
            visit.copy(cancelled = index == 1)
        })
        assertNull(parse(listOf(start, point(500.0, 250.0), cancelled, point(1_500.0, 250.0), end), route))
    }

    @Test fun actualCurveAcrossCancelledVisitIsBundledBetweenActiveVisits() {
        val cancelled = point(1_000.0, 500.0)
        val route = request(start, cancelled, end).copy(visits = request(start, cancelled, end).visits.mapIndexed { index, visit ->
            visit.copy(cancelled = index == 1)
        })
        val result = requireNotNull(parse(listOf(start, point(500.0, 500.0), cancelled, point(1_500.0, 500.0), end), route))
        assertEquals(1, result.segments.size)
        assertEquals("visit-0", result.segments.single().fromKey)
        assertEquals("visit-2", result.segments.single().toKey)
    }

    @Test fun genuineCurveBeforeCancelledVisitDoesNotValidateFollowingMissingSection() {
        val cancelled = point(1_000.0, 500.0)
        val route = request(start, cancelled, end).copy(visits = request(start, cancelled, end).visits.mapIndexed { index, visit ->
            visit.copy(cancelled = index == 1)
        })
        assertNull(parse(listOf(start, point(500.0, 500.0), cancelled, point(1_500.0, 250.0), end), route))
    }

    @Test fun curvedSectionDoesNotValidateAdjacentStationChord() {
        val result = requireNotNull(parse(listOf(start, point(500.0, 200.0), middle, point(1_500.0, 0.0), end)))
        assertEquals(1, result.segments.size)
        assertEquals("visit-0", result.segments.single().fromKey)
        assertEquals("visit-1", result.segments.single().toKey)
    }

    @Test fun equallyNearLoopVisitsRemainAmbiguous() {
        val loop = listOf(start, middle, point(1_000.0, 500.0), point(1_500.0, 500.0), middle,
            point(2_000.0, -500.0), point(3_000.0, 0.0))
        assertNull(parse(loop, request(start, middle, loop.last())))
    }

    @Test fun globallyOrderedIntermediateVisitCanDisambiguateRepeatedStation() {
        val upper = point(1_000.0, 500.0)
        val last = point(3_000.0, 0.0)
        val loop = listOf(start, middle, upper, point(1_500.0, 500.0), middle, point(2_000.0, -500.0), last)
        val route = request(start, middle, upper, middle, last).copy(visits = request(start, middle, upper, middle, last).visits.mapIndexed { index, visit ->
            if (index == 1 || index == 3) visit.copy(stationId = 2, stationUuid = "repeated-station") else visit
        })
        val result = requireNotNull(parse(loop, route))
        assertTrue(result.segments.any { it.fromKey == "visit-2" && it.toKey == "visit-3" })
    }

    @Test fun competingNearlyEqualParallelVisitsCannotChooseAnArbitraryLeg() {
        val low = point(1_000.0, 0.0)
        val near = point(1_000.0, 10.0)
        val path = listOf(start, low, point(1_500.0, 500.0), near, point(2_000.0, -500.0), point(3_000.0, 0.0))
        assertNull(parse(path, request(start, low, path.last())))
    }

    @Test fun crossingAtTheSamePublicStationCoordinateRemainsAmbiguous() {
        val first = point(-1_000.0, -1_000.0)
        val crossing = point(0.0, 0.0)
        val last = point(2_000.0, -1_000.0)
        val path = listOf(first, crossing, point(1_000.0, 1_000.0), point(-1_000.0, 1_000.0),
            point(1_000.0, -1_000.0), last)
        assertNull(parse(path, request(first, crossing, last)))
    }

    @Test fun closerButOutOfOrderCandidateDoesNotMakeWorseVisitArbitrarilyValid() {
        val firstPass = point(1_000.0, 40.0)
        val requiredMiddle = point(1_500.0, 500.0)
        val last = point(3_000.0, 0.0)
        val path = listOf(start, firstPass, requiredMiddle, middle, point(2_000.0, -500.0), last)
        assertNull(parse(path, request(start, middle, requiredMiddle, last)))
    }

    @Test fun oversizedBodyVertexCountAndLongUncoveredEdgeAreRejected() {
        val root = JsonParser.parseString(json(curved)).asJsonObject
        root.addProperty("padding", "a".repeat(TransitRouteParser.MAX_BODY_BYTES))
        assertNull(TransitRouteParser.parse(root.toString(), request, 1234))
        assertNull(parse(curved + List(TransitRouteParser.MAX_POINTS) { end }))
        val far = point(20_000.0, 0.0)
        assertNull(parse(listOf(start, point(500.0, 200.0), far), request(start, far)))
    }

    @Test fun utf8ByteLimitAppliesEvenWhenCharacterCountFits() {
        val root = JsonParser.parseString(json(curved)).asJsonObject
        root.addProperty("padding", "ä".repeat(TransitRouteParser.MAX_BODY_BYTES / 2 + 1))
        val json = root.toString()
        assertTrue(json.length < TransitRouteParser.MAX_BODY_BYTES)
        assertNull(TransitRouteParser.parse(json, request, 1234))
    }

    @Test fun curvedLegBeyondFiveHundredKilometersIsRejected() {
        val path = (0..120).map { index -> point(index * 5_000.0, kotlin.math.sin(index * Math.PI / 120) * 10_000.0) }
        assertNull(parse(path, request(path.first(), path.last())))
    }

    @Test fun totalShapeBeyondThreeThousandKilometersIsRejectedDespiteValidIndividualLegs() {
        val stops = (0..33).map { point(it * 100_000.0, 0.0) }
        val path = (0 until 33).flatMap { leg ->
            (0 until 20).map { index -> point(leg * 100_000.0 + index * 5_000.0,
                kotlin.math.sin(index * Math.PI / 20) * 10_000.0) }
        } + stops.last()
        assertNull(parse(path, request(*stops.toTypedArray())))
    }

    private fun parse(points: List<RoutePoint>, request: TransitRouteRequest = this.request) =
        TransitRouteParser.parse(json(points), request, 1234)

    private fun point(x: Double, y: Double) = RoutePoint(y / 111_195.0, x / 111_195.0)
    private fun request(vararg points: RoutePoint) = TransitRouteRequest(42, "trip-identity", points.mapIndexed { index, point ->
        TransitRouteVisit("visit-$index", index + 1, "station-$index", index * 60_000L, index * 60_000L, point)
    })
    private fun json(points: List<RoutePoint>, statusId: Int = 42): String =
        """{"data":{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"statusId":$statusId},"geometry":{"type":"LineString","coordinates":[${points.joinToString { "[${it.longitude},${it.latitude}]" }}]}}]}}"""

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/routing/transit/$name")).readText()
    private fun fixtureRequest(name: String, statusId: Int, tripIdentity: String): TransitRouteRequest {
        val stops = JsonParser.parseString(resource("$name-stops.json")).asJsonObject.getAsJsonArray("data")
        return TransitRouteRequest(statusId, tripIdentity, stops.map { element ->
            val stop = element.asJsonObject
            val station = stop.getAsJsonObject("station")
            fun planned(key: String): Long? = stop.get(key)?.takeUnless { it.isJsonNull }?.asString?.let { Instant.parse(it).toEpochMilli() }
            TransitRouteVisit(stop.get("uuid").asString, station.get("id").asInt, station.get("uuid").asString,
                planned("arrivalPlanned"), planned("departurePlanned"),
                RoutePoint(station.get("latitude").asDouble, station.get("longitude").asDouble),
                stop.get("cancelled")?.asBoolean == true)
        })
    }
}
