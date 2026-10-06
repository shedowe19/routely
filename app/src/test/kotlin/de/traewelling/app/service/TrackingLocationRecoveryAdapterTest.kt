package de.traewelling.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the same new-observation dispatch used by the Android callback. */
class TrackingLocationRecoveryAdapterTest {
    private val now = 1_800_000_000_000L
    private val metersPerDegree = 111_195.0

    @Test
    fun unusableAccuracyBreaksRecoveryAndRequiresThreeNewPreciseSamples() {
        for (accuracy in listOf(80.0, 150.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            val harness = CallbackHarness()
            harness.emit(0)
            harness.emit(3)
            assertTrue(harness.engine.isReacquiringLocation())
            harness.emit(6, accuracy)
            harness.emit(9)
            assertEquals("bismarck", harness.engine.getProgress().nextStopKey)
            assertTrue(harness.engine.isReacquiringLocation())
            harness.emit(12)
            assertEquals("bismarck", harness.engine.getProgress().nextStopKey)
            harness.emit(15)
            assertEquals("savigny", harness.engine.getProgress().nextStopKey)
            assertFalse(harness.engine.getProgress().arrivedAtCurrent)
            assertFalse(harness.engine.getProgress().completed)
            assertEquals(setOf("origin"), harness.engine.getProgress().announcedKeys)
            assertNull(harness.latestUpdate!!.announcement)
            assertFalse(harness.latestUpdate!!.destinationReached)
        }
    }

    @Test
    fun uninterruptedPreciseRecoveryStillAnchorsAfterThreeSamples() {
        val harness = CallbackHarness()
        harness.emit(0)
        harness.emit(3)
        harness.emit(6)
        assertEquals("savigny", harness.engine.getProgress().nextStopKey)
        assertFalse(harness.engine.getProgress().completed)
    }

    @Test
    fun replayedAndOlderUnusableSamplesDoNotEraseCurrentFreshEvidence() {
        val harness = CallbackHarness()
        harness.emit(0)
        harness.emit(3)
        assertNull(harness.emit(3, 150.0, receivedAtSeconds = 4))
        assertNull(harness.emit(2, 150.0, receivedAtSeconds = 5))
        harness.emit(6)
        assertEquals("savigny", harness.engine.getProgress().nextStopKey)
    }

    private inner class CallbackHarness {
        val engine = StationTrackingEngine(stops(), TrackingProgress(
            nextIndex = 1, nextStopKey = "bismarck", announcedKeys = setOf("origin"), gpsEstablished = true
        ))
        private val clock = TrackingLocationClock()
        var latestUpdate: TrackingUpdate? = null

        fun emit(seconds: Long, accuracy: Double = 10.0, receivedAtSeconds: Long = seconds): TrackingUpdate? {
            val elapsed = (100_000L + seconds * 1_000L) * 1_000_000L
            val receivedElapsed = (100_000L + receivedAtSeconds * 1_000L) * 1_000_000L
            val wall = now + receivedAtSeconds * 1_000L
            val observation = clock.observe(2_000.0 / metersPerDegree, 5_875.0 / metersPerDegree,
                accuracy, elapsed, receivedElapsed, wall, true, 0.0)
            return dispatchTrackingLocationObservation(observation, engine, wall).also {
                if (it != null) latestUpdate = it
            }
        }
    }

    private fun stops(): List<TrackingStop> = listOf(
        stop("origin", 0.0, 0.0, 1, origin = true),
        stop("bismarck", 2_000.0, 0.0, 2),
        stop("missed", 4_000.0, 2_000.0, 3),
        stop("savigny", 6_000.0, 2_000.0, 4),
        stop("destination", 8_000.0, 2_000.0, 5, destination = true)
    )

    private fun stop(key: String, east: Double, north: Double, id: Int,
                     origin: Boolean = false, destination: Boolean = false) = TrackingStop(
        key = key, stationId = id, name = key,
        latitude = north / metersPerDegree, longitude = east / metersPerDegree,
        plannedArrivalMillis = now + 600_000L, effectiveArrivalMillis = now + 600_000L,
        effectiveDepartureMillis = now + 660_000L, isOrigin = origin, isDestination = destination
    )
}
