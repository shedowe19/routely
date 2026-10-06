package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.CheckInResult
import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInNavigationGuardTest {
    @Test fun notificationRestartCannotCancelADelayedCreateOrItsTimeCorrection() = runTest {
        var state = CheckInUiState(step = CheckInStep.CONFIRM, isLoading = true,
            statusBody = "submitted text", manualArrival = "2026-10-06T12:30:00Z")
        val createResponse = CompletableDeferred<CheckInResult>()
        val correctionResponse = CompletableDeferred<Status>()
        val createdStatus = Gson().fromJson("{\"id\":42}", Status::class.java)
        var posts = 0
        var activeStatusId: Int? = null
        var puts = 0
        val job = launch {
            submitCheckIn(
                CheckInSubmission(CheckInRequest("trip", "RE1", 1, 2,
                    "2026-10-06T12:00:00Z", "2026-10-06T12:30:00Z"),
                    UpdateStatusRequest(arrival = "2026-10-06T12:31:00Z")),
                create = { posts++; Result.success(createResponse.await()) },
                onCreated = { created ->
                    activeStatusId = created?.status?.id
                    state = state.copy(step = CheckInStep.SUCCESS, checkInResult = created)
                },
                correctTimes = { id, _ ->
                    assertEquals(42, id)
                    puts++
                    Result.success(correctionResponse.await())
                }
            ).onSuccess { completion ->
                state = state.copy(step = CheckInStep.SUCCESS, isLoading = false,
                    checkInResult = completion.result, completionWarning = completion.warning)
            }.getOrThrow()
        }
        runCurrent()
        assertEquals(1, posts)
        assertNull(resetCheckInState(state))
        assertTrue(state.hasPendingSubmission)
        assertTrue(job.isActive)
        createResponse.complete(CheckInResult(createdStatus, null))
        runCurrent()
        assertEquals(42, activeStatusId)
        assertEquals(1, puts)
        assertEquals(CheckInStep.SUCCESS, state.step)
        assertNull(resetCheckInState(state))
        correctionResponse.complete(createdStatus)
        job.join()
        assertFalse(state.hasPendingSubmission)
        assertEquals(42, state.checkInResult?.status?.id)
        assertEquals(CheckInStep.STATION, resetCheckInState(state)?.step)
        assertEquals(1, posts)
    }

    @Test fun ordinarySearchLoadingAndFailedConfirmationRemainResettable() {
        assertNotNull(resetCheckInState(CheckInUiState(step = CheckInStep.STATION, isLoading = true)))
        assertNotNull(resetCheckInState(CheckInUiState(step = CheckInStep.CONFIRM, isLoading = false,
            error = "failed before creation")))
        assertFalse(CheckInUiState(step = CheckInStep.DESTINATION, isLoading = true).hasPendingSubmission)
    }

    @Test fun resetPreservesOnlyTheCurrentRecognitionAndActiveRideContext() {
        val source = CheckInUiState(step = CheckInStep.SUCCESS, statusBody = "old",
            rideRecognitionEnabled = true, activeRidePresent = true)
        val reset = requireNotNull(resetCheckInState(source))
        assertTrue(reset.rideRecognitionEnabled)
        assertTrue(reset.activeRidePresent)
        assertSame(source.rideRecognition, reset.rideRecognition)
        assertEquals("", reset.statusBody)
    }
}
