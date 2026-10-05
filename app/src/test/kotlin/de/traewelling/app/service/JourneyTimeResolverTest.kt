package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class JourneyTimeResolverTest {
    private val plannedArrival = instant("2026-10-05T18:14:00Z")
    private val plannedDeparture = instant("2026-10-05T18:15:00Z")
    private val now = instant("2026-10-05T18:12:00Z")
    private val stop = StopStation(uuid = "maubisstr", station = TrainStation(id = 7, name = "Maubisstr."),
        arrivalPlanned = iso(plannedArrival), departurePlanned = iso(plannedDeparture),
        arrivalReal = iso(plannedArrival + 5 * MINUTE), departureReal = iso(plannedDeparture + 5 * MINUTE))

    @Test fun freshGpsPredictionTakesPriorityOverApiAndManualTime() {
        val arrival = JourneyTimeResolver.arrival(stop, gps(), now, iso(plannedArrival + 8 * MINUTE))!!
        assertEquals(plannedArrival - 2 * MINUTE, arrival.millis)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE, arrival.source)
        assertEquals(-2L, arrival.delayMinutes)
        assertEquals("GPS-Schätzung", arrival.sourceLabel)
    }

    @Test fun observedGpsArrivalIsDistinguishedFromAnEstimatedDeparture() {
        val times = gps(arrivalObserved = true)
        assertEquals(JourneyTimeSource.GPS_OBSERVED, JourneyTimeResolver.arrival(stop, times, now)!!.source)
        assertEquals("GPS beobachtet", JourneyTimeResolver.arrival(stop, times, now)!!.sourceLabel)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE, JourneyTimeResolver.departure(stop, times, now)!!.source)
    }

    @Test fun observedGpsDepartureIsDistinguishedFromAnEstimatedArrival() {
        val times = gps(departureObserved = true)
        assertEquals(JourneyTimeSource.GPS_OBSERVED, JourneyTimeResolver.departure(stop, times, now)!!.source)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE, JourneyTimeResolver.arrival(stop, times, now)!!.source)
    }

    @Test fun expiredGpsImmediatelyFallsBackToUnmodifiedApiTime() {
        val arrival = JourneyTimeResolver.arrival(stop, gps(), now + 30_001)!!
        assertEquals(plannedArrival + 5 * MINUTE, arrival.millis)
        assertEquals(JourneyTimeSource.API_REALTIME, arrival.source)
        assertEquals("API-Echtzeit", arrival.sourceLabel)
    }

    @Test fun futureDatedGpsSnapshotDoesNotOverrideApi() {
        assertEquals(JourneyTimeSource.API_REALTIME,
            JourneyTimeResolver.arrival(stop, gps().copy(updatedAtMillis = now + 1), now)!!.source)
    }

    @Test fun missingGpsArrivalUsesApiEvenWhenGpsDepartureExists() {
        val times = gps(arrival = null)
        assertEquals(JourneyTimeSource.API_REALTIME, JourneyTimeResolver.arrival(stop, times, now)!!.source)
        assertEquals(JourneyTimeSource.GPS_ESTIMATE, JourneyTimeResolver.departure(stop, times, now)!!.source)
    }

    @Test fun invalidGpsTimeNeverReplacesAValidApiTime() {
        assertEquals(JourneyTimeSource.API_REALTIME,
            JourneyTimeResolver.arrival(stop, gps(arrival = -1), now)!!.source)
    }

    @Test fun anotherVisitToSameStationCannotTakeItsGpsTime() {
        val secondVisit = stop.copy(uuid = "maubisstr-return", arrivalPlanned = iso(plannedArrival + 60 * MINUTE))
        assertEquals(JourneyTimeSource.API_REALTIME, JourneyTimeResolver.arrival(secondVisit, gps(), now)!!.source)
    }

    @Test fun missingUuidStillMatchesTheSpecificPlannedVisitAcrossOffsets() {
        val withoutUuid = stop.copy(uuid = null, arrivalPlanned = "2026-10-05T20:14:00+02:00")
        val times = gps().let { it.copy(stopTimes = it.stopTimes.map { entry -> entry.copy(stopKey = "7:visit:0") }) }
        assertEquals(JourneyTimeSource.GPS_ESTIMATE,
            JourneyTimeResolver.arrival(withoutUuid, times, now)!!.source)
    }

    @Test fun ambiguousGpsVisitTimesUseApiInsteadOfPickingTheFirst() {
        val times = gps().let { it.copy(stopTimes = it.stopTimes + it.stopTimes) }
        assertEquals(JourneyTimeSource.API_REALTIME, JourneyTimeResolver.arrival(stop, times, now)!!.source)
    }

    @Test fun userManualTimeRemainsTheFallbackAfterGpsExpires() {
        val manual = plannedArrival + 2 * MINUTE
        val arrival = JourneyTimeResolver.arrival(stop, gps(), now + 30_001, iso(manual))!!
        assertEquals(manual, arrival.millis)
        assertEquals(JourneyTimeSource.MANUAL, arrival.source)
        assertEquals("Manuell", arrival.sourceLabel)
    }

    @Test fun invalidManualTimeFallsBackToValidApiTime() {
        assertEquals(plannedDeparture + 5 * MINUTE,
            JourneyTimeResolver.departure(stop, null, now, "invalid")!!.millis)
    }

    @Test fun malformedApiArrivalDoesNotHideValidPlan() {
        val malformed = stop.copy(arrivalReal = "invalid")
        val arrival = JourneyTimeResolver.arrival(malformed, null, now)!!
        assertEquals(plannedArrival, arrival.millis)
        assertEquals(JourneyTimeSource.TIMETABLE, arrival.source)
        assertEquals("Fahrplan", arrival.sourceLabel)
    }

    @Test fun blankApiDepartureDoesNotHideValidPlan() {
        val arrival = JourneyTimeResolver.departure(stop.copy(departureReal = ""), null, now)!!
        assertEquals(plannedDeparture, arrival.millis)
        assertEquals(JourneyTimeSource.TIMETABLE, arrival.source)
    }

    @Test fun earlyApiTimeIsPreservedWithoutInheritedDelayClamping() {
        val early = plannedArrival - 2 * MINUTE
        val arrival = JourneyTimeResolver.arrival(stop.copy(arrivalReal = iso(early)), null, now)!!
        assertEquals(early, arrival.millis)
        assertEquals(-2L, arrival.delayMinutes)
        assertEquals(JourneyTimeSource.API_REALTIME, arrival.source)
    }

    @Test fun realtimeCanBeDisplayedWithoutAPlanOrDelay() {
        val arrival = JourneyTimeResolver.arrival(stop.copy(arrivalPlanned = null), null, now)!!
        assertEquals(plannedArrival + 5 * MINUTE, arrival.millis)
        assertNull(arrival.delayMinutes)
        assertNull(arrival.plannedMillis)
    }

    @Test fun resolvingGpsNeverMutatesTheApiStopOrChangesAnEditorFallback() {
        val original = stop.copy()
        JourneyTimeResolver.arrival(stop, gps(), now)
        JourneyTimeResolver.departure(stop, gps(), now)
        assertEquals(original, stop)
        assertEquals(plannedArrival + 5 * MINUTE, JourneyTimeResolver.arrival(stop, null, now)!!.millis)
        assertEquals(plannedDeparture + 5 * MINUTE, JourneyTimeResolver.departure(stop, null, now)!!.millis)
        assertEquals("maubisstr", stop.uuid)
        assertEquals(iso(plannedArrival), stop.arrivalPlanned)
    }

    @Test fun unavailableStopOrAllInvalidTimesStayUnknown() {
        assertNull(JourneyTimeResolver.arrival(null, gps(), now))
        assertNull(JourneyTimeResolver.departure(stop.copy(departureReal = "bad", departurePlanned = "bad"), null, now))
    }

    @Test fun manualClockProjectionAppliesBothEventsAtTheSameConcreteVisit() {
        val result = JourneyTimeResolver.manualTimelineStops(listOf(stop), stop, stop,
            iso(plannedDeparture - MINUTE), iso(plannedArrival - MINUTE)).single()
        assertEquals(iso(plannedDeparture - MINUTE), result.departureReal)
        assertEquals(iso(plannedArrival - MINUTE), result.arrivalReal)
        assertEquals("maubisstr", result.uuid)
        assertEquals(stop.arrivalPlanned, result.arrivalPlanned)
        assertEquals(stop.departurePlanned, result.departurePlanned)
        assertEquals(iso(plannedArrival + 5 * MINUTE), stop.arrivalReal)
    }

    @Test fun manualClockProjectionNeverChangesAnotherVisitToTheSameStation() {
        val repeated = stop.copy(uuid = "maubisstr-return", arrivalPlanned = iso(plannedArrival + 60 * MINUTE))
        val projected = JourneyTimeResolver.manualTimelineStops(listOf(stop, repeated), stop, repeated,
            iso(plannedDeparture - MINUTE), iso(plannedArrival + 62 * MINUTE))
        assertEquals(stop.arrivalReal, projected[0].arrivalReal)
        assertEquals(repeated.departureReal, projected[1].departureReal)
        assertEquals(iso(plannedArrival + 62 * MINUTE), projected[1].arrivalReal)
    }

    @Test fun malformedManualClockTimesPreserveApiEvents() {
        assertEquals(listOf(stop), JourneyTimeResolver.manualTimelineStops(listOf(stop), stop, stop, "bad", "bad"))
    }

    @Test fun ambiguousManualClockVisitIsNotAppliedToBothRows() {
        val ambiguous = listOf(stop, stop.copy())
        assertEquals(ambiguous, JourneyTimeResolver.manualTimelineStops(ambiguous, stop, stop,
            iso(plannedDeparture - MINUTE), iso(plannedArrival - MINUTE)))
    }

    private fun gps(arrival: Long? = plannedArrival - 2 * MINUTE,
                    departure: Long? = plannedDeparture - 2 * MINUTE,
                    arrivalObserved: Boolean = false, departureObserved: Boolean = false): GpsJourneyTimes =
        GpsJourneyTimes(now, now + 30_000, listOf(GpsStopTime("maubisstr", 7, plannedArrival,
            plannedDeparture, arrival, departure, arrivalObserved, departureObserved)))

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun iso(value: Long): String = Instant.ofEpochMilli(value).toString()

    companion object { private const val MINUTE = 60_000L }
}
