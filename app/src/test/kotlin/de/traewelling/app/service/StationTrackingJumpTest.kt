package de.traewelling.app.service

import org.junit.Assert.*
import org.junit.Test

/** Physical movement regressions: synthetic positions, realistically spaced valid samples. */
class StationTrackingJumpTest {
    private val base = 1_800_000_000_000L
    private val origin = stop("origin", 0.0, origin = true)
    private val destination = stop("destination", 2_144.0, destination = true)

    @Test fun implausibleFreshJumpMustNotCompleteTheJourney() {
        val engine = boarded(listOf(origin, destination))
        engine.onLocation(fix(2_144.0, 5_000), base + 5_000)
        val after = engine.onLocation(fix(2_144.0, 7_000), base + 7_000)
        assertFalse(after.destinationReached)
        assertFalse(engine.getProgress().completed)
        assertEquals("origin", engine.getProgress().nextStopKey)
    }

    @Test fun implausibleFreshJumpMustNotReserveDestinationSpeech() {
        val engine = boarded(listOf(origin, destination))
        val jumped = engine.onLocation(fix(2_144.0, 5_000), base + 5_000)
        assertNull(jumped.announcement)
        assertFalse("destination" in engine.getProgress().announcedKeys)
    }

    @Test fun returningFromAnOutlierKeepsTheRealPositionAndSupportsLaterNormalDeparture() {
        val engine = boarded(listOf(origin, stop("middle", 700.0), destination))
        engine.onLocation(fix(2_144.0, 5_000), base + 5_000)
        engine.onLocation(fix(0.0, 8_000), base + 8_000)
        assertEquals("origin", engine.getProgress().nextStopKey)
        assertFalse("destination" in engine.getProgress().announcedKeys)
        val departure = engine.onLocation(fix(450.0, 14_000, speed = 75.0), base + 14_000)
        assertEquals("middle", departure.stop?.key)
        assertEquals("middle", departure.announcement?.key)
        assertFalse(departure.destinationReached)
    }

    @Test fun validFastRailMovementCanStillAnnounceAndCompleteTheDestination() {
        val target = stop("destination", 1_500.0, destination = true)
        val engine = boarded(listOf(origin, target))
        engine.onLocation(fix(700.0, 10_000, speed = 100.0), base + 10_000)
        val arrival = engine.onLocation(fix(1_500.0, 18_000), base + 18_000)
        assertEquals("destination", arrival.announcement?.key)
        assertFalse(arrival.destinationReached)
        assertTrue(engine.onLocation(fix(1_500.0, 21_000), base + 21_000).destinationReached)
    }

    @Test fun validCloseStopMovementRetainsImmediateSuccessorSpeech() {
        val engine = boarded(listOf(origin, stop("close", 120.0), destination))
        engine.onLocation(fix(60.0, 4_000, speed = 60.0), base + 4_000)
        val next = engine.onLocation(fix(120.0, 5_000, speed = 60.0), base + 5_000)
        assertEquals("close", next.stop?.key)
        assertEquals("close", next.announcement?.key)
        assertTrue(engine.getProgress().arrivedAtCurrent)
    }

    @Test fun genuineLongOutageStillRequiresAndAllowsIndependentReanchorEvidence() {
        val engine = boarded(listOf(origin, stop("destination", 20_000.0, destination = true)))
        for (elapsed in listOf(300_000L, 303_000L)) {
            val result = engine.onLocation(fix(20_000.0, elapsed), base + elapsed)
            assertEquals("origin", engine.getProgress().nextStopKey)
            assertFalse(result.destinationReached)
            assertNull(result.announcement)
        }
        val confirmed = engine.onLocation(fix(20_000.0, 306_000), base + 306_000)
        assertEquals("destination", confirmed.stop?.key)
        assertFalse(confirmed.destinationReached)
        assertFalse(engine.getProgress().completed)
        assertTrue(engine.onLocation(fix(20_000.0, 309_000), base + 309_000).destinationReached)
    }

    private fun boarded(stops: List<TrackingStop>): StationTrackingEngine =
        StationTrackingEngine(stops, radiusMeters = 300).also {
            it.onLocation(fix(0.0, 0), base)
            it.onLocation(fix(0.0, 3_000), base + 3_000)
            assertTrue(it.getProgress().arrivedAtCurrent)
        }

    private fun stop(key: String, meters: Double, origin: Boolean = false, destination: Boolean = false) =
        TrackingStop(key, key.hashCode(), key, 0.0, meters / METERS_PER_DEGREE,
            if (origin) null else base + 720_000, if (origin) null else base + 720_000,
            if (destination) null else base + 600_000, isOrigin = origin, isDestination = destination,
            plannedDepartureMillis = if (destination) null else base + 600_000)

    private fun fix(meters: Double, elapsed: Long, speed: Double = 0.0) =
        LocationFix(0.0, meters / METERS_PER_DEGREE, 10.0, base + elapsed, speed)

    private companion object { const val METERS_PER_DEGREE = 111_195.0 }
}
