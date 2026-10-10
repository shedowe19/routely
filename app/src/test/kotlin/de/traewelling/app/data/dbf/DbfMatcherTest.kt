package de.traewelling.app.data.dbf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class DbfMatcherTest {
    private val fetched = Instant.parse("2026-10-10T17:26:55Z")
    private val arrival = Instant.parse("2026-10-10T17:24:00Z")
    private val departure = Instant.parse("2026-10-10T17:25:00Z")

    @Test fun usesExistingPlannedDateAndIndependentArrivalDepartureDelays() {
        val delta = match(board(row()))!!
        assertEquals(DbfField.Present(arrival.plusSeconds(180)), delta.arrival!!.realTime)
        assertEquals(DbfField.Present(departure.plusSeconds(300)), delta.departure!!.realTime)
        assertEquals(DbfField.Absent, delta.arrival.platform)
        assertEquals(DbfField.Present("4"), delta.departure.platform)
        assertEquals(DbfField.Absent, delta.arrival.scheduledPlatform)
        assertEquals(DbfField.Present("3"), delta.departure.scheduledPlatform)
        assertNull(delta.providerUpdatedAt)
    }

    @Test fun capturedArrivalOnlyRowDoesNotInventDeparture() {
        val board = DbfJsonParser.parse(
            requireNotNull(javaClass.getResource("/dbf/public-hannover-v3.json")).readText(), "8000152", fetched
        )
        val delta = DbfMatcher.match(board, "8000152", "34328", arrival, null)!!
        assertEquals(DbfField.Present(arrival.plusSeconds(180)), delta.arrival!!.realTime)
        assertNull(delta.departure)
        assertEquals(DbfField.Absent, delta.arrival.platform)
    }

    @Test fun requiresPositiveOperationalNumberAndExactStationNotPublicLine() {
        listOf(null, "0", "-34328", "RE 1", "34329", "34328a").forEach { number ->
            assertNull(DbfMatcher.match(board(row()), "8000152", number, arrival, departure))
        }
        assertNull(DbfMatcher.match(board(row()), "8000191", "34328", arrival, departure))
    }

    @Test fun repeatedTrainTimeAndEqualDuplicateRowsAreAmbiguous() {
        assertNull(match(board(row(), row())))
        assertNull(match(board(row(), row().copy(delayArrival = 10))))
    }

    @Test fun zeroNullAndMissingRealtimeCannotEraseKnownDelayOrInventLivePlatform() {
        for (row in listOf(
            row().copy(delayArrival = 0, delayDeparture = 0, platform = "3"),
            row().copy(delayArrival = null, delayDeparture = null, platform = "3"),
            row().copy(missingRealtime = true), row().copy(missingRealtime = null)
        )) {
            val delta = match(board(row))!!
            assertEquals(DbfField.Absent, delta.arrival!!.realTime)
            assertEquals(DbfField.Absent, delta.departure!!.realTime)
            assertEquals(DbfField.Absent, delta.departure.platform)
            assertEquals(DbfField.Absent, delta.departure.scheduledPlatform)
        }
    }

    @Test fun falseAggregateCancellationNeverRestoresPotentiallyPartlyCancelledStop() {
        val delta = match(board(row().copy(isCancelled = false)))!!
        assertEquals(DbfField.Absent, delta.cancelled)
    }

    @Test fun confirmedWholeStopCancellationDoesNotManufactureRealtimeTimes() {
        val delta = match(board(row().copy(isCancelled = true)))!!
        assertEquals(DbfField.Present(true), delta.cancelled)
        assertEquals(DbfField.Absent, delta.arrival!!.realTime)
        assertEquals(DbfField.Absent, delta.departure!!.platform)
    }

    @Test fun cancellationRequiresAllRequestedEventSidesToMatch() {
        val delta = DbfMatcher.match(board(row().copy(isCancelled = true)), "8000152", "34328", arrival,
            departure.plusSeconds(120))!!
        assertNull(delta.departure)
        assertEquals(DbfField.Absent, delta.cancelled)
    }

    @Test fun arrivalOnlyCancellationAndChangedPlatformAreSupported() {
        val terminal = row().copy(scheduledDeparture = null, delayDeparture = null)
        val delta = DbfMatcher.match(board(terminal), "8000152", "34328", arrival, null)!!
        assertEquals(DbfField.Present("4"), delta.arrival!!.platform)
        assertEquals(DbfField.Present("3"), delta.arrival.scheduledPlatform)
        val cancelled = DbfMatcher.match(board(terminal.copy(isCancelled = true)), "8000152", "34328", arrival, null)!!
        assertEquals(DbfField.Present(true), cancelled.cancelled)
    }

    @Test fun dateLessBoardNeverMatchesSameClockTimeOnAnotherDayOrOutsideWindow() {
        assertNull(DbfMatcher.match(board(row()), "8000152", "34328", arrival.minusSeconds(86_400), null))
        assertNull(DbfMatcher.match(board(row()), "8000152", "34328", arrival.plusSeconds(86_400), null))
        assertNull(DbfMatcher.match(board(row(), fetchedAt = fetched.plusSeconds(7200)), "8000152", "34328", arrival, null))
    }

    @Test fun refusesRepeatedBerlinDstHourDespiteKnownAppOffset() {
        val scheduled = Instant.parse("2026-10-25T00:30:00Z")
        val row = row().copy(scheduledArrival = LocalTime.of(2, 30), scheduledDeparture = null)
        val board = board(row, fetchedAt = scheduled.minusSeconds(60))
        assertNull(DbfMatcher.match(board, "8000152", "34328", scheduled, null))
        assertNull(DbfMatcher.match(board.copy(fetchedAt = scheduled.plusSeconds(3540)), "8000152", "34328",
            scheduled.plusSeconds(3600), null))
    }

    @Test fun handlesMidnightOnlyUsingExistingPlannedInstantWithinCurrentWindow() {
        val midnight = Instant.parse("2026-10-10T22:05:00Z")
        val row = row().copy(scheduledArrival = LocalTime.of(0, 5), scheduledDeparture = null)
        val delta = DbfMatcher.match(board(row, fetchedAt = midnight.minusSeconds(600)), "8000152", "34328", midnight, null)!!
        assertEquals(DbfField.Present(midnight.plusSeconds(180)), delta.arrival!!.realTime)
    }

    @Test fun negativeDelayWorksButExtremeOrInvalidDelayDoesNot() {
        val delta = match(board(row().copy(delayArrival = -2, delayDeparture = 2_000)))!!
        assertEquals(DbfField.Present(arrival.minusSeconds(120)), delta.arrival!!.realTime)
        assertEquals(DbfField.Absent, delta.departure!!.realTime)
    }

    @Test fun largeDelayMatchesTheCurrentActualEventInsteadOfOldPlannedWindow() {
        val scheduled = Instant.parse("2026-10-10T16:40:00Z")
        val fetched = Instant.parse("2026-10-10T17:23:00Z")
        val row = row().copy(scheduledArrival = LocalTime.of(18, 40), scheduledDeparture = null,
            delayArrival = 43)
        val delta = DbfMatcher.match(board(row, fetchedAt = fetched), "8000152", "34328", scheduled, null)!!
        assertEquals(DbfField.Present(fetched), delta.arrival!!.realTime)
    }

    @Test fun delayedPreviousDayArrivalMatchesAfterMidnightWithoutInventingDate() {
        val scheduled = Instant.parse("2026-10-10T21:40:00Z")
        val actual = Instant.parse("2026-10-10T22:20:00Z")
        val row = row().copy(scheduledArrival = LocalTime.of(23, 40), scheduledDeparture = null,
            delayArrival = 40)
        val delta = DbfMatcher.match(board(row, fetchedAt = actual), "8000152", "34328", scheduled, null)!!
        assertEquals(DbfField.Present(actual), delta.arrival!!.realTime)
        assertNull(DbfMatcher.match(board(row.copy(delayArrival = 1_440), fetchedAt = actual),
            "8000152", "34328", scheduled.minusSeconds(86_400), null))
    }

    private fun row() = DbfDeparture("34328", LocalTime.of(19, 24), LocalTime.of(19, 25),
        3, 5, "4", "3", false, false)
    private fun board(vararg rows: DbfDeparture, fetchedAt: Instant = fetched) =
        DbfBoard("8000152", rows.toList(), fetchedAt)
    private fun match(board: DbfBoard) = DbfMatcher.match(board, "8000152", "34328", arrival, departure)
}
