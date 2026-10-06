package de.traewelling.app.viewmodel

import com.google.gson.Gson
import com.google.gson.JsonParser
import de.traewelling.app.data.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StatusEditRequestTest {
    private val gson = Gson()
    private val status = gson.fromJson(
        JsonParser.parseString(requireNotNull(javaClass.getResource("/traewelling/status-current.json")).readText())
            .asJsonObject.getAsJsonObject("data"), Status::class.java
    )
    private val departure = "2026-10-05T08:57:00Z"
    private val arrival = "2026-10-05T10:26:00Z"
    private val state = StatusDetailUiState(status = status, isEditing = true, editBody = "Text",
        editDeparture = departure, editArrival = arrival,
        editDestinationStop = status.checkin?.destination, editDestinationId = status.checkin?.destination?.stationId)

    @Test fun textOnlyEditDoesNotCreateManualProviderTimeOverrides() {
        val request = buildStatusEditRequest(state, departure, arrival)
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("Text", request.body)
        assertFalse(json.has("manualDeparture"))
        assertFalse(json.has("manualArrival"))
        assertFalse(json.has("destinationId"))
    }

    @Test fun departureEditDoesNotFreezeUnchangedArrival() {
        val request = buildStatusEditRequest(state.copy(editDeparture = "2026-10-05T09:00:00Z"), departure, arrival)
        assertEquals("2026-10-05T09:00:00Z", request.departure)
        assertNull(request.arrival)
    }

    @Test fun arrivalEditDoesNotFreezeUnchangedDeparture() {
        val request = buildStatusEditRequest(state.copy(editArrival = "2026-10-05T10:30:00Z"), departure, arrival)
        assertEquals("2026-10-05T10:30:00Z", request.arrival)
        assertNull(request.departure)
    }

    @Test fun aNewVisitAtTheSameStationSendsTheRequiredDestinationTimePair() {
        val destination = requireNotNull(state.editDestinationStop).copy(uuid = "later-visit", arrivalPlanned = "2026-10-05T11:00:00Z")
        val request = buildStatusEditRequest(state.copy(editDestinationStop = destination), departure, arrival)
        assertEquals(destination.stationId, request.destination)
        assertEquals(destination.arrivalPlanned, request.destinationArrivalPlanned)
        assertNull(request.arrival)
    }

    @Test fun refreshedRealtimeOnTheSameVisitDoesNotBecomeADestinationChange() {
        val destination = requireNotNull(state.editDestinationStop).copy(arrivalReal = "2026-10-05T10:40:00Z")
        val request = buildStatusEditRequest(state.copy(editDestinationStop = destination), departure, arrival)
        assertNull(request.destination)
        assertNull(request.destinationArrivalPlanned)
    }

    @Test fun explicitlyClearedManualTimeIsSentRatherThanOmitted() {
        val request = buildStatusEditRequest(state.copy(editArrival = ""), departure, arrival)
        assertEquals("", request.arrival)
        assertFalse(JsonParser.parseString(gson.toJson(request)).asJsonObject.get("manualArrival").isJsonNull)
    }

    @Test fun destinationChangeClearsThePreviousDestinationsUneditedManualArrival() {
        val previousManual = status.copy(checkin = requireNotNull(status.checkin).copy(manualArrival = "2026-10-05T10:40:00Z"))
        val newArrival = "2026-10-05T11:00:00Z"
        val destination = requireNotNull(state.editDestinationStop).copy(uuid = "later-visit", arrivalPlanned = newArrival)
        val changed = state.copy(status = previousManual, editDestinationStop = destination, editArrival = newArrival)
        assertEquals("", buildStatusEditRequest(changed, departure, newArrival).arrival)
    }

    @Test fun explicitArrivalEditIsPreservedForTheNewDestination() {
        val previousManual = status.copy(checkin = requireNotNull(status.checkin).copy(manualArrival = "2026-10-05T10:40:00Z"))
        val destination = requireNotNull(state.editDestinationStop).copy(uuid = "later-visit", arrivalPlanned = "2026-10-05T11:00:00Z")
        val changed = state.copy(status = previousManual, editDestinationStop = destination, editArrival = "2026-10-05T11:05:00Z")
        assertEquals(changed.editArrival, buildStatusEditRequest(changed, departure, destination.arrivalPlanned.orEmpty()).arrival)
    }
}
