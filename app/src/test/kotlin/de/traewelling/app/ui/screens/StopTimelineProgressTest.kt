package de.traewelling.app.ui.screens

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.service.TrackingLiveState
import de.traewelling.app.service.TrackingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StopTimelineProgressTest {
    private val now = Instant.parse("2026-10-05T18:13:00Z").toEpochMilli()
    private val stops = listOf(
        stop("neusser", 0),
        stop("maubis", 60_000),
        stop("rathaus", 120_000)
    )

    @Test
    fun consecutiveMinuteStopsHaveOneApproximateSelection() {
        val progress = resolveStopTimelineProgress(stops, now + 30_000, null)

        assertNull(progress.currentIndex)
        assertEquals(1, progress.nextIndex)
        assertEquals(0, progress.passedThroughIndex)
        assertEquals(1, stops.indices.count { progress.badgeFor(it) != null })
        assertEquals("NÄCHSTER HALT · CA.", progress.badgeFor(1))
    }

    @Test
    fun overlappingTimetableDwellWindowsStillSelectOneVisit() {
        val overlapping = stops.toMutableList().apply {
            this[0] = this[0].copy(departurePlanned = time(120_000))
        }
        val progress = resolveStopTimelineProgress(overlapping, now + 60_000, null)

        assertEquals(1, progress.currentIndex)
        assertEquals(1, overlapping.indices.count { progress.badgeFor(it) != null })
        assertEquals("LAUT FAHRPLAN", progress.badgeFor(1))
    }

    @Test
    fun gpsApproachTwoMinutesEarlySelectsRathausInsteadOfTheClockStop() {
        val progress = resolveStopTimelineProgress(stops, now, tracking(stops[2], arrived = false))

        assertNull(progress.currentIndex)
        assertEquals(2, progress.nextIndex)
        assertEquals(1, progress.passedThroughIndex)
        assertNull(progress.badgeFor(0))
        assertEquals("ALS NÄCHSTES", progress.badgeFor(2))
    }

    @Test
    fun gpsArrivalMarksOnlyTheReachedVisitAsCurrent() {
        val progress = resolveStopTimelineProgress(stops, now, tracking(stops[2], arrived = true))

        assertEquals(2, progress.currentIndex)
        assertNull(progress.nextIndex)
        assertEquals(2, progress.passedThroughIndex)
        assertEquals(listOf("AKTUELL"), stops.indices.mapNotNull(progress::badgeFor))
    }

    @Test
    fun trackingSectionIndexDoesNotBecomeAFullTripIndex() {
        val progress = resolveStopTimelineProgress(stops, now, tracking(stops[2], arrived = false).copy(nextIndex = 0))

        assertEquals(2, progress.nextIndex)
    }

    @Test
    fun repeatedStationWithoutUuidMatchesItsPlannedVisit() {
        val visits = stops.map { it.copy(uuid = null, station = TrainStation(id = 9, name = "Rundfahrt")) }
        val progress = resolveStopTimelineProgress(visits, now, tracking(visits[2], arrived = true))

        assertEquals(2, progress.currentIndex)
        assertEquals(1, visits.indices.count { progress.badgeFor(it) != null })
    }

    @Test
    fun temporarilyUnmatchedGpsVisitDoesNotJumpBackToTheTimetable() {
        val progress = resolveStopTimelineProgress(stops, now, tracking(stop("removed", 0), arrived = false))

        assertNull(progress.currentIndex)
        assertNull(progress.nextIndex)
        assertEquals(TimelinePositionSource.WAITING, progress.source)
    }

    @Test
    fun cancelledStopsNeverReceiveTheTimetableCursor() {
        val cancelled = stops.toMutableList().apply { this[1] = this[1].copy(cancelled = true) }
        val progress = resolveStopTimelineProgress(cancelled, now + 60_000, null)

        assertNull(progress.currentIndex)
        assertEquals(2, progress.nextIndex)
        assertNull(progress.badgeFor(1))
    }

    @Test
    fun completedCheckedInSectionDoesNotMarkLaterTripStopsPassed() {
        val fullTrip = stops + stop("later", 180_000)
        val completed = tracking(null, arrived = false).copy(completed = true)
        val progress = resolveStopTimelineProgress(fullTrip, now, completed, destinationIndex = 2)

        assertEquals(2, progress.passedThroughIndex)
        assertEquals(0, fullTrip.indices.count { progress.badgeFor(it) != null })
    }

    private fun stop(key: String, offsetMillis: Long) = StopStation(
        uuid = key,
        station = TrainStation(id = key.hashCode(), name = key),
        arrivalPlanned = time(offsetMillis),
        departurePlanned = time(offsetMillis)
    )

    private fun time(offsetMillis: Long): String = Instant.ofEpochMilli(now + offsetMillis).toString()

    private fun tracking(stop: StopStation?, arrived: Boolean) = TrackingLiveState(
        statusId = 7,
        nextStopKey = stop?.uuid,
        nextIndex = 0,
        stop = stop,
        arrivedAtCurrent = arrived,
        completed = false,
        source = TrackingSource.GPS
    )
}
