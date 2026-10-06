package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripChangeMonitorTest {
    private val minute = 60_000L
    private fun stop(key: String = "next", delay: Int? = 0, platform: String? = "2", cancelled: Boolean? = false,
                     destination: Boolean = false, origin: Boolean = false, planned: Long = 1_000_000L): TripChangeStop =
        TripChangeStop(key, "Station $key", planned, delay?.let { planned + it * minute }, planned,
            delay?.let { planned + it * minute }, platform, platform, cancelled, origin, destination)

    private fun snapshot(stops: List<TripChangeStop>, serial: Long, nextIndex: Int = 0, statusId: Int = 42,
                         manualArrival: Long? = null, manualDeparture: Long? = null): TripChangeSnapshot =
        TripChangeSnapshot(statusId, serial, stops, nextIndex, manualDeparture, manualArrival)

    @Test fun firstFreshSnapshotIsSilentEvenWhenAlreadyDelayedCancelledOrOnAnotherPlatform() {
        val monitor = TripChangeMonitor()
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 20, platform = "9", cancelled = true)), 1)).isEmpty())
    }

    @Test fun changedPlatformIsExplainedOnceAndIdenticalPollsAreSilent() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        val changed = monitor.observe(snapshot(listOf(stop(platform = "4")), 2))
        assertEquals(listOf(TripChangeKind.PLATFORM), changed.map { it.kind })
        assertEquals("Station next: Gleis 4 statt Gleis 2.", changed.single().message)
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "4")), 3)).isEmpty())
    }

    @Test fun missingPlatformsDoNotCreateChangesWhenTheyReappear() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = null)), 2)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "4")), 3)).isEmpty())
    }

    @Test fun platformWhitespaceAndCaseDoNotCreateAnAlert() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(platform = "2a")), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = " 2A ")), 2)).isEmpty())
    }

    @Test fun futureIntermediatePlatformChangesAreQuietButDestinationChangesAreExplained() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(), stop("later"), stop("exit", destination = true)), 1))
        val result = monitor.observe(snapshot(listOf(stop(), stop("later", platform = "8"), stop("exit", platform = "9", destination = true)), 2))
        assertEquals(listOf("exit"), result.map { it.stopKey })
    }

    @Test fun cancellingNextStopStillExplainsTheNewNextServedStopsPlatformChange() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(), stop("following"), stop("exit", destination = true)), 1))
        val changed = listOf(stop(cancelled = true), stop("following", platform = "4"), stop("exit", destination = true))
        val result = monitor.observe(snapshot(changed, 2))
        assertEquals(listOf(TripChangeKind.CANCELLED, TripChangeKind.PLATFORM), result.map { it.kind })
        assertEquals(listOf("next", "following"), result.map { it.stopKey })
        assertTrue(monitor.observe(snapshot(changed, 3, nextIndex = 1)).isEmpty())
    }

    @Test fun passedStopsNeverGenerateAnAlert() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop("origin", origin = true), stop(), stop("exit", destination = true)), 1, 1))
        val result = monitor.observe(snapshot(listOf(stop("origin", 20, "9", true, origin = true), stop(), stop("exit", destination = true)), 2, 1))
        assertTrue(result.isEmpty())
    }

    @Test fun cancelledUpcomingAndDestinationStopsAreExplainedWithoutDiscardingTheVisit() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(), stop("exit", destination = true)), 1))
        val result = monitor.observe(snapshot(listOf(stop(cancelled = true), stop("exit", cancelled = true, destination = true)), 2))
        assertEquals(listOf(TripChangeKind.CANCELLED, TripChangeKind.CANCELLED), result.map { it.kind })
        assertTrue(result.first().message.contains("Der nächste Halt"))
        assertTrue(result.last().message.contains("Deine Ausstiegshaltestelle"))
        assertTrue(monitor.observe(snapshot(listOf(stop(cancelled = true), stop("exit", cancelled = true, destination = true)), 3)).isEmpty())
    }

    @Test fun cancellationWithUnknownPreviousStateDoesNotCreateAnAlert() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(cancelled = null)), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(cancelled = true)), 2)).isEmpty())
    }

    @Test fun reinstatementAndAnotherCancellationEachHaveTheirOwnCurrentState() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertEquals(TripChangeKind.CANCELLED, monitor.observe(snapshot(listOf(stop(cancelled = true)), 2)).single().kind)
        assertEquals(TripChangeKind.RESTORED, monitor.observe(snapshot(listOf(stop()), 3)).single().kind)
        assertEquals(TripChangeKind.CANCELLED, monitor.observe(snapshot(listOf(stop(cancelled = true)), 4)).single().kind)
    }

    @Test fun delayChangesAccumulateToFiveMinutesRatherThanNeedingOneBigJump() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(delay = 0)), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 2)), 2)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 4)), 3)).isEmpty())
        val change = monitor.observe(snapshot(listOf(stop(delay = 5)), 4)).single()
        assertEquals(TripChangeKind.DELAY_INCREASE, change.kind)
        assertTrue(change.message.contains("5 Minuten Verspätung"))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 5)), 5)).isEmpty())
    }

    @Test fun regainedTimeIsExplainedIncludingAnEarlyArrival() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(delay = 5)), 1))
        val change = monitor.observe(snapshot(listOf(stop(delay = -1)), 2)).single()
        assertEquals(TripChangeKind.DELAY_DECREASE, change.kind)
        assertTrue(change.message.contains("1 Minute vor dem Fahrplan"))
    }

    @Test fun arrivalAtDestinationIsPreferredOverMultipleDelayAnnouncements() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(), stop("exit", destination = true)), 1))
        val changes = monitor.observe(snapshot(listOf(stop(delay = 10), stop("exit", delay = 10, destination = true)), 2))
        assertEquals(listOf("exit"), changes.map { it.stopKey })
    }

    @Test fun missingLiveTimeBreaksTheDelayComparison() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = null)), 2)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 20)), 3)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE, monitor.observe(snapshot(listOf(stop(delay = 25)), 4)).single().kind)
    }

    @Test fun aChangedPlannedTimeIsNotReportedAsAnIncreaseInLiveDelay() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 15, planned = 2_000_000L)), 2)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE, monitor.observe(snapshot(listOf(stop(delay = 20, planned = 2_000_000L)), 3)).single().kind)
    }

    @Test fun changingAManualArrivalIsQuietButDoesNotDisableLaterProviderChanges() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(destination = true)), 1, manualArrival = 500L))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 10, destination = true)), 2, manualArrival = 700L)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE,
            monitor.observe(snapshot(listOf(stop(delay = 15, destination = true)), 3, manualArrival = 700L)).single().kind)
    }

    @Test fun changingAManualDepartureDoesNotDisableSubsequentOriginDelayChanges() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(origin = true)), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 10, origin = true)), 2, manualDeparture = 100L)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE,
            monitor.observe(snapshot(listOf(stop(delay = 15, origin = true)), 3, manualDeparture = 100L)).single().kind)
    }

    @Test fun staleOrDuplicateSnapshotCannotRollBackTheBaseline() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 10))
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "8", delay = 20, cancelled = true)), 9)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "8")), 10)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM, monitor.observe(snapshot(listOf(stop(platform = "4")), 11)).single().kind)
    }

    @Test fun switchingTripsRequiresANewSilentBaseline() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        monitor.observe(snapshot(listOf(stop(platform = "4")), 2))
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "9", delay = 20)), 3, statusId = 43)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM,
            monitor.observe(snapshot(listOf(stop(platform = "4", delay = 20)), 4, statusId = 43)).single().kind)
    }

    @Test fun restartUsesFreshBaselineAndPreservesLastExplainedValues() {
        val first = TripChangeMonitor()
        first.observe(snapshot(listOf(stop()), 1))
        first.observe(snapshot(listOf(stop(platform = "4")), 2))
        val restarted = TripChangeMonitor()
        restarted.reset(42, first.getState())
        assertTrue(restarted.observe(snapshot(listOf(stop(platform = "4", delay = 30)), 100)).isEmpty())
        assertTrue(restarted.observe(snapshot(listOf(stop(platform = "4", delay = 30)), 101)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM,
            restarted.observe(snapshot(listOf(stop(platform = "8", delay = 30)), 102)).single().kind)
    }

    @Test fun repeatedStationVisitsUseTheirVisitKeysRatherThanTheirNames() {
        val monitor = TripChangeMonitor()
        val first = stop("visit1").copy(name = "Loop Station")
        val second = stop("visit2", destination = true).copy(name = "Loop Station")
        monitor.observe(snapshot(listOf(first, second), 1, 1))
        val changes = monitor.observe(snapshot(listOf(first.copy(arrivalPlatform = "5"), second.copy(arrivalPlatform = "6")), 2, 1))
        assertEquals(listOf("visit2"), changes.map { it.stopKey })
    }

    @Test fun anUnidentifiedNewVisitIsNotComparedToAnUnrelatedPreviousVisit() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop("old")), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop("new", delay = 30, platform = "9", cancelled = true)), 2)).isEmpty())
    }

    @Test fun duplicateVisitIdentitiesInvalidateOnlyThatSnapshot() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = "9"), stop(platform = "8")), 2)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM, monitor.observe(snapshot(listOf(stop(platform = "4")), 3)).single().kind)
    }

    @Test fun equivalentIsoInstantsHaveTheSameFallbackVisitKeyAndDelay() {
        val first = StopStation(station = TrainStation(id = 7, name = "Station"), arrivalPlanned = "2026-10-05T10:00:00Z",
            arrivalReal = "2026-10-05T10:05:00Z")
        val equivalent = first.copy(arrivalPlanned = "2026-10-05T12:00:00+02:00", arrivalReal = "2026-10-05T12:05:00+02:00")
        val checkin = checkin(destination = first)
        val a = TripChangeSnapshot.fromApi(42, 1, listOf(first), checkin, 0)
        val b = TripChangeSnapshot.fromApi(42, 2, listOf(equivalent), checkin, 0)
        assertEquals(a.stops, b.stops)
        val monitor = TripChangeMonitor()
        monitor.observe(a)
        assertTrue(monitor.observe(b).isEmpty())
    }

    @Test fun rawApiTimesIgnoreManualOverrideAndParseErrorsDoNotBecomeZeroDelay() {
        val stop = StopStation(uuid = "uuid", station = TrainStation(id = 7), arrivalPlanned = "2026-10-05T10:00:00Z",
            arrivalReal = "2026-10-05T10:01:00Z")
        val initial = TripChangeSnapshot.fromApi(42, 1, listOf(stop), checkin(destination = stop, manualArrival = "2026-10-05T10:40:00Z"), 0)
        assertEquals(minute, initial.stops.single().arrivalRealMillis!! - initial.stops.single().arrivalPlannedMillis!!)
        val monitor = TripChangeMonitor()
        monitor.observe(initial)
        val invalid = TripChangeSnapshot.fromApi(42, 2, listOf(stop.copy(arrivalReal = "invalid")), checkin(destination = stop), 0)
        assertTrue(monitor.observe(invalid).isEmpty())
        val knownAgain = TripChangeSnapshot.fromApi(42, 3, listOf(stop.copy(arrivalReal = "2026-10-05T10:30:00Z")), checkin(destination = stop), 0)
        assertTrue(monitor.observe(knownAgain).isEmpty())
    }

    @Test fun missingVisitIdentityBeforeDestinationDoesNotShiftTheRemainingIndex() {
        val unknown = StopStation(station = TrainStation(name = "Unknown"), arrivalPlatformPlanned = "1")
        val destination = StopStation(uuid = "exit", station = TrainStation(id = 7), arrivalPlatformPlanned = "2")
        val checkin = checkin(destination)
        val initial = TripChangeSnapshot.fromApi(42, 1, listOf(unknown, destination), checkin, 1)
        val changed = TripChangeSnapshot.fromApi(42, 2, listOf(unknown, destination.copy(arrivalPlatformReal = "4")), checkin, 1)
        assertEquals(2, initial.stops.size)
        assertEquals("unresolved:0", initial.stops.first().key)
        val monitor = TripChangeMonitor()
        monitor.observe(initial)
        assertEquals("exit", monitor.observe(changed).single().stopKey)
    }

    @Test fun losingAndRegainingTheLivePlatformDoesNotExplainAFalseRoundTrip() {
        val initial = stop(platform = "3").copy(arrivalPlatformIsLive = true)
        val gap = stop(platform = "2").copy(arrivalPlatformIsLive = false)
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(initial), 1))
        assertTrue(monitor.observe(snapshot(listOf(gap), 2)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(initial), 3)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM,
            monitor.observe(snapshot(listOf(initial.copy(arrivalPlatform = "4")), 4)).single().kind)
    }

    @Test fun plannedPlatformToNewKnownLivePlatformIsAnActionableChange() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop().copy(arrivalPlatformIsLive = false)), 1))
        val change = stop(platform = "3").copy(arrivalPlatformIsLive = true)
        assertEquals(TripChangeKind.PLATFORM, monitor.observe(snapshot(listOf(change), 2)).single().kind)
        assertTrue(monitor.observe(snapshot(listOf(change), 3)).isEmpty())
    }

    @Test fun anExplainedDelayCanBeExplainedAgainAfterAFreshKnownBaseline() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertEquals(TripChangeKind.DELAY_INCREASE, monitor.observe(snapshot(listOf(stop(delay = 5)), 2)).single().kind)
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = null)), 3)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop()), 4)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE, monitor.observe(snapshot(listOf(stop(delay = 5)), 5)).single().kind)
    }

    @Test fun anExplainedPlatformCanBeExplainedAgainAfterMissingData() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        assertEquals(TripChangeKind.PLATFORM, monitor.observe(snapshot(listOf(stop(platform = "4")), 2)).single().kind)
        assertTrue(monitor.observe(snapshot(listOf(stop(platform = null)), 3)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop()), 4)).isEmpty())
        assertEquals(TripChangeKind.PLATFORM, monitor.observe(snapshot(listOf(stop(platform = "4")), 5)).single().kind)
    }

    @Test fun restartSynchronizesOldDedupMetadataWithTheNewFreshBaseline() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop()), 1))
        monitor.observe(snapshot(listOf(stop(delay = 5, platform = "4")), 2))
        val restarted = TripChangeMonitor()
        restarted.reset(42, monitor.getState())
        assertTrue(restarted.observe(snapshot(listOf(stop()), 10)).isEmpty())
        val changes = restarted.observe(snapshot(listOf(stop(delay = 5, platform = "4")), 11))
        assertEquals(listOf(TripChangeKind.PLATFORM, TripChangeKind.DELAY_INCREASE), changes.map { it.kind })
    }

    @Test fun changingThePreferredDelayStopDoesNotReportAnOldChangeAsNew() {
        val monitor = TripChangeMonitor()
        monitor.observe(snapshot(listOf(stop(), stop("exit", destination = true)), 1))
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 10), stop("exit", destination = true)), 2)).isEmpty())
        assertTrue(monitor.observe(snapshot(listOf(stop(delay = 10), stop("exit", delay = null, destination = true)), 3)).isEmpty())
        assertEquals(TripChangeKind.DELAY_INCREASE,
            monitor.observe(snapshot(listOf(stop(delay = 15), stop("exit", delay = null, destination = true)), 4)).single().kind)
    }

    private fun checkin(destination: StopStation, manualArrival: String? = null): CheckinInfo = CheckinInfo(
        hafasId = null, category = null, mode = null, lineName = "RE 1", distanceMeters = null, points = null,
        duration = null, origin = null, destination = destination, operator = null, trip = 1, tripUuid = null,
        number = null, routeColor = null, routeTextColor = null, journeyNumber = null,
        manualDeparture = null, manualArrival = manualArrival
    )
}
