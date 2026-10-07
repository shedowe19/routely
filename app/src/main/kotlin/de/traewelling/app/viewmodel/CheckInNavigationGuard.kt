package de.traewelling.app.viewmodel

/** A submitted create can already be committed while its response is still travelling. */
internal val CheckInUiState.hasPendingSubmission: Boolean
    get() = isLoading && (step == CheckInStep.CONFIRM || step == CheckInStep.SUCCESS)

internal enum class CheckInSystemBackAction { DEFER_TO_ACTIVITY, STEP_BACK, BLOCK_SUBMISSION }

/** Pager neighbours can remain composed, but only the visible page owns ordinary step navigation. */
internal fun checkInSystemBackAction(state: CheckInUiState, isCurrentPage: Boolean): CheckInSystemBackAction = when {
    !isCurrentPage -> CheckInSystemBackAction.DEFER_TO_ACTIVITY
    state.hasPendingSubmission -> CheckInSystemBackAction.BLOCK_SUBMISSION
    state.step in listOf(CheckInStep.DEPARTURES, CheckInStep.DESTINATION, CheckInStep.CONFIRM) ->
        CheckInSystemBackAction.STEP_BACK
    else -> CheckInSystemBackAction.DEFER_TO_ACTIVITY
}

/** A visible Main destination also protects a submission when the user has switched pager tabs. */
internal fun guardPendingCheckInSystemBack(state: CheckInUiState, isMainDestination: Boolean): Boolean =
    isMainDestination && state.hasPendingSubmission

/** Null protects the existing write. Recognition never pretends that a departures query ran. */
internal fun previousCheckInState(state: CheckInUiState): CheckInUiState? {
    if (state.hasPendingSubmission) return null
    val idle = state.copy(isLoading = false, error = null)
    return when (state.step) {
        CheckInStep.DEPARTURES -> {
            // This station is the proven selection, not a fabricated search response.
            // Keep it available instead of claiming that its retained query found nothing.
            val knownStation = state.selectedStation?.takeIf {
                (it.id ?: 0) > 0 && !it.name.isNullOrBlank()
            }
            idle.copy(
                step = CheckInStep.STATION, selectedStation = null, departures = emptyList(),
                stationQuery = knownStation?.name.orEmpty(), searchResults = listOfNotNull(knownStation)
            )
        }
        CheckInStep.DESTINATION -> if (state.destinationSource == CheckInDestinationSource.RIDE_RECOGNITION) {
            idle.copy(
                step = CheckInStep.STATION, selectedStation = null, stationQuery = "",
                searchResults = emptyList(), departures = emptyList(), selectedDeparture = null,
                selectedTripDetails = null, resolvedOriginStop = null, filteredDestinations = emptyList(),
                selectedDestination = null, destinationSource = CheckInDestinationSource.DEPARTURE_SELECTION
            )
        } else idle.copy(
            step = CheckInStep.DEPARTURES, selectedDeparture = null, selectedTripDetails = null,
            resolvedOriginStop = null, filteredDestinations = emptyList(), selectedDestination = null
        )
        CheckInStep.CONFIRM -> idle.copy(step = CheckInStep.DESTINATION, selectedDestination = null)
        else -> idle
    }
}

/** Null means navigation may show the existing flow, but must not restart/cancel its work. */
internal fun resetCheckInState(state: CheckInUiState): CheckInUiState? =
    if (state.hasPendingSubmission) null else CheckInUiState(
        rideRecognitionEnabled = state.rideRecognitionEnabled,
        rideRecognition = state.rideRecognition,
        activeRidePresent = state.activeRidePresent
    )
