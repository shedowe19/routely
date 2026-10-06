package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.UpdateStatusRequest

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
        body = state.editBody,
        visibility = state.editVisibility,
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
