package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TripProgressModelTest {
    private val route = listOf(stop("origin"), stop("one"), stop("two"), stop("destination"))

    @Test
    fun boardingPlatformUsesDepartureInsteadOfDifferentArrivalPlatform() {
        val visit = route.first().copy(arrivalPlatformReal = "2", departurePlatformReal = "4")
        assertEquals("4", trackingPlatform(visit, isOrigin = true, isReplacementBus = false))
        assertEquals("2", trackingPlatform(visit, isOrigin = false, isReplacementBus = false))
    }

    @Test
    fun blankLivePlatformFallsBackToTheMatchingPlannedPlatform() {
        val visit = route.first().copy(arrivalPlatformReal = " ", arrivalPlatformPlanned = " 2 ",
            departurePlatformReal = "", departurePlatformPlanned = " 4 ", platform = "8")
        assertEquals("4", trackingPlatform(visit, isOrigin = true, isReplacementBus = false))
        assertEquals("2", trackingPlatform(visit, isOrigin = false, isReplacementBus = false))
    }

    @Test
    fun replacementBusNeverPublishesOrSpeaksRailPlatforms() {
        val visit = route.first().copy(arrivalPlatformReal = "2", departurePlatformReal = "4", platform = "8")
        assertNull(trackingPlatform(visit, isOrigin = true, isReplacementBus = true))
        assertNull(trackingPlatform(visit, isOrigin = false, isReplacementBus = true))
    }

    @Test
    fun originIsExcludedFromRemainingStops() {
        val model = model(route[0], arrived = true)
        assertEquals(3, model.totalStops)
        assertEquals(3, model.remainingStops)
        assertEquals(0, model.progress)
        assertEquals("Noch 3 Halte bis zum Ziel", model.remainingText)
    }

    @Test
    fun approachCountsNextStopAndDestination() {
        val model = model(route[2])
        assertEquals(2, model.remainingStops)
        assertEquals(1, model.passedStops)
        assertEquals(100, model.progress)
        assertEquals(300, model.progressMax)
    }

    @Test
    fun arrivalAtIntermediateStopCountsOnlyFollowingStops() {
        val model = model(route[2], arrived = true)
        assertEquals(1, model.remainingStops)
        assertEquals(200, model.progress)
        assertTrue(model.expandedText.contains("Aktuell: two"))
    }

    @Test
    fun cancelledIntermediateStopsAreExcluded() {
        val cancelled = route.toMutableList().apply { this[1] = this[1].copy(cancelled = true) }
        val model = TripProgressModel.from(cancelled, tracking(route[2]))
        assertEquals(2, model.totalStops)
        assertEquals(2, model.remainingStops)
        assertEquals(0, model.progress)
    }

    @Test
    fun cancelledCurrentVisitResolvesFollowingLiveVisit() {
        val cancelled = route.toMutableList().apply { this[1] = this[1].copy(cancelled = true) }
        val model = TripProgressModel.from(cancelled, tracking(route[1], arrived = true))
        assertEquals("two", model.nextStopName)
        assertFalse(model.arrivedAtCurrent)
        assertEquals(2, model.remainingStops)
    }

    @Test
    fun repeatedStationUsesStopoverUuidInsteadOfNumericCursor() {
        val circular = route.map { it.copy(station = TrainStation(id = 42, name = "Rundfahrt")) }
        val model = TripProgressModel.from(circular, tracking(circular[2]).copy(nextIndex = 0))
        assertEquals(2, model.remainingStops)
        assertEquals(100, model.progress)
    }

    @Test
    fun repeatedStationWithoutUuidMatchesPlannedVisit() {
        val circular = route.mapIndexed { index, stop ->
            stop.copy(uuid = null, station = TrainStation(id = 42, name = "Rundfahrt"),
                arrivalPlanned = "2026-10-05T18:0${index}:00Z")
        }
        val model = TripProgressModel.from(circular, tracking(circular[2]).copy(nextStopKey = "unmatched"))
        assertEquals(2, model.remainingStops)
    }

    @Test
    fun fallbackEngineVisitKeyWorksWithoutRawStop() {
        val noUuid = route.map { it.copy(uuid = null) }
        val visit = noUuid[2]
        val key = "${visit.stationId}:${visit.arrivalPlanned}:${visit.departurePlanned}:2"
        val model = TripProgressModel.from(noUuid, tracking(null).copy(nextStopKey = key))
        assertEquals("two", model.nextStopName)
        assertEquals(2, model.remainingStops)
    }

    @Test
    fun missingVisitDoesNotClaimNumericOrTimetableProgress() {
        val model = TripProgressModel.from(route, tracking(stop("missing")).copy(nextIndex = 3))
        assertNull(model.progress)
        assertNull(model.remainingStops)
        assertEquals("Fortschritt wird ermittelt", model.remainingText)
        assertFalse(model.shouldPromote)
    }

    @Test
    fun ambiguousRepeatedStationIsNotAssignedToAnArbitraryVisit() {
        val ambiguous = route.map { it.copy(uuid = null, station = TrainStation(id = 42, name = "Rundfahrt")) }
        val model = TripProgressModel.from(ambiguous, tracking(ambiguous[2]).copy(nextStopKey = "unknown"))
        assertNull(model.remainingStops)
        assertNull(model.progress)
    }

    @Test
    fun clockPastLastStopCannotClaimCompletion() {
        val model = TripProgressModel.from(route, tracking(null).copy(nextIndex = route.size, source = TrackingSource.TIMETABLE))
        assertNull(model.progress)
        assertFalse(model.completed)
        assertFalse(model.shouldPromote)
        assertTrue(model.approximate)
    }

    @Test
    fun unconfirmedArrivalAtLastStopKeepsProgressBelowMaximum() {
        val model = model(route.last(), arrived = true)
        assertEquals(3, model.passedStops)
        assertEquals(0, model.remainingStops)
        assertEquals("Am Ziel · Ankunft wird geprüft", model.remainingText)
        assertEquals(299, model.progress)
        assertEquals(300, model.progressMax)
        assertEquals("destination", model.nextStopName)
        assertTrue(model.arrivedAtCurrent)
        assertFalse(model.completed)
    }

    @Test
    fun approachingDestinationStillCountsItAsRemaining() {
        val model = model(route.last())
        assertEquals(2, model.passedStops)
        assertEquals(1, model.remainingStops)
        assertEquals("Noch 1 Halt bis zum Ziel", model.remainingText)
        assertEquals(200, model.progress)
        assertFalse(model.arrivedAtCurrent)
        assertFalse(model.completed)
    }

    @Test
    fun arrivalWithOnlyDestinationRemainingDoesNotClaimCompletion() {
        val direct = listOf(route.first(), route.last())
        val model = TripProgressModel.from(direct, tracking(direct.last(), arrived = true))
        assertEquals(1, model.totalStops)
        assertEquals(1, model.passedStops)
        assertEquals(0, model.remainingStops)
        assertEquals("Am Ziel · Ankunft wird geprüft", model.remainingText)
        assertEquals(99, model.progress)
        assertEquals(100, model.progressMax)
        assertFalse(model.completed)
    }

    @Test
    fun destinationArrivalExcludesAllCancelledIntermediateVisits() {
        val cancelled = route.mapIndexed { index, stop ->
            if (index in 1..2) stop.copy(cancelled = true) else stop
        }
        val model = TripProgressModel.from(cancelled, tracking(cancelled.last(), arrived = true))
        assertEquals(1, model.totalStops)
        assertEquals(1, model.passedStops)
        assertEquals(0, model.remainingStops)
        assertEquals(99, model.progress)
        assertEquals("Am Ziel · Ankunft wird geprüft", model.remainingText)
        assertFalse(model.completed)
    }

    @Test
    fun cancelledDestinationCannotBeCountedAsAnArrivedVisit() {
        val cancelled = route.toMutableList().apply { this[3] = this[3].copy(cancelled = true) }
        val model = TripProgressModel.from(cancelled, tracking(cancelled.last(), arrived = true))
        assertEquals(2, model.totalStops)
        assertNull(model.passedStops)
        assertNull(model.remainingStops)
        assertNull(model.progress)
        assertEquals("Zielhalt entfällt", model.remainingText)
        assertFalse(model.arrivedAtCurrent)
        assertFalse(model.completed)
    }

    @Test
    fun confirmedCompletionIsFullEvenWhenCursorHasMovedBeyondRoute() {
        val model = TripProgressModel.from(route, tracking(null).copy(completed = true, nextIndex = route.size))
        assertEquals(300, model.progress)
        assertEquals(0, model.remainingStops)
        assertEquals("Ziel erreicht", model.remainingText)
        assertFalse(model.shouldPromote)
    }

    @Test
    fun timetableSourceIsMarkedApproximateAndArrivalLabelIsNotGpsEta() {
        val model = TripProgressModel.from(route, tracking(route[2]).copy(source = TrackingSource.TIMETABLE), zoneId = ZoneOffset.UTC)
        assertTrue(model.approximate)
        assertEquals("Fahrplan · ungefähr", model.sourceLabel)
        assertEquals("Ankunft Ziel: 18:00 (Fahrplan)", model.arrivalText)
    }

    @Test
    fun realtimeArrivalOverridesPlannedArrival() {
        val realtime = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "2026-10-05T18:08:00Z") }
        val model = TripProgressModel.from(realtime, tracking(route[2]), zoneId = ZoneOffset.UTC)
        assertEquals("Ankunft Ziel: 18:08 (API-Echtzeit)", model.arrivalText)
    }

    @Test
    fun invalidRealtimeArrivalFallsBackToValidSchedule() {
        val invalid = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "invalid") }
        val model = TripProgressModel.from(invalid, tracking(route[2]), zoneId = ZoneOffset.UTC)
        assertEquals("Ankunft Ziel: 18:00 (Fahrplan)", model.arrivalText)
    }

    @Test
    fun freshGpsDestinationEstimateOverridesProviderTime() {
        val realtime = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "2026-10-05T18:08:00Z") }
        val live = tracking(realtime[2]).copy(gpsTimes = gpsTimes(realtime.last()))
        val model = TripProgressModel.from(realtime, live, zoneId = ZoneOffset.UTC, nowMillis = nowMillis)
        assertEquals("Ankunft Ziel: 18:04 (GPS-Schätzung)", model.arrivalText)
        assertFalse(model.completed)
        assertEquals(2, model.remainingStops)
    }

    @Test
    fun expiredGpsDestinationEstimateFallsBackToApi() {
        val realtime = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "2026-10-05T18:08:00Z") }
        val live = tracking(realtime[2]).copy(gpsTimes = gpsTimes(realtime.last()))
        val model = TripProgressModel.from(realtime, live, zoneId = ZoneOffset.UTC, nowMillis = nowMillis + 30_001)
        assertEquals("Ankunft Ziel: 18:08 (API-Echtzeit)", model.arrivalText)
    }

    @Test
    fun timetableModeIgnoresEvenFreshGpsTimes() {
        val realtime = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "2026-10-05T18:08:00Z") }
        val live = tracking(realtime[2]).copy(source = TrackingSource.TIMETABLE, gpsTimes = gpsTimes(realtime.last()))
        val model = TripProgressModel.from(realtime, live, zoneId = ZoneOffset.UTC, nowMillis = nowMillis)
        assertEquals("Ankunft Ziel: 18:08 (API-Echtzeit)", model.arrivalText)
        assertTrue(model.approximate)
    }

    @Test
    fun observedDestinationArrivalIsLabelledSeparately() {
        val live = tracking(route.last(), arrived = true).copy(completed = true,
            gpsTimes = gpsTimes(route.last(), observed = true))
        val model = TripProgressModel.from(route, live, zoneId = ZoneOffset.UTC, nowMillis = nowMillis)
        assertEquals("Ankunft Ziel: 18:01 (GPS beobachtet)", model.arrivalText)
        assertTrue(model.completed)
    }

    @Test
    fun anotherVisitCannotSupplyDestinationTime() {
        val realtime = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = "2026-10-05T18:08:00Z") }
        val otherVisit = realtime.last().copy(uuid = "earlier-visit")
        val live = tracking(realtime[2]).copy(gpsTimes = gpsTimes(otherVisit))
        val model = TripProgressModel.from(realtime, live, zoneId = ZoneOffset.UTC, nowMillis = nowMillis)
        assertEquals("Ankunft Ziel: 18:08 (API-Echtzeit)", model.arrivalText)
    }

    @Test
    fun gpsFallbackPreservesManualDestinationCorrection() {
        val model = TripProgressModel.from(route, tracking(route[2]), zoneId = ZoneOffset.UTC,
            nowMillis = nowMillis, manualDestinationArrival = "2026-10-05T18:12:00Z")
        assertEquals("Ankunft Ziel: 18:12 (Manuell)", model.arrivalText)
        assertNull(route.last().arrivalReal)
    }

    @Test
    fun manuallyProjectedServiceRouteKeepsManualArrivalSource() {
        val manualArrival = "2026-10-05T18:12:00Z"
        val projected = route.toMutableList().apply { this[3] = this[3].copy(arrivalReal = manualArrival) }
        val live = tracking(projected.last(), arrived = true).copy(source = TrackingSource.TIMETABLE)
        val model = TripProgressModel.from(projected, live, zoneId = ZoneOffset.UTC,
            nowMillis = nowMillis, manualDestinationArrival = manualArrival)
        assertEquals("Ankunft Ziel: 18:12 (Manuell)", model.arrivalText)
        assertEquals("Am Ziel · Ankunft wird geprüft", model.remainingText)
        assertEquals(0, model.remainingStops)
        assertEquals(299, model.progress)
        assertFalse(model.completed)
        assertEquals(manualArrival, projected.last().arrivalReal)
        assertNull(route.last().arrivalReal)
    }

    @Test
    fun cancelledDestinationNeverShowsArrivalPrediction() {
        val cancelled = route.toMutableList().apply { this[3] = this[3].copy(cancelled = true) }
        val model = TripProgressModel.from(cancelled, tracking(route[2]))
        assertEquals("Zielhalt entfällt", model.remainingText)
        assertNull(model.arrivalText)
        assertTrue(model.destinationCancelled)
    }

    @Test
    fun emptyOrOriginOnlyRouteNeverPromotes() {
        assertFalse(TripProgressModel.from(emptyList(), tracking(null)).shouldPromote)
        assertFalse(TripProgressModel.from(route.take(1), tracking(route[0])).shouldPromote)
    }

    private fun model(stop: StopStation, arrived: Boolean = false) = TripProgressModel.from(route, tracking(stop, arrived))

    private val nowMillis = Instant.parse("2026-10-05T18:01:00Z").toEpochMilli()

    private fun gpsTimes(stop: StopStation, observed: Boolean = false) = GpsJourneyTimes(
        updatedAtMillis = nowMillis,
        validUntilMillis = nowMillis + 30_000,
        stopTimes = listOf(GpsStopTime(
            stopKey = stop.uuid!!,
            stationId = stop.stationId,
            plannedArrivalMillis = Instant.parse(stop.arrivalPlanned).toEpochMilli(),
            plannedDepartureMillis = null,
            arrivalMillis = if (observed) nowMillis else Instant.parse("2026-10-05T18:04:00Z").toEpochMilli(),
            departureMillis = null,
            arrivalObserved = observed
        ))
    )

    private fun stop(name: String) = StopStation(
        uuid = name,
        station = TrainStation(id = name.hashCode(), name = name),
        arrivalPlanned = "2026-10-05T18:00:00Z"
    )

    private fun tracking(stop: StopStation?, arrived: Boolean = false) = TrackingLiveState(
        statusId = 7,
        nextStopKey = stop?.uuid,
        nextIndex = 0,
        stop = stop,
        arrivedAtCurrent = arrived,
        completed = false,
        source = TrackingSource.GPS
    )
}
