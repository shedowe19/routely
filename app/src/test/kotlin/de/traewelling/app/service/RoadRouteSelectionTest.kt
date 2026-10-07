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

    @Test fun duisburgMuelheimEssenExplainsUnconfirmedStopBeforeAnyRoadRequest() {
        val route = listOf("Duisburg Hbf", "Mülheim (Ruhr) Hbf", "Essen Hbf").mapIndexed { index, name ->
            stop("visit-$index", index + 1).let { it.copy(station = it.station!!.copy(name = name)) }
        }
        val routeKeys = route.map { it.uuid!! }
        val trip = checkin().copy(origin = route.first(), destination = route.last())
        val points = mapOf("visit-0" to info(51.01), "visit-1" to info(null), "visit-2" to info(51.03))
        val cursor = TrackingProgress(nextIndex = 1, nextStopKey = "visit-1", gpsEstablished = true)
        assertTrue(RoadRouteSelection.allPairs(trip, route, routeKeys, points).isEmpty())
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
            RoadRouteSelection.unavailableReason(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
                trip, route, routeKeys, points, cursor))

        val confirmed = points + ("visit-1" to info(51.02))
        assertEquals(listOf("visit-0" to "visit-1", "visit-1" to "visit-2"),
            RoadRouteSelection.allPairs(trip, route, routeKeys, confirmed).map { it.fromKey to it.toKey })
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            RoadRouteSelection.unavailableReason(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
                trip, route, routeKeys, confirmed, cursor))
    }

    @Test fun missingEitherIncomingEndpointExplainsMissingSevBasis() {
        for (missing in listOf("a", "b")) {
            assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
                reason(points = verified - missing))
        }
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
            reason(points = verified + ("b" to info(Double.NaN))))
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
            reason(points = verified + ("b" to verified.getValue("b").copy(longitude = null))))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, reason())
    }

    @Test fun anotherVisitsPointCannotHideAnUnconfirmedIncomingEndpoint() {
        val otherVisitOnly = (verified - "b") + ("b-other-visit" to verified.getValue("b"))
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED, reason(points = otherVisitOnly))
    }

    @Test fun explanationUsesPreviousNonCancelledVisitWithoutSkippingAnUnconfirmedServedVisit() {
        val route = stops.map { if (it.uuid == "b") it.copy(cancelled = true) else it }
        val cursor = TrackingProgress(nextIndex = 2, nextStopKey = "c", gpsEstablished = true)
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(route = route, points = verified - "b", progress = cursor))
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
            reason(route = route, points = verified - "a", progress = cursor))
        assertEquals(GpsTimeUnavailableReason.REPLACEMENT_STOP_UNCONFIRMED,
            reason(points = verified - "b", progress = cursor))
    }

    @Test fun ambiguousOrWrongCursorAndCancelledCurrentVisitKeepTheOriginalReason() {
        for (cursor in listOf(
            TrackingProgress(nextIndex = 0, nextStopKey = "b"),
            TrackingProgress(nextIndex = 99, nextStopKey = "b"),
            TrackingProgress(nextIndex = 1, nextStopKey = "unknown"),
            TrackingProgress(nextIndex = 1, nextStopKey = null),
            TrackingProgress(nextIndex = 1, nextStopKey = "b", completed = true)
        )) {
            assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
                reason(points = emptyMap(), progress = cursor))
        }
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(route = stops.map { if (it.uuid == "b") it.copy(cancelled = true) else it }, points = emptyMap()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(trackingKeys = listOf("a", "b", "b", "d"), points = emptyMap()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(trackingKeys = keys.dropLast(1), points = emptyMap()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(trackingKeys = listOf("a", "b", "", "d"), points = emptyMap()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(route = stops.map { if (it.uuid == "c") it.copy(uuid = "a") else it }, points = emptyMap()))
    }

    @Test fun originWithoutIncomingLegAndOtherVehicleModesKeepTheOriginalReason() {
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(points = emptyMap(), progress = TrackingProgress(nextIndex = 0, nextStopKey = "a")))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(trip = checkin().copy(category = "regional", mode = "train"), points = emptyMap()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
            reason(trip = checkin().copy(lineName = "SB 1"), points = emptyMap()))
    }

    @Test fun otherGpsRejectionReasonsAndAnAvailableForecastAreNotOverridden() {
        for (underlying in GpsTimeUnavailableReason.entries.filter {
            it != GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE
        }) {
            assertEquals(underlying, reason(underlying = underlying, points = emptyMap()))
        }
        assertNull(reason(underlying = null, points = emptyMap()))
    }

    private fun reason(
        underlying: GpsTimeUnavailableReason? = GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE,
        trip: CheckinInfo = checkin(),
        route: List<StopStation> = stops,
        trackingKeys: List<String> = keys,
        points: Map<String, SevStopInfo> = verified,
        progress: TrackingProgress = TrackingProgress(nextIndex = 1, nextStopKey = "b", gpsEstablished = true)
    ): GpsTimeUnavailableReason? =
        RoadRouteSelection.unavailableReason(underlying, trip, route, trackingKeys, points, progress)

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
