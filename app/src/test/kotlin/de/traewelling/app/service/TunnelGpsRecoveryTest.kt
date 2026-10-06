package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Synthetic geometry only: these names do not describe a recorded Essen trajectory. */
class TunnelGpsRecoveryTest {
    private val base = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val route = listOf(
        stop("origin", 1, 50.0, .0, null, base, origin = true),
        stop("bismarckplatz", 2, 50.0, .02, base + 120_000, base + 150_000),
        stop("underground", 3, 50.02, .04, base + 270_000, base + 300_000),
        stop("savignystr", 4, 50.02, .06, base + 420_000, base + 450_000),
        stop("destination", 5, 50.02, .08, base + 570_000, base + 600_000, destination = true)
    )
    private val provider = route.map { visit ->
        StopStation(uuid = visit.key, station = TrainStation(id = visit.stationId,
            latitude = visit.latitude, longitude = visit.longitude),
            arrivalPlanned = iso(visit.plannedArrivalMillis), departurePlanned = iso(visit.plannedDepartureMillis),
            arrivalReal = iso(visit.effectiveArrivalMillis), departureReal = iso(visit.effectiveDepartureMillis))
    }

    @Test
    fun tunnelExpiresThePreviousForecastWhileKeepingTheProtectedVisitAndApiTimes() {
        val session = Session()
        val beforeTunnel = session.preGapForecast()
        assertEquals(JourneyTimeSource.GPS_ESTIMATE,
            JourneyTimeResolver.arrival(provider[1], beforeTunnel, base + 42_000)?.source)

        val fallback = session.engine.onTimetable(base + 500_000)
        val absent = session.estimator.update(route, session.engine.getProgress(), fallback.source,
            null, base + 500_000)

        assertEquals(TrackingSource.TIMETABLE, fallback.source)
        assertEquals("bismarckplatz", fallback.stop?.key)
        assertNull(absent)
        assertEquals(JourneyTimeSource.API_REALTIME,
            JourneyTimeResolver.arrival(provider[1], beforeTunnel, base + 500_000)?.source)
        assertFalse(fallback.destinationReached)
        assertFalse(session.engine.getProgress().completed)
    }

    @Test
    fun returningGpsKeepsApiFallbackUntilTheNewVisitHasFreshIndependentEvidence() {
        val session = Session()
        session.preGapForecast()
        val first = session.accept(surfaceFix(.0582, 500_000))
        val second = session.accept(surfaceFix(.0583, 505_000))

        for (step in listOf(first, second)) {
            assertEquals(TrackingSource.TIMETABLE, step.update.source)
            assertEquals("bismarckplatz", step.update.stop?.key)
            assertNull(step.times)
            assertEquals(JourneyTimeSource.API_REALTIME,
                JourneyTimeResolver.arrival(provider[3], step.times, step.fix.timeMillis)?.source)
            assertFalse(step.update.destinationReached)
        }
    }

    @Test
    fun restoredTunnelCursorUsesTheSameFreshEvidenceBeforePublishingGpsTimes() {
        val session = Session(restored = true)
        val steps = session.recover()
        assertTrue(steps.take(2).all { it.update.source == TrackingSource.TIMETABLE && it.times == null })
        assertEquals("savignystr", session.engine.getProgress().nextStopKey)
        assertEquals(TrackingSource.GPS, steps.last().update.source)
        assertEquals(JourneyTimeSource.GPS_OBSERVED,
            JourneyTimeResolver.arrival(provider[3], steps.last().times, steps.last().fix.timeMillis)?.source)
        assertFalse(session.engine.getProgress().completed)
    }

    @Test
    fun restoredSurfaceVisitAllowsTheEstimatorToLeaveTheObsoleteCorridor() {
        val session = Session()
        session.preGapForecast()
        // The former visit's incoming section is on another parallel latitude.
        val staleCursorEstimator = GpsJourneyTimeEstimator()
        val firstSurfaceFix = surfaceFix(.0582, 500_000)
        assertNull(staleCursorEstimator.update(route, staleProgress(), TrackingSource.GPS,
            firstSurfaceFix, firstSurfaceFix.timeMillis))
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, staleCursorEstimator.unavailableReason())

