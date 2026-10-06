package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.util.AuthSession
import org.junit.Assert.*
import org.junit.Test

class TransitRouteTrackingTest {
    private val now = 10 * TransitRouteSelection.MAX_GEOMETRY_AGE_MILLIS
    private val stops = listOf(stop("first", 1, 0), stop("middle", 2, 1), stop("last", 3, 2))
    private val session = AuthSession("https://example.test", "fake-token", "first-session")

    @Test fun onlyKnownRailCategoriesMayFetchNativeShapes() {
        listOf("nationalExpress", "national", "regionalExp", "regional", "suburban", "subway", "tram").forEach {
            assertNotNull(request(checkin = checkin().copy(category = it)))
        }
        listOf<String?>("bus", "ferry", "unknown", null).forEach {
            assertNull(request(checkin = checkin().copy(category = it)))
        }
        assertNull(request(checkin = checkin().copy(category = "regional", mode = "bus", lineName = "RE 1")))
        assertNull(request(checkin = checkin().copy(mode = "BUS")))
    }

    @Test fun realtimeManualTimesAndPlatformsDoNotInvalidateTheSourceRoute() {
        val expected = request()!!
        val updated = stops.map { it.copy(arrivalReal = "2026-10-06T12:20:00Z", departureReal = "2026-10-06T12:21:00Z",
            arrivalPlatformReal = "9", departurePlatformReal = "10") }
        assertEquals(expected, request(selected = updated))
        assertEquals(expected, request(checkin = checkin().copy(manualDeparture = "2026-10-06T12:10:00Z",
            manualArrival = "2026-10-06T12:30:00Z")))
    }

    @Test fun theWholeCheckedInRouteAndCancelledVisitsArePartOfTheBinding() {
        val cancelled = stops.map { if (it.uuid == "middle") it.copy(cancelled = true) else it }
        val requested = request(selected = cancelled, full = cancelled)!!
        assertEquals(listOf("first", "middle", "last"), requested.visits.map { it.key })
        assertTrue(requested.visits[1].cancelled)
        assertNotEquals(request()!!, requested)
        assertNull(request(selected = stops.take(2)))
    }

    @Test fun repeatedPhysicalStationsKeepTheirVisitKeysWhileAmbiguousBoundariesAreRejected() {
        val loop = stops.toMutableList().apply { this[2] = this[2].copy(station = this[0].station) }
        val requested = request(selected = loop, full = loop, checkin = checkin(loop))!!
        assertEquals(requested.visits.first().point, requested.visits.last().point)
        assertNotEquals(requested.visits.first().key, requested.visits.last().key)
        assertNull(request(full = listOf(stops.first()) + stops))
        assertNull(request(keys = listOf("first", "middle", "middle")))
        assertNull(request(keys = listOf("wrong-first", "middle", "last")))
    }

    @Test fun missingCancelledCoordinatesCannotCreateAPartialRouteOrAnInventedBridge() {
        val incomplete = stops.map { if (it.uuid == "middle") it.copy(cancelled = true,
            station = it.station!!.copy(latitude = null)) else it }
        assertNull(request(selected = incomplete, full = incomplete))
        val malformed = stops.map { if (it.uuid == "middle") it.copy(arrivalPlanned = "bad-time") else it }
        assertNull(request(selected = malformed, full = malformed))
        assertNull(request(checkin = checkin().copy(trip = null)))
    }

    @Test fun structuralChangesReplaceTheLeaseAndRejectItsDelayedResponse() {
        val cache = TransitRouteTrackingCache()
        val original = owner(request()!!)
        cache.bind(original)
        val changes = listOf(
            original.request.copy(tripIdentity = "another-trip"),
            original.request.copy(visits = original.request.visits.reversed()),
            original.request.copy(visits = original.request.visits.mapIndexed { index, visit ->
                if (index == 1) visit.copy(point = visit.point.copy(latitude = visit.point.latitude + 0.001)) else visit
            }),
            original.request.copy(visits = original.request.visits.mapIndexed { index, visit ->
                if (index == 1) visit.copy(arrivalPlannedMillis = visit.arrivalPlannedMillis!! + 60_000) else visit
            })
        )
        changes.forEach { changed ->
            cache.bind(original.copy(request = changed))
            assertFalse(cache.adopt(original, geometry(original.request), now))
            assertTrue(cache.segments(now).isEmpty())
            cache.bind(original)
        }
    }

