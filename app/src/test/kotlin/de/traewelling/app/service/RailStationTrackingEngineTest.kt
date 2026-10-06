package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import org.junit.Assert.*
import org.junit.Test

class RailStationTrackingEngineTest {
    private val base = 1_800_000_000_000L
    private val origin = stop("origin", .0, origin = true)
    private val next = stop("next", .02)
    private val path = listOf(RoutePoint(50.0, .0), RoutePoint(50.005, .0),
        RoutePoint(50.005, .02), RoutePoint(50.0, .02))

    @Test fun earlyDepartureOnACurveUsesOrderedShapeInsteadOfDecreasingAirlineDistanceToNext() {
        val engine = StationTrackingEngine(listOf(origin, next))
        engine.updateSegmentGeometries(listOf(shape()))
        val first = fix(50.002, .0, 0)
        val second = fix(50.003, .0, 5_000)
        engine.onLocation(first, first.timeMillis)
        val result = engine.onLocation(second, second.timeMillis)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertEquals(TrackingSource.GPS, result.source)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, engine.geometrySource())
        assertFalse(engine.getProgress().announcedKeys.contains("origin"))
        val chord = StationTrackingEngine(listOf(origin, next))
        chord.onLocation(first, first.timeMillis)
        chord.onLocation(second, second.timeMillis)
        assertEquals("origin", chord.getProgress().nextStopKey)
    }

    @Test fun curvedGapRecoveryStillRequiresFreshDirectedMovementAndAPreviouslyApproachedVisit() {
        val old = origin.copy(isOrigin = false)
        val engine = StationTrackingEngine(listOf(old, next))
        engine.updateSegmentGeometries(listOf(shape()))
        for ((latitude, time) in listOf(49.997 to 0L, 49.9985 to 5_000L,
            50.003 to 45_000L)) {
            val fix = fix(latitude, .0, time)
            engine.onLocation(fix, fix.timeMillis)
        }
        assertEquals("origin", engine.getProgress().nextStopKey)
        val recovered = fix(50.004, .0, 50_000)
        engine.onLocation(recovered, recovered.timeMillis)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test fun aValidShapeCannotUseTheOldChordToBootstrapAnUnrelatedPosition() {
        val engine = StationTrackingEngine(listOf(origin, next))
        engine.updateSegmentGeometries(listOf(shape()))
        val first = fix(50.0, .005, 0)
        val second = fix(50.0, .007, 5_000)
        engine.onLocation(first, first.timeMillis)
        engine.onLocation(second, second.timeMillis)
        assertEquals("origin", engine.getProgress().nextStopKey)
        assertNull(engine.geometrySource())
    }

    @Test fun anAmbiguousLoopOrBackwardsCurveDoesNotBecomeDepartureEvidence() {
        val crossing = listOf(RoutePoint(50.0, .0), RoutePoint(50.004, .02),
            RoutePoint(50.004, .0), RoutePoint(50.0, .02))
        val ambiguous = StationTrackingEngine(listOf(origin, next))
        ambiguous.updateSegmentGeometries(listOf(shape(crossing)))
        val first = fix(50.0018, .009, 0)
        val second = fix(50.002, .01, 5_000)
        ambiguous.onLocation(first, first.timeMillis)
        ambiguous.onLocation(second, second.timeMillis)
        assertEquals("origin", ambiguous.getProgress().nextStopKey)
        val backwards = StationTrackingEngine(listOf(origin, next))
        backwards.updateSegmentGeometries(listOf(shape()))
        val outward = fix(50.004, .0, 0)
        val inward = fix(50.003, .0, 5_000)
        backwards.onLocation(outward, outward.timeMillis)
        backwards.onLocation(inward, inward.timeMillis)
        assertEquals("origin", backwards.getProgress().nextStopKey)
    }

    @Test fun geometryArrivalIsNotGpsAndStaleShapeUsesOnlyTheOptionalChordFallback() {
        val engine = StationTrackingEngine(listOf(origin, next))
        val first = fix(50.002, .0, 0)
        engine.onLocation(first, first.timeMillis)
        engine.updateSegmentGeometries(listOf(shape()))
        engine.onTimetable(base + 1_000)
        engine.onLocation(first, base + 1_000)
        assertEquals("origin", engine.getProgress().nextStopKey)
        val stale = StationTrackingEngine(listOf(origin, next))
        stale.updateSegmentGeometries(listOf(shape().copy(geometry = shape().geometry.copy(fetchedAtMillis = base - 900_001))))
        for (longitude in listOf(.005, .007)) {
            val fix = fix(50.0, longitude, if (longitude == .005) 0 else 5_000)
            stale.onLocation(fix, fix.timeMillis)
        }
        assertEquals("next", stale.getProgress().nextStopKey)
        assertNull(stale.geometrySource())
    }

    @Test fun targetStillRequiresIndependentPhysicalArrivalAndTheClockCannotFinishIt() {
        val target = next.copy(isDestination = true)
        val engine = StationTrackingEngine(listOf(origin, target))
        engine.updateSegmentGeometries(listOf(shape()))
        for ((latitude, elapsed) in listOf(50.002 to 0L, 50.003 to 5_000L)) {
            val fix = fix(latitude, .0, elapsed)
            engine.onLocation(fix, fix.timeMillis)
        }
        assertEquals("next", engine.getProgress().nextStopKey)
        assertFalse(engine.onTimetable(base + 600_000).destinationReached)
        val first = fix(50.0, .02, 605_000).copy(speedMetersPerSecond = 1.0)
        assertFalse(engine.onLocation(first, first.timeMillis).destinationReached)
        val second = first.copy(timeMillis = first.timeMillis + 3_000)
        assertTrue(engine.onLocation(second, second.timeMillis).destinationReached)
    }

    @Test fun shapeInsertionReplacementAndRemovalNeedTwoNewFixesOnTheNewMovementBasis() {
        val changed = shape().copy(geometry = shape().geometry.copy(alternatives = listOf(
            path.map { if (it.latitude == 50.005) it.copy(latitude = 50.0051) else it })))
        for ((before, after) in listOf(emptyList<GpsSegmentGeometry>() to listOf(shape()),
            listOf(shape()) to listOf(changed))) {
            val engine = StationTrackingEngine(listOf(origin, next))
            engine.updateSegmentGeometries(before)
            val first = fix(50.002, .0, 0)
            engine.onLocation(first, first.timeMillis)
            engine.updateSegmentGeometries(after)
            val newFirst = fix(50.003, .0, 5_000)
            engine.onLocation(newFirst, newFirst.timeMillis)
            assertEquals("origin", engine.getProgress().nextStopKey)
            val newSecond = fix(50.004, .0, 10_000)
            engine.onLocation(newSecond, newSecond.timeMillis)
            assertEquals("next", engine.getProgress().nextStopKey)
        }
        val removed = StationTrackingEngine(listOf(origin, next))
        removed.updateSegmentGeometries(listOf(shape()))
        val first = fix(50.0, .005, 0)
        removed.onLocation(first, first.timeMillis)
        removed.updateSegmentGeometries(emptyList())
        val newFirst = fix(50.0, .007, 5_000)
        removed.onLocation(newFirst, newFirst.timeMillis)
        assertEquals("origin", removed.getProgress().nextStopKey)
        val newSecond = fix(50.0, .009, 10_000)
        removed.onLocation(newSecond, newSecond.timeMillis)
        assertEquals("next", removed.getProgress().nextStopKey)
    }

    @Test fun timestampOnlyRefreshAndUnrelatedFutureShapeDoNotEraseFreshDepartureEvidence() {
        val engine = StationTrackingEngine(listOf(origin, next, stop("future", .04)))
        engine.updateSegmentGeometries(listOf(shape()))
        val first = fix(50.002, .0, 0)
        engine.onLocation(first, first.timeMillis)
        val refreshed = shape().copy(geometry = shape().geometry.copy(fetchedAtMillis = base + 1_000))
        val future = GpsSegmentGeometry("next", "future", RouteGeometry(RoutePoint(50.0, .02),
            RoutePoint(50.0, .04), listOf(listOf(RoutePoint(50.0, .02), RoutePoint(50.005, .03),
                RoutePoint(50.0, .04))), base), GpsGeometrySource.TRIP_POLYLINE)
        engine.updateSegmentGeometries(listOf(refreshed, future))
        engine.onTimetable(base + 1_000)
        val second = fix(50.003, .0, 5_000)
        engine.onLocation(second, second.timeMillis)
        assertEquals("next", engine.getProgress().nextStopKey)
    }

    private fun shape(points: List<RoutePoint> = path) = GpsSegmentGeometry("origin", "next",
        RouteGeometry(RoutePoint(50.0, .0), RoutePoint(50.0, .02), listOf(points), base), GpsGeometrySource.TRIP_POLYLINE)
    private fun fix(latitude: Double, longitude: Double, elapsed: Long) = LocationFix(latitude, longitude, 10.0,
        base + elapsed, 12.0)
    private fun stop(key: String, longitude: Double, origin: Boolean = false) = TrackingStop(key,
        key.hashCode(), key, 50.0, longitude, base + 300_000, base + 300_000, base + 330_000,
        isOrigin = origin, plannedDepartureMillis = base + 330_000)
}
