package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInSelectionTest {
    private val station = TrainStation(id = 7, name = "Rundfahrt")
    private fun stop(id: Int?, time: String, cancelled: Boolean = false) = StopStation(
        station = id?.let { TrainStation(id = it) }, departurePlanned = time,
        arrivalPlanned = time, cancelled = cancelled
    )
    private fun departure(time: String?, real: String? = null) = DepartureTrip(
        "trip", null, null, time, real, null, null, false, station
    )
    private val first = "2026-10-06T10:00:00Z"
    private val second = "2026-10-06T11:00:00Z"

    @Test fun uniqueOriginCanBeMatchedWhenProviderOmitsTheDepartureTime() {
        assertEquals(1, resolveCheckInOriginIndex(listOf(stop(1, first), stop(7, first)), station, departure(null)))
    }

    @Test fun repeatedOriginMatchesTheSelectedVisitIncludingEquivalentOffset() {
        val visits = listOf(stop(7, first), stop(8, first), stop(7, second), stop(9, second))
        assertEquals(2, resolveCheckInOriginIndex(visits, station, departure("2026-10-06T13:00:00+02:00")))
    }

    @Test fun repeatedOriginWithoutMatchingTimeIsRejectedInsteadOfChoosingFirst() {
        assertEquals(-1, resolveCheckInOriginIndex(listOf(stop(7, first), stop(7, second)), station, departure(null)))
    }

    @Test fun unknownOriginDoesNotOfferTheWholeTripAsDestinations() {
        val visits = listOf(stop(8, first), stop(9, second))
        val index = resolveCheckInOriginIndex(visits, station, departure(first))
        assertEquals(-1, index)
        assertTrue(validCheckInDestinations(visits, index).isEmpty())
    }

    @Test fun realDepartureCanDistinguishARepeatedVisit() {
        val visits = listOf(stop(7, first), stop(7, second).copy(departureReal = "2026-10-06T11:05:00Z"))
        assertEquals(1, resolveCheckInOriginIndex(visits, station, departure(null, "2026-10-06T13:05:00+02:00")))
    }

    @Test fun cancelledOriginOffersNoDestinations() {
        assertTrue(validCheckInDestinations(listOf(stop(7, first, true), stop(8, second)), 0).isEmpty())
    }

    @Test fun onlyUsableVisitsAfterTheBoardingVisitAreSelectable() {
        val visits = listOf(stop(1, first), stop(7, first), stop(8, second, true),
            stop(null, second), stop(9, second).copy(arrivalPlanned = null), stop(10, second))
        assertEquals(listOf(10), validCheckInDestinations(visits, 1).map { it.stationId })
    }

    @Test fun selectingTheLaterLoopVisitNeverIncludesEarlierStops() {
        val visits = listOf(stop(7, first), stop(8, first), stop(7, second), stop(9, second))
        val origin = resolveCheckInOriginIndex(visits, station, departure(second))
        assertEquals(listOf(9), validCheckInDestinations(visits, origin).map { it.stationId })
    }
}