    @Test fun sameNumericStatusOnAnotherAccountServerOrGenerationCannotReceiveAnOldShape() {
        val request = request()!!
        val original = owner(request)
        val replacements = listOf(
            original.copy(generation = original.generation + 1),
            original.copy(session = session.copy(accessToken = "another-fake-token", revision = "second-session")),
            original.copy(session = session.copy(serverUrl = "https://another.example.test", revision = "second-session")),
            // Even an A -> logout -> A credential reuse is a new access generation.
            original.copy(session = session.copy(revision = "relogged-first-session"))
        )
        replacements.forEach { replacement ->
            val cache = TransitRouteTrackingCache()
            cache.bind(original)
            assertTrue(cache.adopt(original, geometry(request), now))
            assertTrue(cache.bind(replacement))
            assertTrue(cache.segments(now).isEmpty())
            assertFalse(cache.adopt(original, geometry(request), now))
            assertTrue(cache.adopt(replacement, geometry(request), now))
        }
    }

    @Test fun stoppedOwnershipCannotBeReactivatedByALateHttpResult() {
        val cache = TransitRouteTrackingCache()
        val pending = owner(request()!!)
        cache.bind(pending)
        cache.bind(null)
        assertFalse(cache.adopt(pending, geometry(pending.request), now))
        assertFalse(cache.needsRefresh(now))
        assertTrue(cache.segments(now).isEmpty())
    }

    @Test fun refreshStartsBeforeHardExpiryButNeverExtendsAnOldShapesDeadline() {
        val cache = TransitRouteTrackingCache()
        val owner = owner(request()!!)
        cache.bind(owner)
        assertTrue(cache.needsRefresh(now))
        val loaded = geometry(owner.request)
        assertTrue(cache.adopt(owner, loaded, now))
        assertFalse(cache.needsRefresh(now + TransitRouteSelection.REFRESH_AGE_MILLIS - 1))
        assertTrue(cache.needsRefresh(now + TransitRouteSelection.REFRESH_AGE_MILLIS))
        assertFalse(cache.segments(now + TransitRouteSelection.MAX_GEOMETRY_AGE_MILLIS).isEmpty())
        assertTrue(cache.adopt(owner, loaded, now + TransitRouteSelection.REFRESH_AGE_MILLIS))
        assertTrue(cache.segments(now + TransitRouteSelection.MAX_GEOMETRY_AGE_MILLIS + 1).isEmpty())
        assertFalse(cache.adopt(owner, loaded, now + TransitRouteSelection.MAX_GEOMETRY_AGE_MILLIS + 1))
        assertFalse(cache.adopt(owner, loaded.copy(fetchedAtMillis = now + 1), now))
    }

    @Test fun wrongVisitPairEndpointOrRoadSourceIsRejectedEvenIfTheRequestMatches() {
        val requested = request()!!
        val loaded = geometry(requested)
        val segment = loaded.segments.single()
        val badSegments = listOf(
            segment.copy(fromKey = "unrelated"),
            segment.copy(source = GpsGeometrySource.ROAD_MODEL),
            segment.copy(geometry = segment.geometry.copy(to = RoutePoint(1.0, 2.0))),
            segment.copy(geometry = segment.geometry.copy(fetchedAtMillis = now - 1))
        )
        badSegments.forEach {
            assertFalse(TransitRouteSelection.usable(requested, loaded.copy(segments = listOf(it)), now))
        }
        assertFalse(TransitRouteSelection.usable(requested, loaded.copy(segments = listOf(segment, segment)), now))
        assertFalse(TransitRouteSelection.usable(requested, loaded.copy(segments = emptyList()), now))
    }

