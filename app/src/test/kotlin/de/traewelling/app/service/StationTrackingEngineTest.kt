package de.traewelling.app.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic positions and a fixed clock keep these tests independent of Android and the API. */
class StationTrackingEngineTest {
    private val now = 1_791_187_200_000L

    @Test
    fun reliableGpsKeepsDelayedDestinationActiveAfterItsTimetableTime() {
        val stop = stop("destination", arrival = now - 30 * MINUTE, destination = true)
        val engine = StationTrackingEngine(listOf(stop))

        engine.onLocation(fix(-1_000.0, now), now)
        val update = engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        assertEquals(TrackingSource.GPS, update.source)
        assertEquals("destination", update.stop?.key)
        assertEquals("destination", update.announcement?.key)
        assertFalse(update.destinationReached)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun approachDoesNotConfirmArrivalUntilTheInnerRadiusIsReached() {
        val engine = StationTrackingEngine(listOf(stop("next")))

        engine.onLocation(fix(-1_000.0, now), now)
        val approach = engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        val stillApproaching = engine.onLocation(fix(-180.0, now + 2 * SECOND), now + 2 * SECOND)

        assertEquals("next", approach.announcement?.key)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(stillApproaching.destinationReached)

        engine.onLocation(fix(-80.0, now + 3 * SECOND), now + 3 * SECOND)
        assertTrue(engine.getProgress().arrivedAtCurrent)
        assertEquals(0, engine.getProgress().nextIndex)
    }

    @Test
    fun firstGpsFixNearDestinationCannotConfirmArrival() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))

        val update = engine.onLocation(fix(-80.0, now), now)

        assertNull(update.announcement)
        assertFalse(update.destinationReached)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun destinationCompletesOnlyAfterConfirmedGpsArrival() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))

        engine.onLocation(fix(-1_000.0, now), now)
        val approach = engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        val arrival = engine.onLocation(fix(-80.0, now + 2 * SECOND), now + 2 * SECOND)

        assertFalse(approach.destinationReached)
        assertTrue(arrival.destinationReached)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun movingAwayWithinAnnouncementRadiusDoesNotAnnounce() {
        val engine = StationTrackingEngine(listOf(stop("next")))

        engine.onLocation(fix(-200.0, now), now)
        val update = engine.onLocation(fix(-280.0, now + SECOND), now + SECOND)

        assertNull(update.announcement)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun staleGpsUsesTimetableFallbackInsteadOfConfirmingArrival() {
        val engine = StationTrackingEngine(listOf(stop("next", arrival = now + 2 * MINUTE)))

        val update = engine.onLocation(fix(0.0, now - 31 * SECOND), now)

        assertEquals(TrackingSource.TIMETABLE, update.source)
        assertEquals("next", update.stop?.key)
        assertFalse(engine.hasReliableLocation(now))
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(update.destinationReached)
    }

    @Test
    fun inaccurateGpsUsesTimetableFallback() {
        val engine = StationTrackingEngine(listOf(stop("next", arrival = now + 2 * MINUTE)))

        val update = engine.onLocation(fix(0.0, now, accuracy = 250.0), now)

        assertEquals(TrackingSource.TIMETABLE, update.source)
        assertFalse(engine.hasReliableLocation(now))
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun missingStationCoordinatesUsesTimetableFallback() {
        val stop = stop("next", arrival = now + 2 * MINUTE).copy(latitude = null, longitude = null)
        val engine = StationTrackingEngine(listOf(stop))

        val update = engine.onLocation(fix(0.0, now), now)

        assertEquals(TrackingSource.TIMETABLE, update.source)
        assertEquals("next", update.stop?.key)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun timetableFallbackRespectsRealtimeDelay() {
        val stop = stop("delayed", arrival = now - 10 * MINUTE)
            .copy(
                effectiveArrivalMillis = now + 10 * MINUTE,
                effectiveDepartureMillis = now + 11 * MINUTE
            )
        val engine = StationTrackingEngine(listOf(stop))

        val beforeArrivalWindow = engine.onTimetable(now)
        val inArrivalWindow = engine.onTimetable(now + 8 * MINUTE)

        assertEquals(TrackingSource.TIMETABLE, beforeArrivalWindow.source)
        assertEquals("delayed", beforeArrivalWindow.stop?.key)
        assertNull(beforeArrivalWindow.announcement)
        assertEquals("delayed", inArrivalWindow.announcement?.key)
    }

    @Test
    fun timetableFallbackNeverClaimsConfirmedDestinationArrival() {
        val engine = StationTrackingEngine(listOf(stop("destination", arrival = now - MINUTE, destination = true)))

        val update = engine.onTimetable(now)

        assertEquals(TrackingSource.TIMETABLE, update.source)
        assertFalse(update.destinationReached)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun announcementIsNotRepeatedAcrossGpsUpdatesAndFallback() {
        val engine = StationTrackingEngine(listOf(stop("next", arrival = now + 2 * MINUTE)))

        engine.onLocation(fix(-1_000.0, now), now)
        val first = engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        val repeated = engine.onLocation(fix(-200.0, now + 2 * SECOND), now + 2 * SECOND)
        val fallback = engine.onTimetable(now + 33 * SECOND)

        assertEquals("next", first.announcement?.key)
        assertNull(repeated.announcement)
        assertNull(fallback.announcement)
        assertTrue(engine.getProgress().announcedKeys.contains("next"))
    }

    @Test
    fun persistedAnnouncementPreventsDuplicatesAfterEngineRestart() {
        val stops = listOf(stop("next"))
        val engine = StationTrackingEngine(stops)
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        val progress = Gson().fromJson(Gson().toJson(engine.getProgress()), TrackingProgress::class.java)
        val restarted = StationTrackingEngine(stops, progress)
        val first = restarted.onLocation(fix(-400.0, now + 2 * SECOND), now + 2 * SECOND)
        val second = restarted.onLocation(fix(-250.0, now + 3 * SECOND), now + 3 * SECOND)

        assertNull(first.announcement)
        assertNull(second.announcement)
        assertTrue(restarted.getProgress().announcedKeys.contains("next"))
    }

    @Test
    fun restoredArrivalNeedsFreshMovementBeforeAdvancingToNextStop() {
        val stops = listOf(stop("first"), stop("second", positionMeters = 2_000.0, stationId = 2))
        val engine = StationTrackingEngine(stops)
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        engine.onLocation(fix(0.0, now + 2 * SECOND), now + 2 * SECOND)
        assertTrue(engine.getProgress().arrivedAtCurrent)

        val restarted = StationTrackingEngine(stops, engine.getProgress())
        restarted.onLocation(fix(500.0, now + 3 * SECOND), now + 3 * SECOND)
        assertEquals(0, restarted.getProgress().nextIndex)

        restarted.onLocation(fix(800.0, now + 4 * SECOND), now + 4 * SECOND)
        assertEquals(1, restarted.getProgress().nextIndex)
        assertEquals("second", restarted.getProgress().nextStopKey)
    }

    @Test
    fun circularRouteAnnouncesEachVisitToTheSameStationSeparately() {
        val stops = listOf(
            stop("station-a-first", origin = true),
            stop("station-b", positionMeters = 2_000.0, stationId = 2),
            stop("station-a-return", destination = true)
        )
        val engine = StationTrackingEngine(stops)
        val announcements = mutableListOf<String>()
        val positions = listOf(-1_000.0, -250.0, 0.0, 500.0, 1_300.0, 1_750.0, 2_000.0, 1_500.0, 500.0, 250.0, 0.0)

        positions.forEachIndexed { index, position ->
            val time = now + index * SECOND
            val update = engine.onLocation(fix(position, time), time)
            update.announcement?.key?.let(announcements::add)
        }

        assertEquals(listOf("station-a-first", "station-b", "station-a-return"), announcements)
        assertEquals(setOf("station-a-first", "station-b", "station-a-return"), engine.getProgress().announcedKeys)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun adaptiveRadiusAnnouncesEarlierAtHigherSpeed() {
        val slow = StationTrackingEngine(listOf(stop("next")))
        val fast = StationTrackingEngine(listOf(stop("next")))
        slow.onLocation(fix(-1_000.0, now, speed = 1.0), now)
        fast.onLocation(fix(-1_000.0, now, speed = 40.0), now)

        val slowUpdate = slow.onLocation(fix(-800.0, now + SECOND, speed = 1.0), now + SECOND)
        val fastUpdate = fast.onLocation(fix(-800.0, now + SECOND, speed = 40.0), now + SECOND)

        assertNull(slowUpdate.announcement)
        assertEquals("next", fastUpdate.announcement?.key)
    }

    @Test
    fun adaptiveRadiusKeepsUsefulMinimumAndCapsVeryHighSpeeds() {
        val slow = StationTrackingEngine(listOf(stop("next")))
        slow.onLocation(fix(-400.0, now, speed = 0.0), now)
        val near = slow.onLocation(fix(-250.0, now + SECOND, speed = 0.0), now + SECOND)
        assertEquals("next", near.announcement?.key)

        val fast = StationTrackingEngine(listOf(stop("next")))
        fast.onLocation(fix(-4_000.0, now, speed = 500.0), now)
        val far = fast.onLocation(fix(-3_000.0, now + SECOND, speed = 500.0), now + SECOND)
        assertNull(far.announcement)
    }

    @Test
    fun configuredFixedRadiusOverridesAdaptiveSpeedRadius() {
        val engine = StationTrackingEngine(listOf(stop("next")), radiusMeters = 300)
        engine.onLocation(fix(-1_000.0, now, speed = 40.0), now)

        val outsideFixedRadius = engine.onLocation(fix(-800.0, now + SECOND, speed = 40.0), now + SECOND)
        assertNull(outsideFixedRadius.announcement)

        val lowSpeedEngine = StationTrackingEngine(listOf(stop("next")), radiusMeters = 300)
        lowSpeedEngine.onLocation(fix(-1_000.0, now, speed = 1.0), now)
        lowSpeedEngine.onLocation(fix(-800.0, now + SECOND, speed = 1.0), now + SECOND)
        lowSpeedEngine.setRadiusMeters(1000)
        val insideNewRadius = lowSpeedEngine.onLocation(fix(-700.0, now + 2 * SECOND, speed = 1.0), now + 2 * SECOND)
        assertEquals("next", insideNewRadius.announcement?.key)
    }

    @Test
    fun invalidCoordinatesNeverBecomeReliableGps() {
        val engine = StationTrackingEngine(listOf(stop("next", arrival = now + 2 * MINUTE)))
        val invalid = LocationFix(Double.NaN, 0.0, 10.0, now)

        val update = engine.onLocation(invalid, now)

        assertEquals(TrackingSource.TIMETABLE, update.source)
        assertFalse(engine.hasReliableLocation(now))
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun repeatedTimestampCannotProvideASecondArrivalObservation() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-250.0, now), now)

        val duplicateTime = engine.onLocation(fix(-80.0, now), now)

        assertNull(duplicateTime.announcement)
        assertFalse(duplicateTime.destinationReached)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun gpsGapRequiresANewApproachBeforeDestinationConfirmation() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        val afterGap = engine.onLocation(fix(-80.0, now + 32 * SECOND), now + 32 * SECOND)
        assertFalse(afterGap.destinationReached)
        assertFalse(engine.getProgress().completed)

        val newObservation = engine.onLocation(fix(0.0, now + 33 * SECOND), now + 33 * SECOND)
        assertTrue(newObservation.destinationReached)
    }

    @Test
    fun restoredVisitDoesNotJumpAheadByClockBeforeGpsReturns() {
        val stops = listOf(
            stop("delayed-current", arrival = now - 20 * MINUTE),
            stop("later", positionMeters = 2_000.0, stationId = 2)
        )
        val beforeRestart = StationTrackingEngine(stops)
        beforeRestart.onLocation(fix(-1_000.0, now - 2 * SECOND), now - 2 * SECOND)
        beforeRestart.onLocation(fix(-250.0, now - SECOND), now - SECOND)
        val progress = Gson().fromJson(Gson().toJson(beforeRestart.getProgress()), TrackingProgress::class.java)
        val engine = StationTrackingEngine(stops, progress)

        val fallback = engine.onTimetable(now)

        assertEquals("delayed-current", fallback.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
        assertEquals("delayed-current", engine.getProgress().nextStopKey)
    }

    @Test
    fun observedPassAdvancesAnIntermediateVisitWithoutConfirmingDestination() {
        val engine = StationTrackingEngine(listOf(
            stop("passed"),
            stop("destination", positionMeters = 2_000.0, stationId = 2, destination = true)
        ))
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        val passed = engine.onLocation(fix(400.0, now + 2 * SECOND), now + 2 * SECOND)

        assertEquals("destination", passed.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(passed.destinationReached)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun explicitGpsDisableAllowsClockProgressAndReenableWaitsForFreshGps() {
        val stops = listOf(
            stop("delayed-current", arrival = now - 20 * MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        )
        val engine = StationTrackingEngine(stops)
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-500.0, now + SECOND), now + SECOND)
        assertEquals("delayed-current", engine.onTimetable(now + 2 * SECOND).stop?.key)

        engine.setGpsEnabled(false)
        val clockMode = engine.onTimetable(now + 3 * SECOND)
        assertEquals(TrackingSource.TIMETABLE, clockMode.source)
        assertEquals("next", clockMode.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)

        engine.setGpsEnabled(true)
        val later = now + 20 * MINUTE
        val waitingForFix = engine.onTimetable(later)
        assertEquals("next", waitingForFix.stop?.key)
        assertFalse(engine.hasReliableLocation(later))
        assertEquals(1, engine.getProgress().nextIndex)

        val firstFix = engine.onLocation(fix(1_500.0, later + SECOND), later + SECOND)
        val secondFix = engine.onLocation(fix(1_750.0, later + 2 * SECOND), later + 2 * SECOND)
        assertNull(firstFix.announcement)
        assertEquals("next", secondFix.announcement?.key)
    }

    @Test
    fun fastDestinationPassKeepsTheTargetActiveUntilSlowArrivalIsConfirmed() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-1_000.0, now, speed = 30.0), now)
        engine.onLocation(fix(-250.0, now + SECOND, speed = 30.0), now + SECOND)
        val fastNearCentre = engine.onLocation(fix(-80.0, now + 2 * SECOND, speed = 30.0), now + 2 * SECOND)
        assertFalse(fastNearCentre.destinationReached)
        assertFalse(engine.getProgress().completed)

        val passing = engine.onLocation(fix(400.0, now + 3 * SECOND, speed = 30.0), now + 3 * SECOND)
        assertEquals("destination", passing.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
        assertFalse(passing.destinationReached)

        engine.onLocation(fix(250.0, now + 4 * SECOND, speed = 5.0), now + 4 * SECOND)
        val slowArrival = engine.onLocation(fix(0.0, now + 5 * SECOND, speed = 0.0), now + 5 * SECOND)
        assertTrue(slowArrival.destinationReached)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun destinationCanBeConfirmedByDwellWhenGpsSpeedIsUnavailable() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-1_000.0, now, speed = null), now)
        engine.onLocation(fix(-250.0, now + SECOND, speed = null), now + SECOND)
        val entering = engine.onLocation(fix(-80.0, now + 2 * SECOND, speed = null), now + 2 * SECOND)
        assertFalse(entering.destinationReached)

        val dwelling = engine.onLocation(fix(-80.0, now + 13 * SECOND, speed = null), now + 13 * SECOND)
        assertTrue(dwelling.destinationReached)
    }

    @Test
    fun firstGpsBeyondOriginNeedsCoherentMovementTowardTheNextStop() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 10 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))

        val first = engine.onLocation(fix(500.0, now), now)
        assertEquals("origin", first.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)

        val leavingOrigin = engine.onLocation(fix(800.0, now + SECOND), now + SECOND)
        assertEquals("next", leavingOrigin.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(leavingOrigin.destinationReached)
    }

    @Test
    fun movingBackTowardOriginDoesNotBootstrapToTheNextStop() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 10 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(800.0, now), now)

        val towardOrigin = engine.onLocation(fix(500.0, now + SECOND), now + SECOND)

        assertEquals("origin", towardOrigin.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
    }

    @Test
    fun twoFreshFixesRecoverAnObservedIntermediatePassAfterGpsOutage() {
        val engine = StationTrackingEngine(listOf(
            stop("passed"),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        val firstAfterGap = engine.onLocation(fix(500.0, now + 40 * SECOND), now + 40 * SECOND)
        assertEquals("passed", firstAfterGap.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)

        val coherentMovement = engine.onLocation(fix(800.0, now + 41 * SECOND), now + 41 * SECOND)
        assertEquals("next", coherentMovement.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun unsuccessfulSpeechCanBeRetriedThenAcknowledgedWithoutAnotherDuplicate() {
        val engine = StationTrackingEngine(listOf(stop("next")))
        engine.onLocation(fix(-1_000.0, now), now)
        val first = engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        assertEquals("next", first.announcement?.key)

        engine.releaseAnnouncement("next")
        val retry = engine.onLocation(fix(-250.0, now + 2 * SECOND), now + 2 * SECOND)
        assertEquals("next", retry.announcement?.key)

        engine.acknowledgeAnnouncement("next")
        val afterAcknowledgement = engine.onLocation(fix(-200.0, now + 3 * SECOND), now + 3 * SECOND)
        assertNull(afterAcknowledgement.announcement)
        assertTrue(engine.getProgress().announcedKeys.contains("next"))
    }

    @Test
    fun firstGpsReanchorsAProvisionalClockCursorEvenAfterRestartAndHeavyDelay() {
        val stops = listOf(
            stop("origin", arrival = now - 60 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        )
        val clockOnly = StationTrackingEngine(stops)
        assertEquals("next", clockOnly.onTimetable(now).stop?.key)
        assertEquals(1, clockOnly.getProgress().nextIndex)

        val saved = Gson().fromJson(Gson().toJson(clockOnly.getProgress()), TrackingProgress::class.java)
        val restarted = StationTrackingEngine(stops, saved)
        val observedOrigin = restarted.onLocation(fix(0.0, now + SECOND), now + SECOND)

        assertEquals(TrackingSource.GPS, observedOrigin.source)
        assertEquals("origin", observedOrigin.stop?.key)
        assertEquals(0, restarted.getProgress().nextIndex)
        assertFalse(observedOrigin.destinationReached)
    }

    @Test
    fun reenabledGpsCanReanchorClockProgressToTheObservedOrigin() {
        val stops = listOf(
            stop("origin", arrival = now - 20 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        )
        val engine = StationTrackingEngine(stops)
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + SECOND), now + SECOND)
        engine.setGpsEnabled(false)
        assertEquals("next", engine.onTimetable(now + 2 * SECOND).stop?.key)

        engine.setGpsEnabled(true)
        val observedOrigin = engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)

        assertEquals("origin", observedOrigin.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
        assertFalse(observedOrigin.destinationReached)
    }

    @Test
    fun temporaryLocationInvalidationKeepsGpsEstablishedVisitDespiteElapsedTimes() {
        val engine = StationTrackingEngine(listOf(
            stop("delayed-current", arrival = now - 20 * MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-500.0, now + SECOND), now + SECOND)

        engine.invalidateLocation()
        val fallback = engine.onTimetable(now + 2 * SECOND)

        assertEquals(TrackingSource.TIMETABLE, fallback.source)
        assertEquals("delayed-current", fallback.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
        assertFalse(engine.hasReliableLocation(now + 2 * SECOND))
        assertFalse(fallback.destinationReached)
    }

    @Test
    fun cachedRouteAndProgressWorkOfflineWithoutCurrentApiResponses() {
        val gson = Gson()
        val stops = listOf(stop("cached-next", destination = true))
        val type = object : TypeToken<List<TrackingStop>>() {}.type
        val cachedStops: List<TrackingStop> = gson.fromJson(gson.toJson(stops), type)
        val cachedProgress = gson.fromJson(gson.toJson(TrackingProgress()), TrackingProgress::class.java)
        val offlineEngine = StationTrackingEngine(cachedStops, cachedProgress)

        offlineEngine.onLocation(fix(-1_000.0, now), now)
        val update = offlineEngine.onLocation(fix(-250.0, now + SECOND), now + SECOND)

        assertEquals(TrackingSource.GPS, update.source)
        assertEquals("cached-next", update.announcement?.key)
        assertFalse(update.destinationReached)
    }

    @Test
    fun routeRefreshPreservesCurrentVisitWhenEarlierStopsAreInserted() {
        val first = stop("first")
        val current = stop("current", positionMeters = 2_000.0, stationId = 2)
        val progress = TrackingProgress(
            nextIndex = 1,
            nextStopKey = "current",
            announcedKeys = setOf("first", "current")
        )
        val engine = StationTrackingEngine(listOf(first, current), progress)

        engine.updateRoute(listOf(stop("inserted", stationId = 3), first, current))

        assertEquals(2, engine.getProgress().nextIndex)
        assertEquals("current", engine.getProgress().nextStopKey)
        assertEquals(setOf("first", "current"), engine.getProgress().announcedKeys)
    }

    private fun stop(
        key: String,
        positionMeters: Double = 0.0,
        stationId: Int = 1,
        arrival: Long = now + 10 * MINUTE,
        origin: Boolean = false,
        destination: Boolean = false
    ): TrackingStop = TrackingStop(
        key = key,
        stationId = stationId,
        name = "Test station $stationId",
        latitude = 0.0,
        longitude = positionMeters / METERS_PER_DEGREE,
        plannedArrivalMillis = arrival,
        effectiveArrivalMillis = arrival,
        effectiveDepartureMillis = arrival + MINUTE,
        isOrigin = origin,
        isDestination = destination
    )

    private fun fix(
        positionMeters: Double,
        time: Long,
        accuracy: Double = 10.0,
        speed: Double? = 0.0
    ): LocationFix = LocationFix(
        0.0,
        positionMeters / METERS_PER_DEGREE,
        accuracy,
        time,
        speed
    )

    companion object {
        private const val SECOND = 1_000L
        private const val MINUTE = 60_000L
        private const val METERS_PER_DEGREE = 111_195.0
    }
}
