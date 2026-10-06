package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.UpdateStatusRequest

/** Provider times shown in the editor stay provider times until explicitly changed. */
internal fun buildStatusEditRequest(
    state: StatusDetailUiState, initialDeparture: String, initialArrival: String
): UpdateStatusRequest {
    val destination = state.editDestinationStop
    val original = state.status?.checkin?.destination
    val changedDestination = destination != original && destination?.matchesStopover(original) != true
    return UpdateStatusRequest(
        body = state.editBody,
        visibility = state.editVisibility,
        destination = destination?.stationId.takeIf { changedDestination },
        destinationArrivalPlanned = destination?.arrivalPlanned.takeIf { changedDestination },
        departure = state.editDeparture.takeIf { it != initialDeparture },
        arrival = when {
            state.editArrival != initialArrival -> state.editArrival
            changedDestination && state.status?.checkin?.manualArrival != null -> ""
            else -> null
        }
    )
}
