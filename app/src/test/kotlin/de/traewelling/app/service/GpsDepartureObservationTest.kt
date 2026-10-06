package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical progress and event observation use the same engine-first call order as the service. */
class GpsDepartureObservationTest {
    private val base = 1_800_000_000_000L
    private val route = listOf(
        stop("origin", 0.0, null, base, origin = true),
        stop("next", 2_000.0, base + 250_000, base + 280_000),
        stop("destination", 4_000.0, base + 530_000, null, destination = true)
    )

    @Test
    fun frequentPlausibleFixesMustStillObserveDepartureAfterSupportedOriginDwell() {
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null

        fun accept(positionMeters: Double, elapsedMillis: Long, speed: Double) {
            val fix = LocationFix(0.0, positionMeters / METERS_PER_DEGREE,
                10.0, base + elapsedMillis, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            times = estimator.update(route, engine.getProgress(), update.source,
                fix, fix.timeMillis)
            assertFalse("This trajectory cannot complete the distant destination", update.destinationReached)
        }

        // Confirm the origin's inner-station dwell with independent slow fixes.
        accept(0.0, 0L, 0.0)
        accept(0.0, 3_000L, 0.0)
        assertTrue(engine.getProgress().arrivedAtCurrent)
        assertTrue(engine.getProgress().gpsEstablished)
        assertEquals("origin", engine.getProgress().nextStopKey)

        // 8 m/s and one fix every three seconds: 24 m each, with no GPS outage.
        for (sample in 1..15) accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)

        // Controls prove that ordinary physical progress and forecasting work.
        assertEquals("next", engine.getProgress().nextStopKey)
        assertTrue(engine.getProgress().gpsEstablished)
        assertFalse(engine.getProgress().completed)
        assertNotNull("The successor movement window supports a forecast", times)
        assertTrue(times!!.stopTimes.any {
            it.stopKey == "next" && it.arrivalMillis != null && !it.arrivalObserved
        })

        // The handover has only 24 m in its last pair, but continuous movement
        // since the confirmed station dwell supports the actual departure.
        assertTrue("A confirmed dwell followed by supported departure must publish the origin departure",
            times!!.stopTimes.any { it.stopKey == "origin" && it.departureObserved })
    }

    @Test
    fun earlySupportedDepartureIsNotBlockedByTheFutureTimetable() {
        val earlyRoute = route.mapIndexed { index, stop ->
            if (index == 0) stop.copy(plannedDepartureMillis = base + 60_000,
                effectiveDepartureMillis = base + 60_000) else stop
        }
        val times = journey(earlyRoute, dwellUntil = 3_000, speed = 8.0)
        val origin = times!!.stopTimes.single { it.stopKey == "origin" }
        assertTrue(origin.departureObserved)
        assertTrue(origin.departureMillis!! < earlyRoute.first().plannedDepartureMillis!!)
    }

    @Test
    fun unknownSpeedRequiresDwellButCanStillSupportCumulativeDeparture() {
        val times = journey(route, dwellUntil = 8_000, speed = null)
        assertTrue(times!!.stopTimes.single { it.stopKey == "origin" }.departureObserved)
    }

    @Test
    fun aConfirmedDwellJustBeforeTheOriginCanSupportFrequentDepartureFixes() {
        val times = journey(route, dwellUntil = 3_000, speed = 8.0, dwellPositionMeters = -10.0)
        assertTrue(times!!.stopTimes.single { it.stopKey == "origin" }.departureObserved)
    }

    @Test
    fun nativeAndRoadDepartureAnchorsCanUseOnlyTheSupportedPhysicalOrigin() {
        for (source in listOf(GpsGeometrySource.TRIP_POLYLINE, GpsGeometrySource.ROAD_MODEL)) {
            val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1_000.0 / METERS_PER_DEGREE),
                RoutePoint(.001, 1_500.0 / METERS_PER_DEGREE), RoutePoint(0.0, 2_000.0 / METERS_PER_DEGREE))
            val geometry = GpsSegmentGeometry("origin", "next",
                RouteGeometry(points.first(), points.last(), listOf(points), base), source)
            val times = journey(route, dwellUntil = 3_000, speed = 8.0,
                dwellPositionMeters = -10.0, segments = listOf(geometry))
            assertTrue(times!!.stopTimes.single { it.stopKey == "origin" }.departureObserved)
        }
    }

    @Test
    fun aGpsGapCannotTurnAnEarlierDwellIntoAnObservedDeparture() {
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        fun accept(meters: Double, elapsed: Long, speed: Double): GpsJourneyTimes? {
            val fix = LocationFix(0.0, meters / METERS_PER_DEGREE, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            return estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis)
        }
        accept(0.0, 0, 0.0)
        accept(0.0, 3_000, 0.0)
        for (sample in 0..15) {
            val times = accept(240.0 + sample * 24.0, 40_000L + sample * 3_000L, 8.0)
            assertFalse(times?.stopTimes?.any { it.stopKey == "origin" && it.departureObserved } == true)
        }
    }

    @Test
    fun aRejectedJumpCannotProvideTheCumulativeDepartureAnchor() {
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        fun accept(meters: Double, elapsed: Long, speed: Double): GpsJourneyTimes? {
            val fix = LocationFix(0.0, meters / METERS_PER_DEGREE, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            return estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis)
        }
        accept(0.0, 0, 0.0)
        accept(0.0, 3_000, 0.0)
        assertNull(accept(800.0, 3_100, 8.0))
        assertEquals("origin", engine.getProgress().nextStopKey)
        for (sample in 1..15) {
            val times = accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)
            assertFalse(times?.stopTimes?.any { it.stopKey == "origin" && it.departureObserved } == true)
        }
    }

    private fun journey(stops: List<TrackingStop>, dwellUntil: Long, speed: Double?,
                        dwellPositionMeters: Double = 0.0,
                        segments: List<GpsSegmentGeometry> = emptyList()): GpsJourneyTimes? {
        val engine = StationTrackingEngine(stops)
        engine.updateSegmentGeometries(segments)
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null
        fun accept(meters: Double, elapsed: Long, reportedSpeed: Double?) {
            val fix = LocationFix(0.0, meters / METERS_PER_DEGREE, 10.0,
                base + elapsed, reportedSpeed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            times = estimator.update(stops, engine.getProgress(), update.source, fix, fix.timeMillis,
                segments, useRoadGeometry = segments.any { it.source == GpsGeometrySource.ROAD_MODEL })
        }
        accept(dwellPositionMeters, 0, speed?.let { 0.0 })
        accept(dwellPositionMeters, 3_000, speed?.let { 0.0 })
        if (dwellUntil > 3_000) accept(dwellPositionMeters, dwellUntil, null)
        assertTrue(engine.getProgress().arrivedAtCurrent)
        for (sample in 1..15) accept(sample * 24.0, dwellUntil + sample * 3_000L, speed)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().completed)
        return times
    }

    private fun stop(key: String, meters: Double, arrival: Long?, departure: Long?,
                     origin: Boolean = false, destination: Boolean = false) = TrackingStop(
        key = key,
        stationId = key.hashCode(),
        name = key,
        latitude = 0.0,
        longitude = meters / METERS_PER_DEGREE,
        plannedArrivalMillis = arrival,
        effectiveArrivalMillis = arrival,
        effectiveDepartureMillis = departure,
        isOrigin = origin,
        isDestination = destination,
        plannedDepartureMillis = departure
    )

    private companion object { const val METERS_PER_DEGREE = 111_195.0 }
}
