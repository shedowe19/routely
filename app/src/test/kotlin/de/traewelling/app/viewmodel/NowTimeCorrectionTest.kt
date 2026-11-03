package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.util.PreferenceStorageException
import de.traewelling.app.util.PreferenceStoragePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NowTimeCorrectionTest {
    private val status = Gson().fromJson("""{"id":42,"checkin":{"manualDeparture":"2026-11-03T09:00:00Z","manualArrival":"2026-11-03T10:00:00Z"}}""", Status::class.java)
    private val draft = StatusDetailUiState(status = status, isOwnStatus = true, isEditing = true,
        editDeparture = "2026-11-03T09:00:00Z", editArrival = "2026-11-03T10:00:00Z",
        editBody = "Mein Entwurf", editVisibility = 3)
    private val now = Instant.parse("2026-11-03T11:23:45.987Z")

    @Test fun departureNowChangesOnlyTheDraftAtMinutePrecision() {
        val result = draft.withEditTimeNow(NowTimeEvent.DEPARTURE, now)
        assertEquals("2026-11-03T11:23:00Z", result.editDeparture)
        assertEquals(draft.editArrival, result.editArrival)
        assertEquals(draft.status, result.status)
        assertEquals(draft.editBody, result.editBody)
        assertEquals(draft.editVisibility, result.editVisibility)
        assertFalse(result.editArrivalNowRequested)
    }

    @Test fun arrivalNowRecordsExplicitSaveAndTrackingCompletionIntent() {
        val result = draft.withEditTimeNow(NowTimeEvent.ARRIVAL, now)
        assertEquals("2026-11-03T11:23:00Z", result.editArrival)
        assertEquals(draft.status, result.status)
        assertTrue(result.editArrivalManuallyChanged == true)
        assertTrue(result.editArrivalNowRequested)
    }

    @Test fun bothTimesCanBeStagedBeforeTheFinalOrderCheck() {
        val departure = draft.withEditTimeNow(NowTimeEvent.DEPARTURE, now)
        val invalid = buildStatusEditRequest(departure, draft.editDeparture, draft.editArrival)
        assertThrows(IllegalArgumentException::class.java) { validateStatusEditTimes(departure, invalid) }
        val both = departure.withEditTimeNow(NowTimeEvent.ARRIVAL, now.plusSeconds(120))
        val request = buildStatusEditRequest(both, draft.editDeparture, draft.editArrival)
        validateStatusEditTimes(both, request)
        assertEquals("2026-11-03T11:25:00Z", request.arrival)
    }

    @Test fun nowCannotChangeAnUnownedClosedDeletedOrBusyDraft() {
        for (state in listOf(draft.copy(isOwnStatus = false), draft.copy(isEditing = false),
            draft.copy(isUpdating = true), draft.copy(isDeleting = true), draft.copy(deletedStatusId = 42),
            draft.copy(status = null))) {
            assertEquals(state, state.withEditTimeNow(NowTimeEvent.ARRIVAL, now))
        }
    }

    @Test fun explicitArrivalNowIsSentEvenWhenTheMinuteWasAlreadyDisplayed() {
        val staged = draft.withEditTimeNow(NowTimeEvent.ARRIVAL, Instant.parse(draft.editArrival))
        assertEquals(draft.editArrival, buildStatusEditRequest(staged, draft.editDeparture, draft.editArrival).arrival)
    }

    @Test fun acceptedArrivalCleanupRunsExactlyOnce() = runTest {
        var calls = 0
        assertNull(finishAcceptedArrival { calls++ })
        assertEquals(1, calls)
    }

    @Test fun cleanupStorageFailureIsPartialSuccessWithoutReplay() = runTest {
        var calls = 0
        val warning = finishAcceptedArrival { calls++; throw PreferenceStorageException(PreferenceStoragePhase.IO_ERROR) }
        assertTrue(warning.orEmpty().contains("Ankunft gespeichert"))
        assertEquals(1, calls)
    }

    @Test fun cleanupTimeoutIsBoundedAndDoesNotReplay() = runTest {
        var calls = 0
        val warning = finishAcceptedArrival(timeoutMillis = 50) { calls++; awaitCancellation() }
        assertNotNull(warning)
        assertEquals(1, calls)
        assertEquals(50L, testScheduler.currentTime)
    }

    @Test fun ordinaryCancellationStillPropagatesFromCleanup() = runTest {
        val cancelled = CancellationException("cancelled")
        try { finishAcceptedArrival { throw cancelled }; fail("cancellation expected") }
        catch (failure: CancellationException) { assertEquals(cancelled.message, failure.message) }
    }
}
