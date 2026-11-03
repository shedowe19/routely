package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NowTimeCorrectionTest {
    private val gson = Gson()
    private val now = Instant.parse("2026-11-03T10:30:58.900Z")
    private val session = AuthSession("https://example.test", "test-token", "test-session")
    private fun status(extra: String = "", id: Int = 42): Status = gson.fromJson("""
        {"id":$id,"body":"keep this text","visibility":3,"checkin":{
          "origin":{"station":{"id":1,"name":"Start"},"departurePlanned":"2026-11-03T09:00:00Z"},
          "destination":{"station":{"id":2,"name":"Ziel"},"arrivalPlanned":"2026-11-03T11:00:00Z"}
          $extra}}
    """, Status::class.java)

    @Test fun departureCapturesClickedMinuteAndSendsOnlyManualDeparture() {
        val submission = buildNowTimeSubmission(status(), NowTimeEvent.DEPARTURE, now)
        val json = gson.toJsonTree(submission.request).asJsonObject
        assertEquals(Instant.parse("2026-11-03T10:30:00Z"), submission.time)
        assertEquals(setOf("manualDeparture"), json.keySet())
        assertEquals("2026-11-03T10:30:00Z", json["manualDeparture"].asString)
    }

    @Test fun arrivalChangesTheSelectedTargetsTimeWithoutSendingDestinationOrDeparture() {
        val submission = buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now)
        val json = gson.toJsonTree(submission.request).asJsonObject
        assertEquals(setOf("manualArrival"), json.keySet())
        assertEquals("2026-11-03T10:30:00Z", json["manualArrival"].asString)
    }

    @Test fun manualCounterpartHasPriorityWhenCheckingChronologicalOrder() {
        assertInvalid { buildNowTimeSubmission(status(",\"manualDeparture\":\"2026-11-03T11:00:00Z\""), NowTimeEvent.ARRIVAL, now) }
        assertInvalid { buildNowTimeSubmission(status(",\"manualArrival\":\"2026-11-03T09:00:00Z\""), NowTimeEvent.DEPARTURE, now) }
        assertNotNull(buildNowTimeSubmission(status(",\"manualDeparture\":\"2026-11-03T11:30:00+01:00\""),
            NowTimeEvent.ARRIVAL, now))
    }

    @Test fun invalidManualTimestampFallsBackToProviderOrTimetableInsteadOfBeingSent() {
        assertEquals("2026-11-03T10:30:00Z", buildNowTimeSubmission(
            status(",\"manualDeparture\":\"invalid\""), NowTimeEvent.ARRIVAL, now).request.arrival)
    }

    @Test fun theGateRejectsASecondClickBeforeCompositionCanObserveBusyState() {
        val gate = NowTimeCorrectionGate()
        val state = StatusDetailUiState(status = status(), isOwnStatus = true)
        val first = requireNotNull(gate.begin(state, NowTimeEvent.DEPARTURE, now))
        assertNull(gate.begin(state, NowTimeEvent.ARRIVAL, now))
        gate.finish(first)
        assertNotNull(gate.begin(state, NowTimeEvent.ARRIVAL, now))
    }

    @Test fun foreignEditingUpdatingDeletingOrDeletedStatusesCannotDispatch() {
        val own = StatusDetailUiState(status = status(), isOwnStatus = true)
        for (state in listOf(own.copy(isOwnStatus = false), own.copy(isEditing = true),
            own.copy(isUpdating = true), own.copy(isDeleting = true), own.copy(deletedStatusId = 42))) {
            assertNull(NowTimeCorrectionGate().begin(state, NowTimeEvent.ARRIVAL, now))
        }
    }

    @Test fun anOldCompletionCannotReleaseTheNewStatusGateAfterNavigation() {
        val gate = NowTimeCorrectionGate()
        val first = requireNotNull(gate.begin(StatusDetailUiState(status = status(), isOwnStatus = true), NowTimeEvent.ARRIVAL, now))
        gate.reset()
        val next = StatusDetailUiState(status = status(id = 43), isOwnStatus = true)
        assertNotNull(gate.begin(next, NowTimeEvent.DEPARTURE, now))
        gate.finish(first)
        assertNull(gate.begin(next, NowTimeEvent.ARRIVAL, now))
    }

    @Test fun confirmedArrivalFinishesOnlyItsStatusAndSessionAndDepartureDoesNotFinish() = runTest {
        for (event in NowTimeEvent.entries) {
            var cleanups = 0
            var requests = 0
            val submission = buildNowTimeSubmission(status(), event, now)
            val result = submitNowTimeCorrection(submission, session,
                update = { id, request, owner, committed ->
                    requests++
                    assertEquals(42, id); assertEquals(submission.request, request); assertEquals(session, owner)
                    committed(owner)
                    Result.success(status())
                },
                finishArrival = { id, owner -> assertEquals(42, id); assertEquals(session, owner); cleanups++ })
            assertTrue(result.isSuccess)
            assertEquals(1, requests)
            assertEquals(if (event == NowTimeEvent.ARRIVAL) 1 else 0, cleanups)
        }
    }

    @Test fun rejectedCorrectionNeverStopsTheExistingJourney() = runTest {
        var cleanups = 0
        val result = submitNowTimeCorrection(buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now), session,
            update = { _, _, _, _ -> Result.failure(IOException("HTTP 400")) },
            finishArrival = { _, _ -> cleanups++ })
        assertTrue(result.isFailure)
        assertEquals(0, cleanups)
    }

    @Test fun cleanupFailureDoesNotTurnACommittedCorrectionIntoARetryableFailure() = runTest {
        var requests = 0
        val result = submitNowTimeCorrection(buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now), session,
            update = { _, _, owner, committed -> requests++; committed(owner); Result.success(status()) },
            finishArrival = { _, _ -> throw IOException("storage") },
            onCleanupFailure = { throw IllegalStateException("warning delivery also failed") })
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrThrow().warning)
        assertEquals(1, requests)
    }

    @Test fun acceptedIncompleteResponseStillReportsSuccessAndFinishesArrival() = runTest {
        var cleanups = 0
        val result = submitNowTimeCorrection(buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now), session,
            update = { _, _, owner, committed -> committed(owner); Result.failure(IOException("incomplete response")) },
            finishArrival = { _, _ -> cleanups++ })
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().status)
        assertTrue(result.getOrThrow().warning.orEmpty().contains("Zeit gespeichert"))
        assertEquals(1, cleanups)
    }

    @Test fun cleanupHasABoundedDeadlineWithoutRepeatingThePut() = runTest {
        var requests = 0
        val result = submitNowTimeCorrection(buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now), session,
            update = { _, _, owner, committed -> requests++; committed(owner); Result.success(status()) },
            finishArrival = { _, _ -> awaitCancellation() }, cleanupTimeoutMillis = 10)
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrThrow().warning)
        assertEquals(1, requests)
    }

    @Test fun cancellationBeforeACommitCannotBecomeASuccess() = runTest {
        var cleanups = 0
        try {
            submitNowTimeCorrection(buildNowTimeSubmission(status(), NowTimeEvent.ARRIVAL, now), session,
                update = { _, _, _, _ -> throw CancellationException("session changed") },
                finishArrival = { _, _ -> cleanups++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, cleanups)
        }
    }

    private fun assertInvalid(action: () -> Unit) {
        try { action(); fail("Invalid event order must be rejected") } catch (_: IllegalArgumentException) { }
    }
}
