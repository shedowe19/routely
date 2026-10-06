package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import de.traewelling.app.data.routing.TransitRouteParser
import org.junit.Assert.*
import org.junit.Test

/** Endpoint exclusion applies to the whole polyline, regardless of the last edge length. */
class GpsGeometryEndpointTest {
    @Test
    fun aFixBeyondTheUnsupportedPhysicalEndpointCannotCreateANativeForecast() {
        val base = 1_800_000_000_000L
        val originPoint = RoutePoint(50.0, 0.0)
        val targetPoint = RoutePoint(50.0, .014)
        val route = listOf(
            TrackingStop("origin", 1, "Origin", originPoint.latitude, originPoint.longitude,
                null, null, base, isOrigin = true, plannedDepartureMillis = base),
            TrackingStop("target", 2, "Target", targetPoint.latitude, targetPoint.longitude,
                base + 120_000, base + 120_000, null, isDestination = true)
        )
        val request = TransitRouteRequest(42, "7:review-trip", listOf(
            TransitRouteVisit("origin", 1, null, null, base, originPoint),
            TransitRouteVisit("target", 2, null, base + 120_000, null, targetPoint)
        ))
        // A valid curved native response, with a short final edge of about 35.74 m.
        val json = """{"data":{"type":"FeatureCollection","features":[{"type":"Feature",
            "properties":{"statusId":42},"geometry":{"type":"LineString","coordinates":[
            [0.0,50.0],[0.005,50.001],[0.0135,50.0],[0.014,50.0]]}}]}}"""
        val loaded = TransitRouteParser.parse(json, request, base)
        assertNotNull("The native parser must accept this curved, uniquely bound form", loaded)
        val binding = loaded!!.segments.single()
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, binding.source)
        val prepared = TrackingRouteGeometry.prepare(binding.geometry, route[0], route[1],
            binding.source, base + 20_000)
        assertNotNull("The common geometry validator must accept the parsed form", prepared)
        val path = prepared!!.paths.single()
        assertEquals(1027.63, path.length, .1)

        val fixes = listOf(
            LocationFix(50.0007, .00755, 75.0, base + 20_000, 80.0),
            LocationFix(50.00035, .010525, 75.0, base + 24_000, 80.0),
            LocationFix(50.0, .01505, 75.0, base + 28_000, 80.0)
        )
        fixes.take(2).forEach { fix ->
            val projected = TrackingRouteGeometry.project(path, fix)
            assertFalse("Inner controls must be unambiguous", projected.ambiguous)
            assertNotNull("Inner controls must project onto the route", projected.projection)
            assertTrue(projected.projection!!.fraction in .05.. .98)
            assertEquals(0.0, projected.projection!!.across, .01)
        }
        val lastFix = fixes.last()
        val targetDistance = TrackingRouteGeometry.distance(
            RoutePoint(lastFix.latitude, lastFix.longitude), targetPoint)
        assertEquals(75.05, targetDistance, .1)
        assertTrue("The physical inner endpoint zone is unsupported", targetDistance + lastFix.accuracyMeters > 120.0)