    @Test fun cancelledVisitPairsUseTheWholeValidatedPathBetweenRemainingVisits() {
        val cancelled = stops.map { if (it.uuid == "middle") it.copy(cancelled = true) else it }
        val requested = request(selected = cancelled, full = cancelled)!!
        val loaded = geometry(requested)
        assertEquals("first", loaded.segments.single().fromKey)
        assertEquals("last", loaded.segments.single().toKey)
        assertTrue(TransitRouteSelection.usable(requested, loaded, now))
    }

    @Test fun anActiveRailBasisRemainsVisibleWhenTheForecastIntervalExceedsNinetyMinutes() {
        val base = 1_800_000_000_000L
        val route = listOf(
            TrackingStop("origin", 1, "Origin", 50.0, 0.0, null, null, base,
                isOrigin = true, plannedDepartureMillis = base),
            TrackingStop("next", 2, "Next", 50.0, .02, base + 5_400_001, base + 5_400_001, null,
                isDestination = true)
        )
        val points = listOf(RoutePoint(50.0, 0.0), RoutePoint(50.005, 0.0),
            RoutePoint(50.005, .02), RoutePoint(50.0, .02))
        val shape = GpsSegmentGeometry("origin", "next", RouteGeometry(points.first(), points.last(),
            listOf(points), base), GpsGeometrySource.TRIP_POLYLINE)
        val engine = StationTrackingEngine(route,
            TrackingProgress(nextIndex = 1, nextStopKey = "next", gpsEstablished = true))
        val estimator = GpsJourneyTimeEstimator()
        engine.updateSegmentGeometries(listOf(shape))
        val fix = LocationFix(50.005, .006, 10.0, base + 10_000, 12.0)
        val update = engine.onLocation(fix, fix.timeMillis)
        assertEquals(TrackingSource.GPS, update.source)
        assertNull(estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis, listOf(shape)))
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, estimator.unavailableReason())
        assertNull(estimator.geometrySource())
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, engine.geometrySource())
        // The service's route badge may use this basis without claiming an available GPS ETA.
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, estimator.geometrySource() ?: engine.geometrySource())
        engine.onTimetable(fix.timeMillis + StationTrackingEngine.MAX_FIX_AGE_MILLIS + 1)
        assertNull(engine.geometrySource())
    }

    private fun request(
        checkin: CheckinInfo = checkin(), selected: List<StopStation> = stops,
        full: List<StopStation> = stops, keys: List<String> = selected.map { it.uuid!! }
    ): TransitRouteRequest? = TransitRouteSelection.request(42, checkin, full, selected, keys)

    private fun owner(request: TransitRouteRequest) = TransitRouteTrackingCache.Lease(42, 10L, session, request)

    private fun geometry(request: TransitRouteRequest): TransitRouteGeometry {
        val active = request.visits.filterNot { it.cancelled }
        val from = active.first()
        val to = active[1]
        val curve = RoutePoint((from.point.latitude + to.point.latitude) / 2 + 0.01,
            (from.point.longitude + to.point.longitude) / 2)
        return TransitRouteGeometry(request, listOf(GpsSegmentGeometry(from.key, to.key,
            RouteGeometry(from.point, to.point, listOf(listOf(from.point, curve, to.point)), now),
            GpsGeometrySource.TRIP_POLYLINE)), now)
    }

    private fun stop(key: String, id: Int, minute: Int) = StopStation(uuid = key,
        station = TrainStation(id = id, uuid = "station-$id", latitude = 51.0 + id / 100.0, longitude = 7.0),
        arrivalPlanned = "2026-10-06T12:0${minute}:00Z", departurePlanned = "2026-10-06T12:0${minute}:10Z")

    private fun checkin(route: List<StopStation> = stops) = CheckinInfo(hafasId = null, category = "regional", mode = "train",
        lineName = "RE 1", distanceMeters = null, points = null, duration = null, origin = route.first(), destination = route.last(),
        operator = null, trip = 7, tripUuid = "trip-uuid", number = null, routeColor = null, routeTextColor = null,
        journeyNumber = null, manualDeparture = null, manualArrival = null)
}
