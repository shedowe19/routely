package de.traewelling.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises the production Android adapter projection through the actual boarding engine. */
class TrackingLocationObservationTest {
    private val now = 1_800_000_000_000L

    @Test
    fun reportedInvalidSpeedCannotBecomeAnUnknownSpeedBoardingHint() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { speed ->
            val engine = engine()
            listOf(0L, 3_000L, 10_000L).forEach { offset ->
                val fix = observation(now + offset, hasSpeed = true, speed = speed)
                assertNull(engine.onLocation(fix, now + offset).announcement)
            }
        }
    }

    @Test
    fun genuinelyAbsentSpeedStillAllowsTheEstablishedTenSecondWaitingRule() {
        val engine = engine()
        assertNull(engine.onLocation(observation(now, hasSpeed = false), now).announcement)
        assertNull(engine.onLocation(observation(now + 3_000, hasSpeed = false), now + 3_000).announcement)
        assertEquals("origin", engine.onLocation(observation(now + 10_000, hasSpeed = false), now + 10_000).announcement?.key)
    }

    @Test
    fun validReportedZeroSpeedStillAllowsConfirmedBoardingAfterThreeSeconds() {
        val engine = engine()
        assertNull(engine.onLocation(observation(now, hasSpeed = true, speed = 0.0), now).announcement)
        assertEquals("origin", engine.onLocation(observation(now + 3_000, hasSpeed = true, speed = 0.0), now + 3_000).announcement?.key)
    }

    private fun observation(time: Long, hasSpeed: Boolean, speed: Double = 0.0) =
        trackingLocationFix(52.0, 13.0, 8.0, time, hasSpeed, speed)

    private fun engine() = StationTrackingEngine(listOf(
        TrackingStop("origin", 1, "Start", 52.0, 13.0, null, null, now + 60_000, isOrigin = true),
        TrackingStop("destination", 2, "Ziel", 52.0, 13.02, now + 600_000, now + 600_000, null, isDestination = true)
    ))
}
