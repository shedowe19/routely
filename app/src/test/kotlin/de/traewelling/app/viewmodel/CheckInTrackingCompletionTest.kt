package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.CheckInResult
import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CheckInTrackingCompletionTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()

    @Test fun aFinishedHistoricalTripNeverTouchesAnExistingActiveJourney() = runBlocking {
        var existingActive = 99
        val completed = completeCheckInTracking(result(planned = "2026-11-02T13:00:00Z"), now) {
            existingActive = it
            true
        }
        assertFalse(completed.active)
        assertEquals(99, existingActive)
        assertTrue(completed.notice.orEmpty().contains("abgeschlossene Fahrt"))
    }

    @Test fun anOngoingTripCanStartAndAnotherActiveTripIsNotReplaced() = runBlocking {
        val ongoing = result(planned = "2026-11-03T13:00:00Z")
        var activated = 0
        val started = completeCheckInTracking(ongoing, now) { activated = it; true }
        assertTrue(started.active)
        assertEquals(42, activated)
        val preserved = completeCheckInTracking(ongoing, now) { false }
        assertFalse(preserved.active)
        assertTrue(preserved.notice.orEmpty().contains("bestehende Reisebegleitung"))
    }

    @Test fun theFinalCorrectedArrivalOverridesFutureProviderAndPlannedArrival() = runBlocking {
        val completed = completeCheckInTracking(result(manual = "2026-11-03T11:59:00Z",
            real = "2026-11-03T13:00:00Z", planned = "2026-11-03T12:50:00Z"), now) {
            fail("A manually finished trip must not be activated"); false
        }
        assertFalse(completed.active)
    }

    @Test fun aSuccessfulManualArrivalPutFinishesBeforeTrackingIsDecidedAndPostIsNeverRepeated() = runBlocking {
        val submission = CheckInSubmission(CheckInRequest("trip", "RE 1", 1, 2,
            "2026-11-03T11:00:00Z", "2026-11-03T13:00:00Z"),
            UpdateStatusRequest(arrival = "2026-11-03T11:59:00Z"))
        val calls = mutableListOf<String>()
        val accepted = submitCheckIn(submission,
            create = { calls += "POST"; Result.success(result(planned = "2026-11-03T13:00:00Z")) },
            onCreated = { calls += "accepted" },
            correctTimes = { _, _ ->
                calls += "PUT"
                Result.success(requireNotNull(result(manual = "2026-11-03T11:59:00Z").status))
            }).getOrThrow()
        val tracking = completeCheckInTracking(accepted.result, now) { calls += "activate"; true }
        assertEquals(listOf("POST", "accepted", "PUT"), calls)
        assertFalse(tracking.active)
    }

    @Test fun invalidManualTimeFallsBackToParseableProviderThenPlanWithoutInventingAnEnd() = runBlocking {
        assertFalse(completeCheckInTracking(result(manual = "invalid", real = "2026-11-03T11:55:00Z",
            planned = "2026-11-03T13:00:00Z"), now) { fail("Provider time is past"); false }.active)
        assertTrue(completeCheckInTracking(result(manual = "invalid", real = "invalid",
            planned = "2026-11-03T13:00:00Z"), now) { true }.active)
        assertFalse(completeCheckInTracking(result(), now) { fail("No proven arrival"); false }.active)
    }

    @Test fun anAcceptedReplyWithoutAUsableStatusDoesNotActivateOrRequestAnotherCreate() = runBlocking {
        for (value in listOf(null, CheckInResult(null, null))) {
            val completion = completeCheckInTracking(value, now) { fail("No ID available"); false }
            assertFalse(completion.active)
        }
    }

    @Test fun activationFailureIsAnHonestLocalNoticeAndOuterCancellationStillPropagates() = runBlocking {
        val trip = result(planned = "2026-11-03T13:00:00Z")
        val failed = completeCheckInTracking(trip, now) { error("storage") }
        assertFalse(failed.active)
        assertTrue(failed.notice.orEmpty().contains("konnte nicht aktiviert"))
        val cancelled = async { completeCheckInTracking(trip, now) { throw CancellationException("session") } }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
    }

    @Test fun aStalledLocalActivationHasABoundedCompletionWithoutUndoingTheSavedTrip() = runTest {
        val trip = result(planned = "2026-11-03T13:00:00Z")
        val completion = completeCheckInTracking(trip, now, activationTimeoutMillis = 15_000) { awaitCancellation() }
        assertFalse(completion.active)
        assertTrue(completion.notice.orEmpty().contains("Fahrt wurde gespeichert"))
    }

    private fun result(manual: String? = null, real: String? = null, planned: String? = null): CheckInResult {
        val status = Gson().fromJson(Gson().toJson(mapOf("id" to 42, "checkin" to mapOf(
            "manualArrival" to manual, "destination" to mapOf("arrivalReal" to real, "arrivalPlanned" to planned)
        ))), Status::class.java)
        return CheckInResult(status, null)
    }
}
