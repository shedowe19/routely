package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.TrainStation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CheckInDepartureSearchTest {
    private val station = TrainStation(id = 7, name = "Teststation")
    private val yesterday = Instant.parse("2026-11-02T22:05:00Z").toEpochMilli()

    @Test fun currentBoardOmitsWhenAndExplicitTimeIsAnImmutableUtcQuery() {
        assertNull(departureSearchRequest(CheckInUiState(), station).whenTime)
        val request = departureSearchRequest(CheckInUiState(departureSearchTimeMillis = yesterday), station)
        assertEquals(7, request.stationId)
        assertEquals("2026-11-02T22:05:00Z", request.whenTime)
        assertThrows(IllegalArgumentException::class.java) {
            departureSearchRequest(CheckInUiState(), station.copy(id = 0))
        }
    }

    @Test fun changingTheTimeDropsDependentTripChoicesButKeepsTheStatusDraft() {
        val old = CheckInUiState(step = CheckInStep.DEPARTURES, selectedStation = station,
            departures = listOf(departure()), selectedDeparture = departure(),
            isLoading = true, error = "old", statusBody = "Mein Text", visibility = 3)
        val changed = requireNotNull(withDepartureSearchTime(old, yesterday))
        assertEquals(yesterday, changed.departureSearchTimeMillis)
        assertEquals(station, changed.selectedStation)
        assertEquals("Mein Text", changed.statusBody)
        assertEquals(3, changed.visibility)
        assertFalse(changed.isLoading)
        assertNull(changed.error)
        assertNull(changed.selectedDeparture)
        assertTrue(changed.departures.isEmpty())
        assertTrue(changed.filteredDestinations.isEmpty())
    }

    @Test fun backKeepsHistoricalTimeAndFullResetReturnsToTheCurrentBoard() {
        val state = CheckInUiState(step = CheckInStep.DEPARTURES, selectedStation = station,
            departureSearchTimeMillis = yesterday)
        val back = requireNotNull(previousCheckInState(state))
        assertEquals(yesterday, back.departureSearchTimeMillis)
        assertNull(requireNotNull(resetCheckInState(back)).departureSearchTimeMillis)
        assertNull(withDepartureSearchTime(state.copy(step = CheckInStep.CONFIRM), null))
    }

    @Test fun cancellingAPendingStationLookupDoesNotClaimThatItsQueryFoundNothing() {
        val state = CheckInUiState(stationQuery = "Hamburg", isLoading = true)
        val changed = requireNotNull(withDepartureSearchTime(state, yesterday))
        assertEquals("", changed.stationQuery)
        assertFalse(changed.isLoading)
        val known = state.copy(isLoading = false, searchResults = listOf(station))
        assertEquals("Hamburg", withDepartureSearchTime(known, yesterday)?.stationQuery)
    }

    @Test fun aLateOldDepartureReplyCannotReplaceTheNewTimeSelection() = runBlocking {
        var generation = 1
        var published = 0
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<List<DepartureTrip>>>()
        val query = departureSearchRequest(CheckInUiState(departureSearchTimeMillis = yesterday), station)
        val pending = async {
            queryCheckInDepartures(query, { id, whenTime ->
                assertEquals(7, id)
                assertEquals("2026-11-02T22:05:00Z", whenTime)
                entered.complete(Unit)
                reply.await()
            }, { generation == 1 }, { published++ })
        }
        entered.await()
        generation = 2
        reply.complete(Result.success(listOf(departure())))
        pending.await()
        assertEquals(0, published)
    }

    @Test fun currentEmptyRepliesRemainValidAndCancellationNeverPublishes() = runBlocking {
        val query = departureSearchRequest(CheckInUiState(), station)
        var published = 0
        queryCheckInDepartures(query, { _, _ -> Result.success(emptyList()) }, { true }) {
            assertTrue(it.getOrThrow().isEmpty())
            published++
        }
        val cancelled = async {
            queryCheckInDepartures(query, { _, _ -> Result.failure(CancellationException("cancelled")) },
                { true }, { fail("No cancelled reply") })
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(1, published)
    }

    @Test fun datePickerUsesCalendarDayInTheDeviceZoneRatherThanUtcDay() {
        val instant = Instant.parse("2026-07-03T15:05:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Tokyo")
        val date = DepartureSearchTime.datePickerMillis(instant, zone)
        assertEquals("2026-07-04T00:00:00Z", Instant.ofEpochMilli(date).toString())
        assertEquals(instant, DepartureSearchTime.fromLocalSelection(date, 0, 5, zone))
        assertTrue(DepartureSearchTime.label(instant, zone).contains("04.07.2026"))
        assertTrue(DepartureSearchTime.label(instant, zone).contains("+09:00"))
    }

    @Test fun missingDstClockTimeIsRejectedAndRepeatedTimeHasAnExplicitOffsetPolicy() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertThrows(IllegalArgumentException::class.java) {
            DepartureSearchTime.fromLocalSelection(Instant.parse("2026-03-29T00:00:00Z").toEpochMilli(), 2, 30, berlin)
        }
        val fallDate = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
        assertEquals("2026-10-25T00:30:00Z", Instant.ofEpochMilli(
            DepartureSearchTime.fromLocalSelection(fallDate, 2, 30, berlin)).toString())
        assertEquals("2026-10-25T01:30:00Z", Instant.ofEpochMilli(
            DepartureSearchTime.fromLocalSelection(fallDate, 2, 30, berlin, ZoneOffset.ofHours(1))).toString())
    }

    private fun departure() = DepartureTrip("trip", null, null, "2026-11-02T22:05:00Z",
        null, null, null, false, station)
}