        val engine = StationTrackingEngine(route, TrackingProgress(nextIndex = 1,
            nextStopKey = "target", gpsEstablished = true))
        engine.updateSegmentGeometries(loaded.segments)
        val estimator = GpsJourneyTimeEstimator()
        var forecast: GpsJourneyTimes? = null
        fixes.forEachIndexed { index, fix ->
            val update = engine.onLocation(fix, fix.timeMillis)
            assertEquals(TrackingSource.GPS, update.source)
            assertFalse("A passing high-speed fix must not physically finish the trip", update.destinationReached)
            assertFalse(engine.getProgress().arrivedAtCurrent)
            forecast = estimator.update(route, engine.getProgress(), update.source, fix,
                fix.timeMillis, loaded.segments)
            if (index < 2) assertNull("Two controls cannot establish the movement window", forecast)
        }
        // The preceding vertex is within the forecast fraction window, but cannot
        // substitute for a closest physical endpoint whose arrival zone is unsupported.
        assertNull("A fix beyond the unsupported route endpoint must fall back, not create a GPS forecast", forecast)
    }
    @Test
    fun shortAndDensifiedEndpointEdgesCannotHideOutsideFixes() {
        for (source in listOf(GpsGeometrySource.TRIP_POLYLINE, GpsGeometrySource.ROAD_MODEL)) {
            for (tail in listOf(listOf(.0135, .014), listOf(.0135, .0138, .0139, .014))) {
                val path = prepare(listOf(RoutePoint(50.0, 0.0), RoutePoint(50.001, .005)) +
                    tail.map { RoutePoint(50.0, it) }, source)
                val fix = LocationFix(50.0, .01505, 75.0, 1_800_000_030_000L, 12.0)
                assertNull(TrackingRouteGeometry.project(path, fix).projection)
                assertNull(TrackingRouteGeometry.project(path, fix, arrivalEndpoint = true).projection)
            }
        }
    }

    @Test
    fun shortInitialEdgeCannotClampABeforeOriginFixOntoTheSecondEdge() {
        val path = prepare(listOf(RoutePoint(50.0, 0.0), RoutePoint(50.0, .0005),
            RoutePoint(50.001, .009), RoutePoint(50.0, .014)))
        val fix = LocationFix(50.0, -.00105, 75.0, 1_800_000_030_000L, 12.0)
        assertNull(TrackingRouteGeometry.project(path, fix).projection)
        assertNull(TrackingRouteGeometry.project(path, fix, arrivalEndpoint = true).projection)
    }

    @Test
    fun aNearOriginBranchRemainsAmbiguousInsteadOfBecomingAnOriginFallback() {
        val path = prepare(listOf(RoutePoint(50.0, 0.0), RoutePoint(50.0, .01),
            RoutePoint(50.01, .01), RoutePoint(50.01, .03), RoutePoint(50.0, .03),
            RoutePoint(50.0, -.01), RoutePoint(50.001, .014), RoutePoint(50.0, .014)))
        val fix = LocationFix(50.0, -.0001, 10.0, 1_800_000_030_000L, 0.0)
        val result = TrackingRouteGeometry.project(path, fix)
        assertNull(result.projection)
        assertTrue(result.ambiguous)
        assertFalse(result.beforeOrigin)
    }

    @Test
    fun aSupportedInnerArrivalZoneCanStillUseTheRealEndpoint() {
        val path = prepare(listOf(RoutePoint(50.0, 0.0), RoutePoint(50.001, .005),
            RoutePoint(50.0, .0135), RoutePoint(50.0, .014)))
        val fix = LocationFix(50.0, .0142, 10.0, 1_800_000_030_000L, 0.0)
        assertNull(TrackingRouteGeometry.project(path, fix).projection)
        val arrived = TrackingRouteGeometry.project(path, fix, arrivalEndpoint = true)
        assertFalse(arrived.ambiguous)
        assertEquals(1.0, arrived.projection!!.fraction, .000001)
    }

    @Test
    fun anInteriorCurveIsNotRejectedByAGlobalEndpointHalfPlane() {
        // The terminal edge points west; an earlier valid curve point also lies
        // west of the endpoint, but the physical endpoint is not its nearest route point.
        val path = prepare(listOf(RoutePoint(50.0, 0.0), RoutePoint(50.002, .010),
            RoutePoint(50.0, .014), RoutePoint(50.0, .0135)))
        val fix = LocationFix(50.001, .012, 10.0, 1_800_000_030_000L, 12.0)
        val result = TrackingRouteGeometry.project(path, fix)
        assertFalse(result.ambiguous)
        assertNotNull(result.projection)
        assertEquals(0.0, result.projection!!.across, .001)
        assertTrue(result.projection!!.fraction in .05.. .98)
    }

    private fun prepare(points: List<RoutePoint>, source: GpsGeometrySource = GpsGeometrySource.TRIP_POLYLINE): TrackingRouteGeometry.Path {
        val base = 1_800_000_000_000L
        val from = points.first()
        val to = points.last()
        val first = TrackingStop("from", 1, "From", from.latitude, from.longitude,
            null, null, base, isOrigin = true, plannedDepartureMillis = base)
        val last = TrackingStop("to", 2, "To", to.latitude, to.longitude,
            base + 120_000, base + 120_000, null, isDestination = true)
        return TrackingRouteGeometry.prepare(RouteGeometry(from, to, listOf(points), base),
            first, last, source, base)!!.paths.single()
    }

}
