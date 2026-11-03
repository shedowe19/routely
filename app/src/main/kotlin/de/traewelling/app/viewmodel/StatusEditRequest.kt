package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.data.model.StatusVisibility
import java.time.Instant

internal val StatusDetailUiState.isEditVisibilityLocked: Boolean
    get() = status?.lockVisibility == true || editInitialStatus?.lockVisibility == true

/** Provider times shown in the editor stay provider times until explicitly changed. */
internal fun buildStatusEditRequest(
    state: StatusDetailUiState, initialDeparture: String, initialArrival: String
): UpdateStatusRequest {
    val destination = state.editDestinationStop
    // A background refresh may change the status while the editor remains open.
    // Only the user's change from the opening snapshot is an edit of the destination.
    val originalCheckin = (state.editInitialStatus ?: state.status)?.checkin
    val original = originalCheckin?.destination
    val changedDestination = destination != original && destination?.matchesStopover(original) != true
    return UpdateStatusRequest(
        body = state.editBody.takeIf { it != (state.editInitialStatus ?: state.status)?.body.orEmpty() },
        // An unchanged opening value is not permission to restore visibility after a refresh.
        visibility = state.editVisibility.takeIf {
            !state.isEditVisibilityLocked && state.editVisibilityManuallyChanged &&
                StatusVisibility.fromApiValue(it) != null &&
                it != (state.editInitialStatus ?: state.status)?.visibility
        },
        destination = destination?.stationId.takeIf { changedDestination },
        destinationArrivalPlanned = destination?.arrivalPlanned.takeIf { changedDestination },
        departure = state.editDeparture.takeIf { it != initialDeparture },
        arrival = when {
            (state.editArrivalManuallyChanged ?: (state.editArrival != initialArrival)) -> state.editArrival
            changedDestination && (originalCheckin?.manualArrival != null ||
                state.status?.checkin?.manualArrival != null) -> ""
            else -> null
        }
    )
}

/** Validate the values the request will actually leave on the server, including cleared overrides. */
internal fun validateStatusEditTimes(state: StatusDetailUiState, request: UpdateStatusRequest) {
    if (request.departure == null && request.arrival == null && request.destination == null) return
    val checkin = requireNotNull(state.status?.checkin) { "Die Fahrt enthält keine gültigen Zeiten." }
    val destination = if (request.destination != null) state.editDestinationStop else checkin.destination
    fun parsed(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun resolved(requested: String?, stored: String?, real: String?, planned: String?, label: String): Instant {
        if (!requested.isNullOrBlank()) return requireNotNull(parsed(requested)) { "$label: Bitte Datum und Uhrzeit wählen." }
        return requireNotNull((if (requested == null) parsed(stored) else null) ?: parsed(real) ?: parsed(planned)) {
            "$label fehlt. Bitte Datum und Uhrzeit wählen."
        }
    }
    val departure = resolved(request.departure, checkin.manualDeparture,
        checkin.origin?.departureReal, checkin.origin?.departurePlanned, "Abfahrt")
    val arrival = resolved(request.arrival, checkin.manualArrival.takeIf { request.destination == null },
        destination?.arrivalReal, destination?.arrivalPlanned, "Ankunft")
    require(departure <= arrival) { "Die Ankunft darf nicht vor der Abfahrt liegen. Bitte prüfe beide Zeiten." }
}
