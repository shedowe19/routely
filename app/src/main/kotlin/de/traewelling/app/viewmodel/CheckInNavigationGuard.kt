package de.traewelling.app.viewmodel

/** A submitted create can already be committed while its response is still travelling. */
internal val CheckInUiState.hasPendingSubmission: Boolean
    get() = isLoading && (step == CheckInStep.CONFIRM || step == CheckInStep.SUCCESS)

/** Null means navigation may show the existing flow, but must not restart/cancel its work. */
internal fun resetCheckInState(state: CheckInUiState): CheckInUiState? =
    if (state.hasPendingSubmission) null else CheckInUiState(
        rideRecognitionEnabled = state.rideRecognitionEnabled,
        rideRecognition = state.rideRecognition,
        activeRidePresent = state.activeRidePresent
    )
