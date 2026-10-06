package de.traewelling.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun monotonicAgeDefinesTheHumanEventTimeWithoutAnyProviderWallClock() {
        val clock = TrackingLocationClock()
        val observed = sample(clock, fixElapsed = 95_000, nowElapsed = 100_000, wall = now)
        assertTrue(observed.isNew)
        assertFalse(observed.clockChanged)
        assertEquals(now - 5_000, observed.fix!!.timeMillis)
        assertEquals(TrackingSource.GPS, engine().onLocation(observed.fix, now).source)
    }

    @Test fun replayKeepsExactlyTheOriginalEventTimeAndOlderAccurateFixCannotReplaceANewerOne() {
        val clock = TrackingLocationClock()
        val first = sample(clock, fixElapsed = 100_000, nowElapsed = 100_000, wall = now, accuracy = 80.0)
        val replay = sample(clock, fixElapsed = 100_000, nowElapsed = 103_000, wall = now + 3_000, accuracy = 5.0)
        assertEquals(first.fix, replay.fix)
        assertFalse(replay.isNew)
        assertNull(sample(clock, fixElapsed = 99_000, nowElapsed = 103_000, wall = now + 3_000).fix)
        assertEquals(first.fix, sample(clock, fixElapsed = 100_000, nowElapsed = 104_000, wall = now + 4_000).fix)
    }

    @Test fun futureAndStaleMonotonicSamplesCannotPoisonTheWatermark() {
        val clock = TrackingLocationClock()
        assertNull(sample(clock, fixElapsed = 200_000, nowElapsed = 100_000, wall = now).fix)
        assertNull(sample(clock, fixElapsed = 60_000, nowElapsed = 100_000, wall = now).fix)
        assertTrue(sample(clock, fixElapsed = 100_000, nowElapsed = 100_000, wall = now).isNew)
        // Check nanoseconds before division: even a sub-millisecond future fix is invalid.
        assertNull(clock.observe(52.0, 13.0, 8.0, 100_000_000_001L, 100_000_000_000L,
            now, true, 0.0).fix)
    }

    @Test fun wallClockRollbackRebasesEpochEvidenceWithoutReplayingTheLastFixOrLosingTheVisit() {
        for (clockJump in listOf(-120_000L, 60_000L)) {
            val clock = TrackingLocationClock()
            val engine = engine()
            val first = sample(clock, 100_000, 100_000, now)
            engine.onLocation(first.fix!!, now)
            val second = sample(clock, 103_000, 103_000, now + 3_000)
            engine.onLocation(second.fix!!, now + 3_000)
            val established = engine.getProgress()
            assertTrue(established.arrivedAtCurrent)
            val newNow = now + 4_000 + clockJump
            val changedReplay = sample(clock, 103_000, 104_000, newNow)
            assertTrue(changedReplay.clockChanged)
            assertNull(changedReplay.fix)
            engine.resetLocationClock()
            assertEquals(established, engine.getProgress())
            assertNull(sample(clock, 102_000, 105_000, newNow + 1_000).fix)
            val fresh = sample(clock, 106_000, 106_000, newNow + 2_000)
            assertTrue(fresh.isNew)
            assertFalse(fresh.clockChanged)
            val update = engine.onLocation(fresh.fix!!, newNow + 2_000)
            assertEquals(TrackingSource.GPS, update.source)
            assertEquals("origin", engine.getProgress().nextStopKey)
            assertTrue(engine.hasReliableLocation(newNow + 2_000))
        }
    }

    private fun sample(clock: TrackingLocationClock, fixElapsed: Long, nowElapsed: Long, wall: Long,
                       accuracy: Double = 8.0) = clock.observe(52.0, 13.0, accuracy,
        fixElapsed * 1_000_000, nowElapsed * 1_000_000, wall, true, 0.0)

    private fun observation(time: Long, hasSpeed: Boolean, speed: Double = 0.0) =
        trackingLocationFix(52.0, 13.0, 8.0, time, hasSpeed, speed)

    private fun engine() = StationTrackingEngine(listOf(
        TrackingStop("origin", 1, "Start", 52.0, 13.0, null, null, now + 60_000, isOrigin = true),
        TrackingStop("destination", 2, "Ziel", 52.0, 13.02, now + 600_000, now + 600_000, null, isDestination = true)
    ))
}
