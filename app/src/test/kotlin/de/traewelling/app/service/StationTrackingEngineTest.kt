package de.traewelling.app.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic positions and a fixed clock keep these tests independent of Android and the API.
 * Valid trajectories allow at most 100 m/s plus positional uncertainty between short samples;
 * explicit outliers stay impossible, and gap/dwell scenarios retain their own time boundaries.
 */
class StationTrackingEngineTest {
    private val now = 1_791_187_200_000L

    @Test
    fun reliableGpsKeepsDelayedDestinationActiveAfterItsTimetableTime() {
        val stop = stop("destination", arrival = now - 30 * MINUTE, destination = true)
        val engine = StationTrackingEngine(listOf(stop))

        engine.onLocation(fix(-1_000.0, now), now)
        val update = engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

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
        val approach = engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)
        val stillApproaching = engine.onLocation(fix(-180.0, now + 9 * SECOND), now + 9 * SECOND)

        assertEquals("next", approach.announcement?.key)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(stillApproaching.destinationReached)

        engine.onLocation(fix(-80.0, now + 10 * SECOND), now + 10 * SECOND)
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
        val approach = engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)
        val arrival = engine.onLocation(fix(-80.0, now + 10 * SECOND), now + 10 * SECOND)

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
        val first = engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)
        val repeated = engine.onLocation(fix(-200.0, now + 9 * SECOND), now + 9 * SECOND)
        val fallback = engine.onTimetable(now + 40 * SECOND)

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
        engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

        val progress = Gson().fromJson(Gson().toJson(engine.getProgress()), TrackingProgress::class.java)
        val restarted = StationTrackingEngine(stops, progress)
        val first = restarted.onLocation(fix(-400.0, now + 11 * SECOND), now + 11 * SECOND)
        val second = restarted.onLocation(fix(-250.0, now + 13 * SECOND), now + 13 * SECOND)

        assertNull(first.announcement)
        assertNull(second.announcement)
        assertTrue(restarted.getProgress().announcedKeys.contains("next"))
    }

    @Test
    fun restoredArrivalNeedsFreshMovementBeforeAdvancingToNextStop() {
        val stops = listOf(stop("first"), stop("second", positionMeters = 2_000.0, stationId = 2))
        val engine = StationTrackingEngine(stops)
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)
        engine.onLocation(fix(0.0, now + 11 * SECOND), now + 11 * SECOND)
        assertTrue(engine.getProgress().arrivedAtCurrent)

        val restarted = StationTrackingEngine(stops, engine.getProgress())
        restarted.onLocation(fix(500.0, now + 14 * SECOND), now + 14 * SECOND)
        assertEquals(0, restarted.getProgress().nextIndex)

        restarted.onLocation(fix(800.0, now + 18 * SECOND), now + 18 * SECOND)
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
            val time = now + index * 10 * SECOND
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

        val slowUpdate = slow.onLocation(fix(-800.0, now + 5 * SECOND, speed = 1.0), now + 5 * SECOND)
        val fastUpdate = fast.onLocation(fix(-800.0, now + 5 * SECOND, speed = 40.0), now + 5 * SECOND)

        assertNull(slowUpdate.announcement)
        assertEquals("next", fastUpdate.announcement?.key)
    }

    @Test
    fun adaptiveRadiusKeepsUsefulMinimumAndCapsVeryHighSpeeds() {
        val slow = StationTrackingEngine(listOf(stop("next")))
        slow.onLocation(fix(-400.0, now, speed = 0.0), now)
        val near = slow.onLocation(fix(-250.0, now + 2 * SECOND, speed = 0.0), now + 2 * SECOND)
        assertEquals("next", near.announcement?.key)

        val fast = StationTrackingEngine(listOf(stop("next")))
        fast.onLocation(fix(-4_000.0, now, speed = 500.0), now)
        val far = fast.onLocation(fix(-3_000.0, now + 10 * SECOND, speed = 500.0), now + 10 * SECOND)
        assertNull(far.announcement)
    }

    @Test
    fun configuredFixedRadiusOverridesAdaptiveSpeedRadius() {
        val engine = StationTrackingEngine(listOf(stop("next")), radiusMeters = 300)
        engine.onLocation(fix(-1_000.0, now, speed = 40.0), now)

        val outsideFixedRadius = engine.onLocation(fix(-800.0, now + 5 * SECOND, speed = 40.0), now + 5 * SECOND)
        assertNull(outsideFixedRadius.announcement)

        val lowSpeedEngine = StationTrackingEngine(listOf(stop("next")), radiusMeters = 300)
        lowSpeedEngine.onLocation(fix(-1_000.0, now, speed = 1.0), now)
        lowSpeedEngine.onLocation(fix(-800.0, now + 5 * SECOND, speed = 1.0), now + 5 * SECOND)
        lowSpeedEngine.setRadiusMeters(1000)
        val insideNewRadius = lowSpeedEngine.onLocation(fix(-700.0, now + 10 * SECOND, speed = 1.0), now + 10 * SECOND)
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
        engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

        val afterGap = engine.onLocation(fix(-80.0, now + 40 * SECOND), now + 40 * SECOND)
        assertFalse(afterGap.destinationReached)
        assertFalse(engine.getProgress().completed)

        val newObservation = engine.onLocation(fix(0.0, now + 41 * SECOND), now + 41 * SECOND)
        assertTrue(newObservation.destinationReached)
    }

    @Test
    fun restoredVisitDoesNotJumpAheadByClockBeforeGpsReturns() {
        val stops = listOf(
            stop("delayed-current", arrival = now - 20 * MINUTE),
            stop("later", positionMeters = 2_000.0, stationId = 2)
        )
        val beforeRestart = StationTrackingEngine(stops)
        beforeRestart.onLocation(fix(-1_000.0, now - 10 * SECOND), now - 10 * SECOND)
        beforeRestart.onLocation(fix(-250.0, now - 2 * SECOND), now - 2 * SECOND)
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
        engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

        val passed = engine.onLocation(fix(400.0, now + 15 * SECOND), now + 15 * SECOND)

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
        engine.onLocation(fix(-500.0, now + 5 * SECOND), now + 5 * SECOND)
        assertEquals("delayed-current", engine.onTimetable(now + 6 * SECOND).stop?.key)

        engine.setGpsEnabled(false)
        val clockMode = engine.onTimetable(now + 7 * SECOND)
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
        val secondFix = engine.onLocation(fix(1_750.0, later + 6 * SECOND), later + 6 * SECOND)
        assertNull(firstFix.announcement)
        assertEquals("next", secondFix.announcement?.key)
    }

    @Test
    fun fastDestinationPassKeepsTheTargetActiveUntilSlowArrivalIsConfirmed() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-1_000.0, now, speed = 30.0), now)
        engine.onLocation(fix(-250.0, now + 25 * SECOND, speed = 30.0), now + 25 * SECOND)
        val fastNearCentre = engine.onLocation(fix(-80.0, now + 31 * SECOND, speed = 30.0), now + 31 * SECOND)
        assertFalse(fastNearCentre.destinationReached)
        assertFalse(engine.getProgress().completed)

        val passing = engine.onLocation(fix(400.0, now + 47 * SECOND, speed = 30.0), now + 47 * SECOND)
        assertEquals("destination", passing.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
        assertFalse(passing.destinationReached)

        engine.onLocation(fix(250.0, now + 52 * SECOND, speed = 5.0), now + 52 * SECOND)
        val slowArrival = engine.onLocation(fix(0.0, now + 61 * SECOND, speed = 0.0), now + 61 * SECOND)
        assertTrue(slowArrival.destinationReached)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun destinationCanBeConfirmedByDwellWhenGpsSpeedIsUnavailable() {
        val engine = StationTrackingEngine(listOf(stop("destination", destination = true)))
        engine.onLocation(fix(-1_000.0, now, speed = null), now)
        engine.onLocation(fix(-250.0, now + 25 * SECOND, speed = null), now + 25 * SECOND)
        val entering = engine.onLocation(fix(-80.0, now + 31 * SECOND, speed = null), now + 31 * SECOND)
        assertFalse(entering.destinationReached)

        val dwelling = engine.onLocation(fix(-80.0, now + 42 * SECOND, speed = null), now + 42 * SECOND)
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

        val leavingOrigin = engine.onLocation(fix(800.0, now + 4 * SECOND), now + 4 * SECOND)
        assertEquals("next", leavingOrigin.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(leavingOrigin.destinationReached)
    }

    @Test
    fun earlyGpsDepartureDoesNotWaitForPlannedOrApiDeparture() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now + MINUTE, origin = true).copy(
                plannedDepartureMillis = now + 2 * MINUTE,
                effectiveDepartureMillis = now + 10 * MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2, arrival = now + 5 * MINUTE)
        ))
        engine.onLocation(fix(300.0, now, speed = 20.0), now)

        val leaving = engine.onLocation(fix(400.0, now + 5 * SECOND, speed = 20.0), now + 5 * SECOND)

        assertEquals("next", leaving.stop?.key)
        assertEquals(TrackingSource.GPS, leaving.source)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(leaving.destinationReached)
    }

    @Test
    fun gpsTimeForecastWorksWhenTrackingStartsAfterAnEarlyDeparture() {
        val route = listOf(
            stop("origin", arrival = now + MINUTE, origin = true).copy(
                plannedDepartureMillis = now + 2 * MINUTE,
                effectiveDepartureMillis = now + 10 * MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2, arrival = now + 5 * MINUTE,
                destination = true).copy(effectiveArrivalMillis = now + 15 * MINUTE)
        )
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        var estimate: GpsJourneyTimes? = null
        listOf(300.0, 400.0, 500.0, 600.0).forEachIndexed { index, position ->
            val time = now + index * 5 * SECOND
            val fix = fix(position, time, speed = 20.0)
            val update = engine.onLocation(fix, time)
            estimate = estimator.update(route, engine.getProgress(), update.source, fix, time)
        }

        assertNotNull(estimate)
        val arrival = estimate!!.stopTimes.single { it.stopKey == "next" }.arrivalMillis!!
        assertTrue(arrival < route[1].plannedArrivalMillis!!)
        assertTrue(arrival > now + 15 * SECOND)
        assertEquals(now + 15 * MINUTE, route[1].effectiveArrivalMillis)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun movingBackTowardOriginDoesNotBootstrapToTheNextStop() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 10 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(800.0, now), now)

        val towardOrigin = engine.onLocation(fix(500.0, now + 4 * SECOND), now + 4 * SECOND)

        assertEquals("origin", towardOrigin.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
    }

    @Test
    fun confirmedGpsWaitingAtOriginAnnouncesDepartureBeforeTheTrainMoves() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 2 * MINUTE, origin = true).copy(
                effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))

        val first = engine.onLocation(fix(0.0, now, speed = 0.0), now)
        val waiting = engine.onLocation(fix(0.0, now + 3 * SECOND, speed = 0.0), now + 3 * SECOND)
        val repeated = engine.onTimetable(now + 4 * SECOND)

        assertNull(first.announcement)
        assertEquals("origin", waiting.announcement?.key)
        assertEquals(TrackingSource.GPS, waiting.source)
        assertNull(repeated.announcement)
        assertEquals("origin", engine.getProgress().nextStopKey)
        assertTrue(engine.getProgress().arrivedAtCurrent)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun apiDepartureWindowCanTriggerVoiceWhileGpsContinuesToProveWaiting() {
        val route = listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + 10 * MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        )
        val engine = StationTrackingEngine(route)
        engine.onLocation(fix(0.0, now), now)
        val waiting = engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)
        assertNull(waiting.announcement)

        engine.updateRoute(route.map { if (it.isOrigin) it.copy(effectiveDepartureMillis = now + MINUTE) else it })
        val refreshed = engine.onTimetable(now + 4 * SECOND)

        assertEquals("origin", refreshed.announcement?.key)
        assertEquals(TrackingSource.GPS, refreshed.source)
        assertEquals(0, engine.getProgress().nextIndex)
        assertFalse(refreshed.destinationReached)
    }

    @Test
    fun earlyDepartureWithAnInwardGpsFluctuationNeverAnnouncesTheOldOrigin() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 2 * MINUTE, origin = true).copy(
                effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        val departures = listOf(50.0, 40.0, 130.0, 240.0).mapIndexed { index, position ->
            val time = now + index * 3 * SECOND
            engine.onLocation(fix(position, time, speed = 12.0), time)
        }

        assertTrue(departures.all { it.announcement?.key != "origin" })
        assertEquals("next", departures.last().stop?.key)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun originWaitingUsesRealtimeDepartureAndDoesNotReplayAnElapsedDeparture() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 3 * MINUTE, origin = true).copy(
                plannedDepartureMillis = now + MINUTE,
                effectiveDepartureMillis = now - SECOND),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now), now)
        val waiting = engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)
        val later = engine.onTimetable(now + 4 * SECOND)

        assertNull(waiting.announcement)
        assertNull(later.announcement)
        assertEquals("origin", later.stop?.key)
        assertFalse(later.destinationReached)
    }

    @Test
    fun unknownSpeedRequiresStableOriginDwellBeforeDepartureAdvice() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now, speed = null), now)
        val shortDwell = engine.onLocation(fix(0.0, now + 3 * SECOND, speed = null), now + 3 * SECOND)
        val confirmed = engine.onLocation(fix(0.0, now + 10 * SECOND, speed = null), now + 10 * SECOND)

        assertNull(shortDwell.announcement)
        assertEquals("origin", confirmed.announcement?.key)
        assertEquals(TrackingSource.GPS, confirmed.source)
        assertFalse(confirmed.destinationReached)
    }

    @Test
    fun movementDespiteReportedZeroSpeedResetsTheOriginWaitingEvidence() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now, speed = 0.0), now)
        val departing = engine.onLocation(fix(60.0, now + 3 * SECOND, speed = 0.0), now + 3 * SECOND)
        val continuing = engine.onLocation(fix(100.0, now + 6 * SECOND, speed = 0.0), now + 6 * SECOND)

        assertNull(departing.announcement)
        assertNull(continuing.announcement)
        assertEquals("origin", continuing.stop?.key)
        assertFalse(continuing.destinationReached)
    }

    @Test
    fun releasedOriginAdviceWaitsForFreshStationaryEvidenceBeforeRetrying() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now), now)
        val waiting = engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)
        assertEquals("origin", waiting.announcement?.key)
        engine.releaseAnnouncement("origin")

        val moving = engine.onLocation(fix(70.0, now + 6 * SECOND, speed = 12.0), now + 6 * SECOND)
        val jittering = engine.onLocation(fix(65.0, now + 9 * SECOND, speed = 12.0), now + 9 * SECOND)

        assertNull(moving.announcement)
        assertNull(jittering.announcement)
        assertFalse(engine.getProgress().announcedKeys.contains("origin"))
    }

    @Test
    fun twoMinuteEarlyGpsDepartureStillAnnouncesTheOrderedSuccessorBeforeArrival() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now, origin = true).copy(
                plannedDepartureMillis = now + 2 * MINUTE,
                effectiveDepartureMillis = now + 2 * MINUTE),
            stop("next", positionMeters = 1_000.0, stationId = 2, arrival = now + 4 * MINUTE),
            stop("destination", positionMeters = 2_000.0, stationId = 3, destination = true)
        ), radiusMeters = 300)
        val updates = listOf(0.0, 60.0, 130.0, 240.0, 600.0, 720.0).mapIndexed { index, position ->
            val time = now + index * 6 * SECOND
            engine.onLocation(fix(position, time, speed = 12.0), time)
        }

        assertTrue(updates.all { it.announcement?.key != "origin" })
        assertEquals("next", updates.last().announcement?.key)
        assertEquals("next", updates.last().stop?.key)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun reservedOriginAdviceIsStillRelevantOnlyBeforeItsDepartureTime() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + 30 * SECOND),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now), now)
        val event = engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)
        assertEquals("origin", event.announcement?.key)
        assertTrue(engine.getProgress().announcedKeys.contains("origin"))

        assertTrue(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 4 * SECOND))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 31 * SECOND))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now + 31 * SECOND))
    }

    @Test
    fun movementCancelsPendingOriginAdviceBeforeTheCursorLeavesTheOrigin() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(0.0, now), now)
        engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.onLocation(fix(65.0, now + 6 * SECOND, speed = 12.0), now + 6 * SECOND)

        assertEquals("origin", engine.getProgress().nextStopKey)
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 6 * SECOND))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now + 6 * SECOND))
    }

    @Test
    fun staleGpsOrAReplacedVisitInvalidatesPendingOriginAdvice() {
        val origin = stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE)
        val next = stop("next", positionMeters = 2_000.0, stationId = 2)
        val engine = StationTrackingEngine(listOf(origin, next))
        engine.onLocation(fix(0.0, now), now)
        engine.onLocation(fix(0.0, now + 3 * SECOND), now + 3 * SECOND)

        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 34 * SECOND))
        engine.updateRoute(listOf(origin.copy(key = "replacement-origin"), next))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 4 * SECOND))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now + 4 * SECOND))
    }

    @Test
    fun originAdviceRequiresConfirmedWaitingEvenIfThePhoneApproachesTheBoardingStation() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ), radiusMeters = 300)
        engine.onLocation(fix(-400.0, now, speed = 12.0), now)
        val approaching = engine.onLocation(fix(-250.0, now + 3 * SECOND, speed = 12.0), now + 3 * SECOND)

        assertEquals("origin", approaching.announcement?.key)
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now + 3 * SECOND))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now + 3 * SECOND))
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun clockOnlyOriginAdviceUsesTheFutureDepartureWindowWithoutInventingGpsEvidence() {
        val origin = stop("origin", origin = true).copy(effectiveDepartureMillis = now + MINUTE)
        val engine = StationTrackingEngine(listOf(origin))

        assertTrue(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.GPS, now))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now + 2 * MINUTE))
        engine.updateRoute(listOf(origin.copy(effectiveDepartureMillis = now + 10 * MINUTE)))
        assertFalse(engine.isOriginAnnouncementRelevant("origin", TrackingSource.TIMETABLE, now))
    }

    @Test
    fun twoFreshFixesRecoverAnObservedIntermediatePassAfterGpsOutage() {
        val engine = StationTrackingEngine(listOf(
            stop("passed"),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(-1_000.0, now), now)
        engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

        val firstAfterGap = engine.onLocation(fix(500.0, now + 40 * SECOND), now + 40 * SECOND)
        assertEquals("passed", firstAfterGap.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)

        val coherentMovement = engine.onLocation(fix(800.0, now + 44 * SECOND), now + 44 * SECOND)
        assertEquals("next", coherentMovement.stop?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun unsuccessfulSpeechCanBeRetriedThenAcknowledgedWithoutAnotherDuplicate() {
        val engine = StationTrackingEngine(listOf(stop("next")))
        engine.onLocation(fix(-1_000.0, now), now)
        val first = engine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)
        assertEquals("next", first.announcement?.key)

        engine.releaseAnnouncement("next")
        val retry = engine.onLocation(fix(-250.0, now + 9 * SECOND), now + 9 * SECOND)
        assertEquals("next", retry.announcement?.key)

        engine.acknowledgeAnnouncement("next")
        val afterAcknowledgement = engine.onLocation(fix(-200.0, now + 10 * SECOND), now + 10 * SECOND)
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
    fun firstFarGpsDoesNotLockProvisionalClockCursorBeforeLaterStationProof() {
        val stops = listOf(
            stop("origin", arrival = now - 60 * MINUTE, origin = true),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        )
        val engine = StationTrackingEngine(stops)
        assertEquals("next", engine.onTimetable(now).stop?.key)

        val farFromEitherStation = engine.onLocation(fix(500.0, now + SECOND), now + SECOND)
        assertEquals(TrackingSource.TIMETABLE, farFromEitherStation.source)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(engine.getProgress().gpsEstablished)

        val saved = Gson().fromJson(Gson().toJson(engine.getProgress()), TrackingProgress::class.java)
        val restarted = StationTrackingEngine(stops, saved)
        val actualOrigin = restarted.onLocation(fix(0.0, now + 2 * SECOND), now + 2 * SECOND)

        assertEquals(TrackingSource.GPS, actualOrigin.source)
        assertEquals("origin", actualOrigin.stop?.key)
        assertEquals(0, restarted.getProgress().nextIndex)
        assertTrue(restarted.getProgress().gpsEstablished)
        assertFalse(actualOrigin.destinationReached)
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
        val update = offlineEngine.onLocation(fix(-250.0, now + 8 * SECOND), now + 8 * SECOND)

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

    @Test
    fun replacementStopCorrectionRequiresArrivalAtTheNewPhysicalPoint() {
        val original = stop("current")
        val engine = StationTrackingEngine(listOf(original), TrackingProgress(
            nextStopKey = "current", arrivedAtCurrent = true,
            announcedKeys = setOf("current"), gpsEstablished = true
        ))

        engine.updateRoute(listOf(original.copy(longitude = 350.0 / METERS_PER_DEGREE)))

        assertEquals("current", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertEquals(setOf("current"), engine.getProgress().announcedKeys)
        val atOldStation = engine.onLocation(fix(0.0, now), now)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertNull(atOldStation.announcement)
    }

    @Test
    fun apiTimeRefreshAtTheSamePhysicalStopKeepsConfirmedArrival() {
        val original = stop("current")
        val engine = StationTrackingEngine(listOf(original), TrackingProgress(
            nextStopKey = "current", arrivedAtCurrent = true,
            announcedKeys = setOf("current"), gpsEstablished = true
        ))

        engine.updateRoute(listOf(original.copy(effectiveDepartureMillis = now + 20 * MINUTE)))

        assertTrue(engine.getProgress().arrivedAtCurrent)
        assertEquals(setOf("current"), engine.getProgress().announcedKeys)
    }

    @Test
    fun earlyBusAnnouncesTheNextCloseStopBeforeLeavingThePrevious220MeterZone() {
        val engine = StationTrackingEngine(listOf(
            stop("maubis", arrival = now + 2 * MINUTE),
            stop("rathaus", positionMeters = 180.0, stationId = 2, arrival = now + 3 * MINUTE),
            stop("martinus", positionMeters = 600.0, stationId = 3)
        ))
        engine.onLocation(fix(-350.0, now, speed = 8.0), now)
        val maubis = engine.onLocation(fix(-250.0, now + 5 * SECOND, speed = 8.0), now + 5 * SECOND)
        engine.onLocation(fix(0.0, now + 25 * SECOND, speed = 0.0), now + 25 * SECOND)
        engine.onLocation(fix(80.0, now + 35 * SECOND, speed = 8.0), now + 35 * SECOND)

        val rathaus = engine.onLocation(fix(120.0, now + 40 * SECOND, speed = 8.0), now + 40 * SECOND)
        val repeated = engine.onLocation(fix(150.0, now + 43 * SECOND, speed = 8.0), now + 43 * SECOND)

        assertEquals("maubis", maubis.announcement?.key)
        assertEquals("rathaus", rathaus.stop?.key)
        assertEquals("rathaus", rathaus.announcement?.key)
        assertEquals(TrackingSource.GPS, rathaus.source)
        assertNull(repeated.announcement)
        assertEquals(setOf("maubis", "rathaus"), engine.getProgress().announcedKeys)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun frequentSlowBusFixesAccumulateDepartureEvidenceForACloseSuccessor() {
        val engine = StationTrackingEngine(listOf(
            stop("current"),
            stop("close-next", positionMeters = 180.0, stationId = 2)
        ))
        engine.onLocation(fix(-250.0, now), now)
        engine.onLocation(fix(-80.0, now + 20 * SECOND), now + 20 * SECOND)
        engine.onLocation(fix(0.0, now + 30 * SECOND), now + 30 * SECOND)
        val announcements = mutableListOf<String>()
        listOf(24.0, 48.0, 72.0, 96.0, 120.0, 144.0).forEachIndexed { index, position ->
            val time = now + (33 + index * 3) * SECOND
            val update = engine.onLocation(fix(position, time, speed = 8.0), time)
            update.announcement?.key?.let(announcements::add)
        }

        assertEquals(listOf("close-next"), announcements)
        assertEquals("close-next", engine.getProgress().nextStopKey)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun sparseFixAtTheFollowingBusStopAnnouncesItInTheSameUpdate() {
        val engine = StationTrackingEngine(listOf(
            stop("first"),
            stop("close-next", positionMeters = 140.0, stationId = 2),
            stop("later", positionMeters = 600.0, stationId = 3)
        ))
        engine.onLocation(fix(-300.0, now), now)
        engine.onLocation(fix(-80.0, now + 10 * SECOND), now + 10 * SECOND)
        engine.onLocation(fix(0.0, now + 20 * SECOND), now + 20 * SECOND)

        val next = engine.onLocation(fix(160.0, now + 32 * SECOND, speed = 12.0), now + 32 * SECOND)

        assertEquals("close-next", next.stop?.key)
        assertEquals("close-next", next.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
        assertFalse(next.destinationReached)
    }

    @Test
    fun sparseMovementPastAnObservedStopDoesNotWaitForABusStopDwell() {
        val engine = StationTrackingEngine(listOf(
            stop("passed"),
            stop("close-next", positionMeters = 180.0, stationId = 2),
            stop("later", positionMeters = 600.0, stationId = 3)
        ))
        engine.onLocation(fix(-350.0, now, speed = 12.0), now)
        engine.onLocation(fix(-150.0, now + 15 * SECOND, speed = 12.0), now + 15 * SECOND)
        assertFalse(engine.getProgress().arrivedAtCurrent)

        val next = engine.onLocation(fix(160.0, now + 40 * SECOND, speed = 12.0), now + 40 * SECOND)

        assertEquals("close-next", next.stop?.key)
        assertEquals("close-next", next.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun sparseDepartureFixAlsoAnnouncesANormalDistanceSuccessor() {
        val engine = StationTrackingEngine(listOf(
            stop("first"),
            stop("next", positionMeters = 2_000.0, stationId = 2)
        ))
        engine.onLocation(fix(-400.0, now), now)
        engine.onLocation(fix(-80.0, now + 4 * SECOND), now + 4 * SECOND)
        engine.onLocation(fix(0.0, now + 5 * SECOND), now + 5 * SECOND)

        val next = engine.onLocation(fix(1_800.0, now + 30 * SECOND, speed = 70.0), now + 30 * SECOND)

        assertEquals("next", next.stop?.key)
        assertEquals("next", next.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun overlappingArrivalZonesAndInaccurateJitterDoNotAdvanceTheVisit() {
        val engine = StationTrackingEngine(listOf(
            stop("current"),
            stop("close-next", positionMeters = 180.0, stationId = 2)
        ))
        engine.onLocation(fix(-300.0, now), now)
        engine.onLocation(fix(-80.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.onLocation(fix(0.0, now + 4 * SECOND), now + 4 * SECOND)

        val ambiguous = engine.onLocation(fix(95.0, now + 5 * SECOND), now + 5 * SECOND)
        val inaccurate = engine.onLocation(fix(140.0, now + 6 * SECOND, accuracy = 100.0), now + 6 * SECOND)
        val jitter = engine.onLocation(fix(130.0, now + 7 * SECOND, accuracy = 100.0), now + 7 * SECOND)

        assertEquals("current", ambiguous.stop?.key)
        assertEquals("current", inaccurate.stop?.key)
        assertEquals("current", jitter.stop?.key)
        assertEquals(0, engine.getProgress().nextIndex)
    }

    @Test
    fun movingAwayFromBothStopsCannotUseTheCloseSuccessorShortcut() {
        val engine = StationTrackingEngine(listOf(
            stop("current"),
            stop("close-next", positionMeters = 180.0, stationId = 2)
        ))
        engine.onLocation(fix(-300.0, now), now)
        engine.onLocation(fix(-80.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.onLocation(fix(0.0, now + 4 * SECOND), now + 4 * SECOND)

        val oppositeDirection = engine.onLocation(fix(-160.0, now + 10 * SECOND), now + 10 * SECOND)

        assertEquals("current", oppositeDirection.stop?.key)
        assertNull(oppositeDirection.announcement)
        assertEquals(0, engine.getProgress().nextIndex)
    }

    @Test
    fun closeDestinationDoesNotCompleteDuringFastTransitionOrPass() {
        val engine = StationTrackingEngine(listOf(
            stop("current"),
            stop("destination", positionMeters = 180.0, stationId = 2, destination = true)
        ))
        engine.onLocation(fix(-300.0, now), now)
        engine.onLocation(fix(-80.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.onLocation(fix(0.0, now + 4 * SECOND), now + 4 * SECOND)

        val fastTransition = engine.onLocation(fix(140.0, now + 12 * SECOND, speed = 12.0), now + 12 * SECOND)
        assertEquals("destination", fastTransition.announcement?.key)
        assertFalse(fastTransition.destinationReached)
        assertFalse(engine.getProgress().completed)

        val movingPast = engine.onLocation(fix(230.0, now + 20 * SECOND, speed = 12.0), now + 20 * SECOND)
        assertFalse(movingPast.destinationReached)
        val stopped = engine.onLocation(fix(180.0, now + 30 * SECOND, speed = 0.0), now + 30 * SECOND)
        assertTrue(stopped.destinationReached)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun closeSuccessorRecoveryAfterAnOutageEvaluatesItsAnnouncementImmediately() {
        val engine = StationTrackingEngine(listOf(
            stop("observed"),
            stop("close-next", positionMeters = 180.0, stationId = 2)
        ))
        engine.onLocation(fix(-350.0, now), now)
        engine.onLocation(fix(-150.0, now + 5 * SECOND), now + 5 * SECOND)

        val firstAfterGap = engine.onLocation(fix(80.0, now + 45 * SECOND), now + 45 * SECOND)
        assertEquals("observed", firstAfterGap.stop?.key)
        val secondAfterGap = engine.onLocation(fix(140.0, now + 50 * SECOND), now + 50 * SECOND)

        assertEquals("close-next", secondAfterGap.stop?.key)
        assertEquals("close-next", secondAfterGap.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun lateStartBetweenCloseStopsUsesAccumulatedFreshDepartureMovement() {
        val engine = StationTrackingEngine(listOf(
            stop("origin", arrival = now - 5 * MINUTE, origin = true),
            stop("close-next", positionMeters = 180.0, stationId = 2, arrival = now + 2 * MINUTE)
        ))
        engine.onLocation(fix(130.0, now, speed = 6.7), now)
        val second = engine.onLocation(fix(150.0, now + 3 * SECOND, speed = 6.7), now + 3 * SECOND)
        assertEquals("origin", second.stop?.key)

        val third = engine.onLocation(fix(170.0, now + 6 * SECOND, speed = 6.7), now + 6 * SECOND)

        assertEquals("close-next", third.stop?.key)
        assertEquals("close-next", third.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun closeStopRecoveryAccumulatesSmallFreshFixesAfterAGap() {
        val engine = StationTrackingEngine(listOf(
            stop("observed"),
            stop("close-next", positionMeters = 180.0, stationId = 2)
        ))
        engine.onLocation(fix(-350.0, now), now)
        engine.onLocation(fix(-150.0, now + 5 * SECOND), now + 5 * SECOND)
        engine.onLocation(fix(130.0, now + 45 * SECOND, speed = 6.7), now + 45 * SECOND)
        val second = engine.onLocation(fix(150.0, now + 48 * SECOND, speed = 6.7), now + 48 * SECOND)
        assertEquals("observed", second.stop?.key)

        val third = engine.onLocation(fix(170.0, now + 51 * SECOND, speed = 6.7), now + 51 * SECOND)

        assertEquals("close-next", third.stop?.key)
        assertEquals("close-next", third.announcement?.key)
        assertEquals(1, engine.getProgress().nextIndex)
    }

    @Test
    fun closeStopsKeepSeparateVisitKeysAcrossAReturnToTheSameStation() {
        val engine = StationTrackingEngine(listOf(
            stop("first-visit", origin = true),
            stop("middle", positionMeters = 180.0, stationId = 2),
            stop("return-visit", destination = true)
        ))
        val announcements = mutableListOf<String>()
        val positions = listOf(-350.0, -250.0, 0.0, 120.0, 180.0, 60.0, 0.0)
        positions.forEachIndexed { index, position ->
            val time = now + index * 5 * SECOND
            engine.onLocation(fix(position, time), time).announcement?.key?.let(announcements::add)
        }

        assertEquals(listOf("first-visit", "middle", "return-visit"), announcements)
        assertEquals(setOf("first-visit", "middle", "return-visit"), engine.getProgress().announcedKeys)
        assertTrue(engine.getProgress().completed)
    }

    @Test
    fun restoredTunnelCursorReanchorsOnlyAfterThreeFreshUniqueFutureFixes() {
        val engine = tunnelEngine()
        val first = engine.onLocation(tunnelFix(5_870.0, now), now)
        val second = engine.onLocation(tunnelFix(5_875.0, now + 3 * SECOND), now + 3 * SECOND)

        assertEquals("bismarck", first.stop?.key)
        assertEquals(TrackingSource.TIMETABLE, first.source)
        assertEquals(TrackingSource.TIMETABLE, second.source)
        assertTrue(engine.isReacquiringLocation())
        assertNull(first.announcement)
        assertNull(second.announcement)
        val selected = engine.onLocation(tunnelFix(5_880.0, now + 6 * SECOND), now + 6 * SECOND)

        assertEquals("savigny", selected.stop?.key)
        assertEquals(TrackingSource.GPS, selected.source)
        assertFalse(engine.isReacquiringLocation())
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(selected.destinationReached)
        assertEquals(setOf("origin"), engine.getProgress().announcedKeys)
        assertNull(selected.announcement)
    }

    @Test
    fun multiStopGapRecoveryDoesNotRequireAPreGapApproachTrend() {
        val engine = tunnelEngine()
        engine.onLocation(fix(500.0, now), now)
        repeat(3) { index ->
            val time = now + (40 + index * 3) * SECOND
            engine.onLocation(tunnelFix(5_875.0, time), time)
        }

        assertEquals("savigny", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun explicitLocationInvalidationAllowsForwardRecoveryWithoutOldFixHistory() {
        val engine = tunnelEngine()
        engine.onLocation(fix(2_000.0, now), now)
        engine.onLocation(fix(2_000.0, now + 3 * SECOND), now + 3 * SECOND)
        assertTrue(engine.getProgress().arrivedAtCurrent)
        engine.invalidateLocation()
        repeat(3) { index ->
            val time = now + (10 + index * 3) * SECOND
            engine.onLocation(tunnelFix(5_875.0, time), time)
        }

        assertEquals("savigny", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun invalidationAndGpsTogglesCannotReplayAlreadyConsumedFixesAsNewVisitProof() {
        listOf(false, true).forEach { toggle ->
            val engine = tunnelEngine()
            val old = listOf(0L, 3 * SECOND, 6 * SECOND).map { elapsed ->
                fix(1_000.0, now + elapsed)
            }
            old.forEach { engine.onLocation(it, it.timeMillis) }
            if (toggle) {
                engine.setGpsEnabled(false)
                engine.setGpsEnabled(true)
            } else engine.invalidateLocation()
            // Even changed cached coordinates at these timestamps are old
            // events, not three fresh fixes proving a later physical visit.
            old.forEach { engine.onLocation(tunnelFix(5_875.0, it.timeMillis), now + 7 * SECOND) }
            assertEquals("bismarck", engine.getProgress().nextStopKey)
            assertFalse(engine.hasReliableLocation(now + 7 * SECOND))
            repeat(3) { index ->
                val time = now + (10 + index * 3) * SECOND
                engine.onLocation(tunnelFix(5_875.0, time), time)
            }
            assertEquals("savigny", engine.getProgress().nextStopKey)
        }
    }

    @Test
    fun tentativeOneHopGapRecoveryKeepsMultiStopRecoveryArmedUntilPhysicalVisitProof() {
        val engine = StationTrackingEngine(listOf(
            stop("old", stationId = 1),
            stop("unconfirmed", positionMeters = 2_000.0, stationId = 2),
            stop("missed", positionMeters = 3_000.0, stationId = 3),
            stop("later", positionMeters = 4_000.0, stationId = 4)
        ))
        engine.onLocation(fix(-300.0, now), now)
        engine.onLocation(fix(-150.0, now + 5 * SECOND), now + 5 * SECOND)
        engine.onLocation(fix(1_800.0, now + 45 * SECOND), now + 45 * SECOND)
        engine.onLocation(fix(1_850.0, now + 50 * SECOND), now + 50 * SECOND)
        assertEquals("unconfirmed", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        engine.onLocation(fix(3_800.0, now + 75 * SECOND), now + 75 * SECOND)
        assertEquals("missed", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        repeat(3) { index ->
            val time = now + (78 + index * 3) * SECOND
            engine.onLocation(fix(3_875.0, time), time)
            if (index < 2) assertEquals("missed", engine.getProgress().nextStopKey)
        }
        assertEquals("later", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        assertFalse(engine.getProgress().announcedKeys.contains("missed"))
    }

    @Test
    fun firstFarOriginFixDoesNotDisableLaterMultiStopPhysicalProof() {
        val engine = StationTrackingEngine(tunnelStops())
        engine.onLocation(tunnelFix(5_000.0, now, yMeters = 1_000.0), now)
        assertEquals("origin", engine.getProgress().nextStopKey)
        repeat(3) { index ->
            val time = now + (15 + index * 3) * SECOND
            engine.onLocation(tunnelFix(5_875.0, time), time)
        }

        assertEquals("savigny", engine.getProgress().nextStopKey)
        assertEquals(emptySet<String>(), engine.getProgress().announcedKeys)
    }

    @Test
    fun stationaryLaterVisitCanBeReacquiredWithoutClockOrMovementGuessing() {
        val stops = tunnelStops().map { it.copy(effectiveArrivalMillis = now + 2 * 60 * MINUTE,
            effectiveDepartureMillis = now + 2 * 60 * MINUTE + MINUTE) }
        val engine = tunnelEngine(stops)
        repeat(3) { index ->
            val time = now + index * 3 * SECOND
            engine.onLocation(tunnelFix(6_000.0, time), time)
        }
        assertEquals("savigny", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        engine.onLocation(tunnelFix(6_000.0, now + 9 * SECOND), now + 9 * SECOND)
        assertTrue(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun pendingRecoveryTicksCannotAnnounceOldStopOrRestoreItsGpsSource() {
        val engine = tunnelEngine()
        engine.onLocation(tunnelFix(5_875.0, now), now)
        val tick = engine.onTimetable(now + SECOND)
        val laterTick = engine.onTimetable(now + 45 * MINUTE)

        assertEquals("bismarck", tick.stop?.key)
        assertEquals(TrackingSource.TIMETABLE, tick.source)
        assertNull(tick.announcement)
        assertEquals("bismarck", laterTick.stop?.key)
        assertEquals(TrackingSource.TIMETABLE, laterTick.source)
        assertNull(laterTick.announcement)
        assertTrue(engine.isReacquiringLocation())
    }

    @Test
    fun staleAndInaccurateFixesResetFutureVisitEvidence() {
        listOf("stale", "inaccurate").forEach { invalid ->
            val engine = tunnelEngine()
            engine.onLocation(tunnelFix(5_875.0, now), now)
            engine.onLocation(tunnelFix(5_875.0, now + 3 * SECOND), now + 3 * SECOND)
            val fix = tunnelFix(5_875.0, now + 6 * SECOND,
                accuracy = if (invalid == "stale") 10.0 else 80.0)
            val invalidNow = now + (if (invalid == "stale") 40 else 6) * SECOND
            engine.onLocation(fix, invalidNow)
            engine.onLocation(tunnelFix(5_875.0, invalidNow + 3 * SECOND), invalidNow + 3 * SECOND)
            engine.onLocation(tunnelFix(5_875.0, invalidNow + 6 * SECOND), invalidNow + 6 * SECOND)
            assertEquals(invalid, "bismarck", engine.getProgress().nextStopKey)
            engine.onLocation(tunnelFix(5_875.0, invalidNow + 9 * SECOND), invalidNow + 9 * SECOND)
            assertEquals(invalid, "savigny", engine.getProgress().nextStopKey)
        }
    }

    @Test
    fun duplicateApiFixReplaysNeitherCountNorEraseFreshPendingRecoveryEvidence() {
        val engine = tunnelEngine()
        val first = tunnelFix(5_875.0, now)
        val second = tunnelFix(5_875.0, now + 3 * SECOND)
        engine.onLocation(first, now)
        repeat(3) { assertEquals(TrackingSource.TIMETABLE, engine.onLocation(first, now + SECOND).source) }
        engine.onLocation(second, now + 3 * SECOND)
        repeat(3) { engine.onLocation(second, now + 4 * SECOND) }
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        val confirmed = engine.onLocation(tunnelFix(5_875.0, now + 6 * SECOND), now + 6 * SECOND)
        assertEquals("savigny", confirmed.stop?.key)
        assertEquals(TrackingSource.GPS, confirmed.source)
    }

    @Test
    fun recentImplausibleJumpCannotBeTheFirstFutureAnchorSample() {
        val engine = tunnelEngine()
        engine.onLocation(fix(2_000.0, now), now)
        engine.onLocation(tunnelFix(5_875.0, now + SECOND), now + SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 4 * SECOND), now + 4 * SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 7 * SECOND), now + 7 * SECOND)
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        engine.onLocation(tunnelFix(5_875.0, now + 10 * SECOND), now + 10 * SECOND)
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        // Repeated short outliers cannot replace the plausible prior position.
        // An actual outage starts a separate, fresh three-fix re-anchor window.
        engine.onLocation(tunnelFix(5_875.0, now + 40 * SECOND), now + 40 * SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 43 * SECOND), now + 43 * SECOND)
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        engine.onLocation(tunnelFix(5_875.0, now + 46 * SECOND), now + 46 * SECOND)
        assertEquals("savigny", engine.getProgress().nextStopKey)
    }

    @Test
    fun repeatedPastPhysicalStopAndDuplicateStationIdCannotChooseALaterLoopVisit() {
        val base = tunnelStops()
        val routes = listOf(
            base.toMutableList().apply { this[0] = base[3].copy(key = "past-savigny", isOrigin = true) },
            base.toMutableList().apply { this[0] = base[0].copy(stationId = base[3].stationId) }
        )
        routes.forEach { stops ->
            val engine = tunnelEngine(stops)
            repeat(5) { index ->
                val time = now + index * 3 * SECOND
                engine.onLocation(tunnelFix(5_875.0, time), time)
            }
            assertEquals("bismarck", engine.getProgress().nextStopKey)
            assertFalse(engine.getProgress().completed)
        }
    }

    @Test
    fun nearbyCurrentAndFutureVisitsAreAmbiguousInsteadOfNearestMatched() {
        val stops = tunnelStops().toMutableList().apply {
            this[1] = this[1].copy(latitude = this[3].latitude,
                longitude = 5_800.0 / METERS_PER_DEGREE)
        }
        val engine = tunnelEngine(stops)
        repeat(4) { index ->
            val time = now + index * 3 * SECOND
            engine.onLocation(tunnelFix(5_875.0, time, speed = 10.0), time)
        }

        assertEquals("bismarck", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().completed)
    }

    @Test
    fun missingOrRepeatedVisitKeyCannotBecomeAForwardAnchor() {
        val base = tunnelStops()
        val routes = listOf(
            base.map { if (it.key == "savigny") it.copy(key = "") else it },
            base.map { if (it.key == "origin") it.copy(key = "savigny", stationId = null) else it }
        )
        routes.forEach { stops ->
            val engine = tunnelEngine(stops)
            repeat(4) { index ->
                val time = now + index * 3 * SECOND
                engine.onLocation(tunnelFix(5_875.0, time), time)
            }
            assertEquals("bismarck", engine.getProgress().nextStopKey)
            assertFalse(engine.getProgress().completed)
        }
    }

    @Test
    fun candidateChangesAndSecondOutagesStartNewFreshProofWindows() {
        val engine = tunnelEngine()
        engine.onLocation(tunnelFix(5_875.0, now), now)
        engine.onLocation(tunnelFix(5_875.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.onLocation(tunnelFix(3_875.0, now + 25 * SECOND), now + 25 * SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 50 * SECOND), now + 50 * SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 90 * SECOND), now + 90 * SECOND)
        engine.onLocation(tunnelFix(5_875.0, now + 93 * SECOND), now + 93 * SECOND)
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        engine.onLocation(tunnelFix(5_875.0, now + 96 * SECOND), now + 96 * SECOND)
        assertEquals("savigny", engine.getProgress().nextStopKey)
    }

    @Test
    fun selectingAnAlreadyDepartingVisitKeepsRecoveryArmedForTheFollowingVisit() {
        val engine = tunnelEngine()
        listOf(6_090.0, 6_110.0, 6_130.0).forEachIndexed { index, position ->
            val time = now + index * 3 * SECOND
            engine.onLocation(tunnelFix(position, time, speed = 10.0), time)
        }
        assertEquals("savigny", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        repeat(3) { index ->
            val time = now + (30 + index * 3) * SECOND
            engine.onLocation(tunnelFix(7_875.0, time, speed = 10.0), time)
        }
        assertEquals("destination", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().completed)
        assertEquals(setOf("origin"), engine.getProgress().announcedKeys)
    }

    @Test
    fun initialOuterCurrentVisitSelectionDoesNotDisableLaterPhysicalRecovery() {
        val route = listOf(
            stop("already-passed", stationId = 1),
            stop("later", positionMeters = 2_000.0, stationId = 2)
        )
        val engine = StationTrackingEngine(route)
        engine.onLocation(fix(130.0, now), now)
        engine.onLocation(fix(180.0, now + 3 * SECOND), now + 3 * SECOND)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        repeat(3) { index ->
            val time = now + (25 + index * 3) * SECOND
            engine.onLocation(fix(1_875.0, time), time)
        }
        assertEquals("later", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
    }

    @Test
    fun routeCoordinateRefreshInvalidatesPendingFutureSamples() {
        val stops = tunnelStops()
        val engine = tunnelEngine(stops)
        engine.onLocation(tunnelFix(5_875.0, now), now)
        engine.onLocation(tunnelFix(5_875.0, now + 3 * SECOND), now + 3 * SECOND)
        engine.updateRoute(stops.map { if (it.key == "savigny")
            it.copy(longitude = 6_010.0 / METERS_PER_DEGREE) else it })
        engine.onLocation(tunnelFix(5_880.0, now + 6 * SECOND), now + 6 * SECOND)
        engine.onLocation(tunnelFix(5_880.0, now + 9 * SECOND), now + 9 * SECOND)
        assertEquals("bismarck", engine.getProgress().nextStopKey)
        engine.onLocation(tunnelFix(5_880.0, now + 12 * SECOND), now + 12 * SECOND)
        assertEquals("savigny", engine.getProgress().nextStopKey)
    }

    @Test
    fun forwardDestinationSelectionNeedsItsOwnSubsequentArrivalFix() {
        val engine = tunnelEngine()
        repeat(3) { index ->
            val time = now + index * 3 * SECOND
            val update = engine.onLocation(tunnelFix(8_000.0, time), time)
            assertFalse(update.destinationReached)
        }
        assertEquals("destination", engine.getProgress().nextStopKey)
        assertFalse(engine.getProgress().arrivedAtCurrent)
        val arrived = engine.onLocation(tunnelFix(8_000.0, now + 9 * SECOND), now + 9 * SECOND)
        assertTrue(arrived.destinationReached)
        assertEquals(setOf("origin"), engine.getProgress().announcedKeys)
    }

    @Test
    fun completedJourneyNeverReanchorsAfterNewLocationFixes() {
        val engine = StationTrackingEngine(tunnelStops(), TrackingProgress(nextIndex = 4,
            nextStopKey = "destination", arrivedAtCurrent = true, completed = true, gpsEstablished = true))
        repeat(3) { index ->
            val time = now + index * 3 * SECOND
            engine.onLocation(tunnelFix(6_000.0, time), time)
        }
        assertEquals("destination", engine.getProgress().nextStopKey)
        assertTrue(engine.getProgress().completed)
        assertFalse(engine.isReacquiringLocation())
    }

    private fun tunnelStops(): List<TrackingStop> = listOf(
        stop("origin", stationId = 1, origin = true),
        stop("bismarck", positionMeters = 2_000.0, stationId = 2, arrival = now + 2 * MINUTE),
        stop("missed", positionMeters = 4_000.0, stationId = 3)
            .copy(latitude = 2_000.0 / METERS_PER_DEGREE),
        stop("savigny", positionMeters = 6_000.0, stationId = 4)
            .copy(latitude = 2_000.0 / METERS_PER_DEGREE),
        stop("destination", positionMeters = 8_000.0, stationId = 5, destination = true)
            .copy(latitude = 2_000.0 / METERS_PER_DEGREE)
    )

    private fun tunnelEngine(stops: List<TrackingStop> = tunnelStops()): StationTrackingEngine =
        StationTrackingEngine(stops, TrackingProgress(nextIndex = 1, nextStopKey = "bismarck",
            announcedKeys = setOf("origin"), gpsEstablished = true))

    private fun tunnelFix(xMeters: Double, time: Long, yMeters: Double = 2_000.0,
        accuracy: Double = 10.0, speed: Double? = 0.0): LocationFix =
        LocationFix(yMeters / METERS_PER_DEGREE, xMeters / METERS_PER_DEGREE, accuracy, time, speed)

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
