package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import org.junit.Assert.*
import org.junit.Test

class StatusEditTimeValidationTest {
    private val status = Gson().fromJson("""{"id":42,"checkin":{"manualDeparture":"2026-11-03T09:00:00Z","manualArrival":"2026-11-03T10:00:00Z","origin":{"departureReal":"2026-11-03T08:50:00Z","departurePlanned":"2026-11-03T08:45:00Z"},"destination":{"arrivalReal":"2026-11-03T10:30:00Z","arrivalPlanned":"2026-11-03T10:20:00Z"}}}""", Status::class.java)
    private val state = StatusDetailUiState(status = status)

    @Test fun unchangedTextDoesNotRequireNewTimeOverrides() {
        validateStatusEditTimes(state.copy(status = null), UpdateStatusRequest(body = "Text"))
    }

    @Test fun clearedManualArrivalFallsBackToProviderTime() {
        validateStatusEditTimes(state, UpdateStatusRequest(departure = "2026-11-03T10:25:00Z", arrival = ""))
    }

    @Test fun requestedDepartureIsCheckedAgainstCurrentSavedArrival() {
        assertThrows(IllegalArgumentException::class.java) {
            validateStatusEditTimes(state, UpdateStatusRequest(departure = "2026-11-03T10:01:00Z"))
        }
    }

    @Test fun aNewDestinationDoesNotRetainTheOldManualArrival() {
        val destination = requireNotNull(status.checkin?.destination).copy(arrivalReal = "2026-11-03T12:00:00Z")
        validateStatusEditTimes(state.copy(editDestinationStop = destination),
            UpdateStatusRequest(destination = 12, departure = "2026-11-03T11:00:00Z", arrival = ""))
    }

    @Test fun malformedExplicitTimeIsRejectedBeforeSending() {
        assertThrows(IllegalArgumentException::class.java) {
            validateStatusEditTimes(state, UpdateStatusRequest(arrival = "invalid"))
        }
    }

    @Test fun departureAndArrivalCanBeTheSameMinute() {
        validateStatusEditTimes(state,
            UpdateStatusRequest(departure = "2026-11-03T11:00:00Z", arrival = "2026-11-03T11:00:00Z"))
    }
}
