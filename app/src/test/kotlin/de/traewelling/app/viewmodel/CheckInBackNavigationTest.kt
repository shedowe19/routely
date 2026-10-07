package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.*
import org.junit.Test

class CheckInBackNavigationTest {
    private val station = TrainStation(id = 1, name = "Einstieg")
    private val departure = DepartureTrip("trip", null, null,
        "2026-10-07T12:00:00Z", null, null, null, false, station)
    private val destination = StopStation(station = TrainStation(id = 2, name = "Ziel"),
        arrivalPlanned = "2026-10-07T12:30:00Z")
    private val recognitionDestination = CheckInUiState(
        step = CheckInStep.DESTINATION, destinationSource = CheckInDestinationSource.RIDE_RECOGNITION,
        stationQuery = station.name.orEmpty(), selectedStation = station,
        selectedDeparture = departure, filteredDestinations = listOf(destination),
        rideRecognitionEnabled = true, statusBody = "Mein Entwurf"
    )

    @Test fun backFromAFreshRecognizedTripReturnsToStationSearchWithoutAnUnqueriedDepartureList() {
        assertTrue(recognitionDestination.departures.isEmpty())
        val back = requireNotNull(previousCheckInState(recognitionDestination))
        assertEquals(CheckInStep.STATION, back.step)
        assertEquals("", back.stationQuery)
        assertNull(back.selectedStation)
        assertNull(back.selectedDeparture)
        assertNull(back.selectedTripDetails)
        assertNull(back.resolvedOriginStop)
        assertTrue(back.filteredDestinations.isEmpty())
        assertTrue(back.rideRecognitionEnabled)
        assertSame(recognitionDestination.rideRecognition, back.rideRecognition)
        assertEquals("Mein Entwurf", back.statusBody)
    }

    @Test fun recognitionBackNeverReusesDeparturesFromAnEarlierManualSelection() {
        val state = recognitionDestination.copy(departures = listOf(departure),
            searchResults = listOf(station))
        val back = requireNotNull(previousCheckInState(state))
        assertEquals(CheckInStep.STATION, back.step)
        assertTrue(back.departures.isEmpty())
        assertTrue(back.searchResults.isEmpty())
    }

    @Test fun normalDepartureSelectionBackKeepsTheActuallyLoadedDepartures() {
        val state = recognitionDestination.copy(destinationSource = CheckInDestinationSource.DEPARTURE_SELECTION,
            departures = listOf(departure))
        val back = requireNotNull(previousCheckInState(state))
        assertEquals(CheckInStep.DEPARTURES, back.step)
        assertEquals(listOf(departure), back.departures)
        assertEquals(station, back.selectedStation)
        assertNull(back.selectedDeparture)
        assertTrue(back.filteredDestinations.isEmpty())
    }

    @Test fun backingOutOfRecognizedConfirmationRetainsTheRecognitionReturnPath() {
        val confirmation = recognitionDestination.copy(step = CheckInStep.CONFIRM,
            selectedDestination = destination)
        val destinationStep = requireNotNull(previousCheckInState(confirmation))
        assertEquals(CheckInStep.DESTINATION, destinationStep.step)
        assertEquals(CheckInDestinationSource.RIDE_RECOGNITION, destinationStep.destinationSource)
        assertNull(destinationStep.selectedDestination)
        assertEquals(CheckInStep.STATION, previousCheckInState(destinationStep)?.step)
    }

    @Test fun ordinaryDepartureLoadingCanBeCanceledAndReturnsToStation() {
        val state = CheckInUiState(step = CheckInStep.DEPARTURES, isLoading = true,
            selectedStation = station, departures = listOf(departure), error = "old")
        val back = requireNotNull(previousCheckInState(state))
        assertEquals(CheckInStep.STATION, back.step)
        assertFalse(back.isLoading)
        assertNull(back.error)
        assertNull(back.selectedStation)
        assertTrue(back.departures.isEmpty())
        assertEquals(station.name, back.stationQuery)
        assertEquals(listOf(station), back.searchResults)
    }

    @Test fun backFromQueriedDeparturesShowsOnlyTheProvenStationWithAMatchingQuery() {
        val unrelated = TrainStation(id = 99, name = "Altes Suchergebnis")
        val state = CheckInUiState(step = CheckInStep.DEPARTURES,
            selectedStation = station, stationQuery = "Eins", searchResults = listOf(unrelated),
            departures = listOf(departure))
        val back = requireNotNull(previousCheckInState(state))
        assertEquals(CheckInStep.STATION, back.step)
        assertEquals(station.name, back.stationQuery)
        assertEquals(listOf(station), back.searchResults)
        assertFalse(back.searchResults.contains(unrelated))
    }

    @Test fun anUnprovenStationDoesNotProduceAFalseSearchResponseOrNoResultsClaim() {
        for (invalid in listOf(null, station.copy(id = null), station.copy(id = 0), station.copy(name = " "))) {
            val back = requireNotNull(previousCheckInState(CheckInUiState(
                step = CheckInStep.DEPARTURES, selectedStation = invalid, stationQuery = "old query"
            )))
            assertEquals(CheckInStep.STATION, back.step)
            assertEquals("", back.stationQuery)
            assertTrue(back.searchResults.isEmpty())
        }
    }
}
