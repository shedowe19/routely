package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.*
import org.junit.Test

class RoadRouteSelectionTest {
    private val stops = listOf(stop("a", 1), stop("b", 2), stop("c", 3), stop("d", 4))
    private val keys = stops.map { it.uuid!! }
    private val verified = stops.associate { it.uuid!! to info(51.0 + it.stationId!! / 100.0) }
    private val now = 2 * RoadRouteSelection.MAX_GEOMETRY_AGE_MILLIS

    @Test fun onlyReplacementRegionalBusesRequestGeometry() {
        assertEquals(3, pairs().size)
        assertTrue(pairs(checkin().copy(category = "regional", mode = "train")).isEmpty())
        assertTrue(pairs(checkin().copy(lineName = "SB 1")).isEmpty())
        assertTrue(pairs(checkin().copy(lineName = "RE")).isEmpty())
    }

    @Test fun originPreloadsOnlyFirstTwoAdjacentSegments() {
        val selected = RoadRouteSelection.aroundCursor(pairs(), "a")
        assertEquals(listOf("a" to "b", "b" to "c"), selected.map { it.fromKey to it.toKey })
    }

    @Test fun intermediateCursorLoadsIncomingAndFollowingSegment() {
        val selected = RoadRouteSelection.aroundCursor(pairs(), "c")
        assertEquals(listOf("b" to "c", "c" to "d"), selected.map { it.fromKey to it.toKey })
        assertEquals(1, RoadRouteSelection.aroundCursor(pairs(), "d").size)
        assertTrue(RoadRouteSelection.aroundCursor(pairs(), null).isEmpty())
        assertTrue(RoadRouteSelection.aroundCursor(pairs(), "unrelated").isEmpty())
    }

    @Test fun cancelledVisitsAreSkippedBeforePhysicalPairsAreBuilt() {
        val route = stops.map { if (it.uuid == "b") it.copy(cancelled = true) else it }
        val selected = RoadRouteSelection.aroundCursor(pairs(route = route), "c")
        assertEquals(listOf("a" to "c", "c" to "d"), selected.map { it.fromKey to it.toKey })
    }

    @Test fun missingOrUnresolvedSevPointNeverUsesApiStationCentre() {
        val missing = verified - "b"
        assertEquals(listOf("c" to "d"), pairs(points = missing).map { it.fromKey to it.toKey })
        val guidanceOnly = verified + ("b" to info(null))
        assertEquals(listOf("c" to "d"), pairs(points = guidanceOnly).map { it.fromKey to it.toKey })
        val halfPoint = verified + ("b" to verified.getValue("b").copy(longitude = null))
        assertEquals(listOf("c" to "d"), pairs(points = halfPoint).map { it.fromKey to it.toKey })
    }

    @Test fun malformedPhysicalPointsAreNotSentToRouting() {
        val invalid = verified + ("b" to info(Double.NaN))
        assertEquals(1, pairs(points = invalid).size)
        assertTrue(pairs(points = emptyMap()).isEmpty())
    }

    @Test fun repeatedStationVisitsKeepTheirDistinctPhysicalEndpoints() {
        val repeated = stops.toMutableList().apply { this[2] = this[2].copy(station = stops[0].station) }
        val selected = RoadRouteSelection.aroundCursor(pairs(route = repeated), "c")
        assertEquals("c", selected[0].toKey)
        assertEquals(verified.getValue("c").latitude!!, selected[0].to.latitude, 0.0)
        assertNotEquals(selected[0].to, RoutePoint(verified.getValue("a").latitude!!, 7.0))
    }

    @Test fun ambiguousOrMismatchedVisitKeysCannotSelectAnyRoadGeometry() {
        assertTrue(RoadRouteSelection.allPairs(checkin(), stops, listOf("a", "b", "b", "d"), verified).isEmpty())
        assertTrue(RoadRouteSelection.allPairs(checkin(), stops, keys.dropLast(1), verified).isEmpty())
        assertTrue(RoadRouteSelection.allPairs(checkin(), stops, listOf("a", "", "c", "d"), verified).isEmpty())
        val ambiguous = stops.toMutableList().apply { this[2] = this[2].copy(uuid = "a") }
        assertTrue(RoadRouteSelection.allPairs(checkin(), ambiguous, keys, verified).isEmpty())
    }

    @Test fun cacheValidationRequiresExactPhysicalEndpointsAndFreshTimestamp() {
        val request = pairs().first()
        val geometry = geometry(request)
        assertTrue(RoadRouteSelection.usable(request, geometry, now))
        assertFalse(RoadRouteSelection.usable(request.copy(from = request.from.copy(latitude = request.from.latitude + 0.00001)), geometry, now))
        assertFalse(RoadRouteSelection.usable(request.copy(to = request.to.copy(longitude = request.to.longitude + 0.00001)), geometry, now))
        assertFalse(RoadRouteSelection.usable(request, geometry.copy(fetchedAtMillis = now + 1), now))
        assertFalse(RoadRouteSelection.usable(request, geometry.copy(fetchedAtMillis = now - RoadRouteSelection.MAX_GEOMETRY_AGE_MILLIS - 1), now))
        assertTrue(RoadRouteSelection.usable(request, geometry.copy(fetchedAtMillis = now - RoadRouteSelection.MAX_GEOMETRY_AGE_MILLIS), now))
    }

    @Test fun visitIdentityIsPartOfTheCacheRequestEvenForIdenticalCoordinates() {
        val request = pairs().first()
        assertNotEquals(request, request.copy(fromKey = "other-origin-visit"))
        assertNotEquals(request, request.copy(toKey = "other-destination-visit"))
        assertNotEquals(request, request.copy(from = request.from.copy(latitude = request.from.latitude + 0.01)))
    }

    @Test fun emptyOrInvalidShapeIsNeverUsable() {
        val request = pairs().first()
        assertFalse(RoadRouteSelection.usable(request, geometry(request).copy(alternatives = emptyList()), now))
        assertFalse(RoadRouteSelection.usable(request, geometry(request).copy(alternatives = listOf(listOf(request.from))), now))
        assertFalse(RoadRouteSelection.usable(request, geometry(request).copy(alternatives = listOf(listOf(request.from, RoutePoint(95.0, 7.0)))), now))
    }

    private fun pairs(checkin: CheckinInfo = checkin(), route: List<StopStation> = stops,
                      points: Map<String, SevStopInfo> = verified): List<RoadSegmentRequest> =
        RoadRouteSelection.allPairs(checkin, route, keys, points)

    private fun geometry(request: RoadSegmentRequest) = RoadRouteGeometry(request.from, request.to,
        listOf(listOf(request.from, request.to)), now)

    private fun stop(key: String, stationId: Int) = StopStation(uuid = key,
        station = TrainStation(id = stationId, latitude = 50.0, longitude = 8.0))

    private fun info(latitude: Double?) = SevStopInfo("https://example.test/public-stop", null,
        "Verifizierter öffentlicher SEV-Punkt", latitude, 7.0)

    private fun checkin() = CheckinInfo(hafasId = null, category = "bus", mode = "bus", lineName = "RE 1",
        distanceMeters = null, points = null, duration = null, origin = stops.first(), destination = stops.last(),
        operator = null, trip = 1, tripUuid = null, number = null, routeColor = null, routeTextColor = null,
        journeyNumber = null, manualDeparture = null, manualArrival = null)
}