        val steps = session.recover()
        val last = steps.last()
        assertEquals("savignystr", session.engine.getProgress().nextStopKey)
        assertEquals(TrackingSource.GPS, last.update.source)
        assertNotNull(last.times)
        assertNull(session.estimator.unavailableReason())
        assertEquals(JourneyTimeSource.GPS_OBSERVED,
            JourneyTimeResolver.arrival(provider[3], last.times, last.fix.timeMillis)?.source)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE,
            JourneyTimeResolver.arrival(provider.last(), last.times, last.fix.timeMillis)?.source)
        assertFalse(last.update.destinationReached)
    }

    @Test
    fun skippedTunnelVisitsReceiveNoInventedActualEventsOrRetrospectiveSpeech() {
        val session = Session()
        session.preGapForecast()
        val steps = session.recover()
        val hiddenKeys = setOf("bismarckplatz", "underground")

        assertTrue(steps.none { it.update.announcement?.key in hiddenKeys })
        assertTrue(session.engine.getProgress().announcedKeys.none { it in hiddenKeys })
        for (step in steps) {
            assertTrue(step.times?.stopTimes.orEmpty().none {
                it.stopKey in hiddenKeys && (it.arrivalObserved || it.departureObserved)
            })
        }
        // Skipping an unobserved visit neither supplies measured times nor updates provider fields.
        assertTrue(steps.last().times!!.stopTimes.none { it.stopKey in hiddenKeys })
        assertEquals(base + 180_000, route[1].effectiveArrivalMillis)
        assertEquals(base + 330_000, route[2].effectiveArrivalMillis)
        assertEquals(iso(base + 330_000), provider[2].arrivalReal)
    }

    @Test
    fun departureFromTheRecoveredVisitBuildsForecastsOnItsActualNextSection() {
        val session = Session()
        session.preGapForecast()
        session.recover()
        session.accept(surfaceFix(.0610, 530_000, 12.0))
        session.accept(surfaceFix(.0635, 540_000, 12.0))
        session.accept(surfaceFix(.0650, 550_000, 12.0))
        session.accept(surfaceFix(.0660, 556_000, 12.0))
        val last = session.accept(surfaceFix(.0670, 562_000, 12.0))

        assertEquals("destination", session.engine.getProgress().nextStopKey)
        assertEquals(TrackingSource.GPS, last.update.source)
        assertNull(session.estimator.unavailableReason())
        val arrival = JourneyTimeResolver.arrival(provider.last(), last.times, last.fix.timeMillis)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE, arrival?.source)
        assertEquals(base + 640_000, arrival?.millis)
        assertTrue(last.times!!.stopTimes.none { it.stopKey == "underground" })
        assertFalse(last.update.destinationReached)
        assertFalse(session.engine.getProgress().completed)
    }

    @Test
    fun recoveryAndAnotherSignalLossLeaveProviderAndManualValuesUntouched() {
        val originalProvider = provider.map { it.copy(station = it.station?.copy()) }
        val originalTracking = route.map { it.copy() }
        val manualArrival = iso(base + 850_000)!!
        val manualDeparture = iso(base + 80_000)!!
        val session = Session()
        session.preGapForecast()
        val recovered = session.recover().last()
        assertEquals(JourneyTimeSource.GPS_ESTIMATE,
            JourneyTimeResolver.arrival(provider.last(), recovered.times,
                recovered.fix.timeMillis, manualArrival)?.source)

        val clock = session.engine.onTimetable(base + 600_000)
        val unavailable = session.estimator.update(route, session.engine.getProgress(), clock.source,
            null, base + 600_000)
        val manual = JourneyTimeResolver.arrival(provider.last(), unavailable, base + 600_000, manualArrival)
        assertEquals(JourneyTimeSource.MANUAL, manual?.source)
        assertEquals(base + 850_000, manual?.millis)
        assertEquals(base + 80_000,
            JourneyTimeResolver.departure(provider.first(), unavailable, base + 600_000, manualDeparture)?.millis)
        assertEquals(originalProvider, provider)
        assertEquals(originalTracking, route)
        assertFalse(clock.destinationReached)
    }

    @Test
    fun explicitLocationInvalidationCannotReviveAnAlreadyConsumedGpsForecast() {
        val session = Session()
        session.preGapForecast()
        session.engine.invalidateLocation()
        session.estimator.invalidateLocation()
        val consumed = LocationFix(50.0, .007, 10.0, base + 42_000, 12.0)
        repeat(3) {
            val update = session.engine.onLocation(consumed, base + 43_000)
            val revived = session.estimator.update(route, session.engine.getProgress(), update.source,
                consumed, base + 43_000)
            assertNull(revived)
        }
        val restored = session.recover().last()
        assertEquals("savignystr", restored.update.stop?.key)
        assertEquals(TrackingSource.GPS, restored.update.source)
        assertNotNull(restored.times)
    }

    @Test
    fun selectingTheTerminalAfterATunnelDoesNotInventArrivalOrFinishTheJourney() {
        val session = Session()
        session.preGapForecast()
        val steps = listOf(.0782, .0783, .0784).mapIndexed { index, longitude ->
            session.accept(surfaceFix(longitude, 600_000L + index * 5_000))
        }
        assertEquals("destination", session.engine.getProgress().nextStopKey)
        assertEquals(TrackingSource.GPS, steps.last().update.source)
        assertFalse(session.engine.getProgress().completed)
        assertFalse(steps.last().update.destinationReached)
        assertTrue(steps.last().times?.stopTimes.orEmpty().none {
            it.stopKey == "destination" && it.arrivalObserved
        })
        assertTrue(steps.none { it.update.announcement?.key in setOf("bismarckplatz", "underground", "savignystr") })
    }

    private data class Step(val update: TrackingUpdate, val times: GpsJourneyTimes?, val fix: LocationFix)

    private inner class Session(restored: Boolean = false) {
        val engine = StationTrackingEngine(route, if (restored) staleProgress() else TrackingProgress())
        val estimator = GpsJourneyTimeEstimator()

        fun accept(fix: LocationFix): Step {
            val update = engine.onLocation(fix, fix.timeMillis)
            val times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis)
            return Step(update, times, fix)
        }

        fun preGapForecast(): GpsJourneyTimes {
            // Establish an uninterrupted origin visit and real departure first;
            // the later tunnel recovery is distinct from cache restoration.
            accept(LocationFix(50.0, .0, 10.0, base, 1.0))
            accept(LocationFix(50.0, .0, 10.0, base + 5_000, 1.0))
            accept(LocationFix(50.0, .001, 10.0, base + 10_000, 12.0))
            accept(LocationFix(50.0, .0035, 10.0, base + 20_000, 12.0))
            var times: GpsJourneyTimes? = null
            for (fraction in listOf(.25, .30, .35)) {
                times = accept(LocationFix(50.0, .02 * fraction, 10.0,
                    base + (120_000 * fraction).toLong(), 12.0)).times
            }
            return times!!
        }

        fun recover(): List<Step> = listOf(.0582, .0583, .0584, .0590, .05905)
            .mapIndexed { index, longitude ->
                val speed = if (index >= 3) 1.0 else 10.0
                accept(surfaceFix(longitude, 500_000L + index * 5_000, speed))
            }
    }

    private fun staleProgress() = TrackingProgress(nextIndex = 1, nextStopKey = "bismarckplatz", gpsEstablished = true)

    private fun surfaceFix(longitude: Double, elapsed: Long, speed: Double = 10.0) =
        LocationFix(50.02, longitude, 10.0, base + elapsed, speed)

    private fun stop(key: String, id: Int, latitude: Double, longitude: Double, arrival: Long?, departure: Long?,
        origin: Boolean = false, destination: Boolean = false) = TrackingStop(
        key, id, key, latitude, longitude, arrival, arrival?.let { it + 60_000 },
        departure?.let { it + 60_000 }, isOrigin = origin, isDestination = destination,
        plannedDepartureMillis = departure)

    private fun iso(time: Long?): String? = time?.let { Instant.ofEpochMilli(it).toString() }
}
