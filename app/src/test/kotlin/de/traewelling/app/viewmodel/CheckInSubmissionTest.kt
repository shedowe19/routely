package de.traewelling.app.viewmodel

import com.google.gson.Gson
import com.google.gson.JsonParser
import de.traewelling.app.data.model.*
import de.traewelling.app.data.repository.CheckInAcceptedException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CheckInSubmissionTest {
    private val gson = Gson()
    private val origin = StopStation(station = TrainStation(id = 1),
        departurePlanned = "2026-10-06T18:00:00+02:00", departureReal = "2026-10-06T18:05:00+02:00")
    private val destination = StopStation(station = TrainStation(id = 2),
        arrivalPlanned = "2026-10-06T18:30:00+02:00", arrivalReal = "2026-10-06T18:35:00+02:00")
    private val state = CheckInUiState(selectedDeparture = DepartureTrip("trip", null, null,
        origin.departurePlanned, origin.departureReal, null, null, false, origin.station),
        selectedDestination = destination, selectedStation = origin.station)
    private fun status(body: String = "original") = gson.fromJson("{\"id\":42,\"body\":\"$body\"}", Status::class.java)
    private val created get() = CheckInResult(status(), null)

    @Test fun manualRealTimesNeverReplacePlannedPostVisitMarkers() {
        val submission = buildCheckInSubmission(state.copy(manualDeparture = "2026-10-06T18:07:00+02:00",
            manualArrival = "2026-10-06T18:37:00+02:00"), origin)
        assertEquals(origin.departurePlanned, submission.request.departure)
        assertEquals(destination.arrivalPlanned, submission.request.arrival)
        assertEquals("2026-10-06T16:07:00Z", submission.timeCorrection?.departure)
        assertEquals("2026-10-06T16:37:00Z", submission.timeCorrection?.arrival)
    }

    @Test fun aSingleCorrectionDoesNotFreezeTheOtherProviderEvent() {
        val correction = requireNotNull(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin).timeCorrection)
        val json = JsonParser.parseString(gson.toJson(correction)).asJsonObject
        assertFalse(json.has("manualDeparture"))
        assertEquals("2026-10-06T16:37:00Z", json.get("manualArrival").asString)
    }

    @Test fun noManualInputNeedsNoPut() {
        assertNull(buildCheckInSubmission(state.copy(manualArrival = " "), origin).timeCorrection)
    }

    @Test fun absentPlannedArrivalCannotBeReplacedWithRealtimeInTheCreateRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            buildCheckInSubmission(state.copy(selectedDestination = destination.copy(arrivalPlanned = null)), origin)
        }
    }

    @Test fun invalidManualIsoTimeAndReversedCorrectedTimesAreRejectedBeforeCreate() {
        for (invalid in listOf(state.copy(manualArrival = "18:37"),
            state.copy(manualDeparture = "2026-10-06T18:40:00+02:00"))) {
            assertThrows(IllegalArgumentException::class.java) { buildCheckInSubmission(invalid, origin) }
        }
    }

    @Test fun successfulCreateThenCorrectionKeepsTheSameStatusAndExactlyOnePost() = runBlocking {
        val calls = mutableListOf<String>()
        val completion = submitCheckIn(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin),
            create = { calls += "POST"; Result.success(created) },
            onCreated = { calls += "activate" },
            correctTimes = { id, request ->
                assertEquals(42, id)
                assertEquals("2026-10-06T16:37:00Z", request.arrival)
                calls += "PUT"
                Result.success(status("corrected"))
            }).getOrThrow()
        assertEquals(listOf("POST", "activate", "PUT"), calls)
        assertEquals("corrected", completion.result?.status?.body)
        assertNull(completion.warning)
    }

    @Test fun failedCorrectionIsPartialSuccessWithoutAnotherCreate() = runBlocking {
        var posts = 0
        val completion = submitCheckIn(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin),
            { posts++; Result.success(created) }, {}, { _, _ -> Result.failure(IOException("offline")) }).getOrThrow()
        assertEquals(1, posts)
        assertEquals(42, completion.result?.status?.id)
        assertTrue(requireNotNull(completion.warning).contains("Du bist eingecheckt"))
        assertTrue(requireNotNull(completion.warning).contains("erneuter Check-in ist nicht nötig"))
    }

    @Test fun activationStorageFailureStillRetainsTheCreatedStatusAndAppliesTheCorrection() = runBlocking {
        var puts = 0
        val completion = submitCheckIn(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin),
            { Result.success(created) }, { throw IOException("storage unavailable") },
            { _, _ -> puts++; Result.success(status("corrected")) }).getOrThrow()
        assertEquals(1, puts)
        assertEquals("corrected", completion.result?.status?.body)
        assertTrue(requireNotNull(completion.warning).contains("Reisebegleitung"))
    }

    @Test fun noCorrectionsLeaveTheCreateResponseUnchanged() = runBlocking {
        val completion = submitCheckIn(buildCheckInSubmission(state, origin), { Result.success(created) }, {},
            { _, _ -> fail("No PUT expected"); error("unreachable") }).getOrThrow()
        assertEquals(42, completion.result?.status?.id)
        assertNull(completion.warning)
    }

    @Test fun failedCreateNeverActivatesOrWritesAStatusCorrection() = runBlocking {
        val result = submitCheckIn(buildCheckInSubmission(state, origin), { Result.failure(IOException("offline")) },
            { fail("No activation expected") }, { _, _ -> fail("No PUT expected"); error("unreachable") })
        assertTrue(result.isFailure)
    }

    @Test fun acceptedButIncompleteCreateIsACompletedWarningWithoutPutOrRetry() = runBlocking {
        var accepted = 0
        for (response in listOf(Result.failure<CheckInResult?>(CheckInAcceptedException()), Result.success(null))) {
            val completion = submitCheckIn(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin),
                { response }, { assertNull(it); accepted++ },
                { _, _ -> fail("No status ID is available for PUT"); error("unreachable") }).getOrThrow()
            assertNull(completion.result)
            assertTrue(requireNotNull(completion.warning).contains("angenommen"))
        }
        assertEquals(2, accepted)
    }

    @Test fun cancellationDuringCorrectionPropagatesWithoutASecondCreate() = runBlocking {
        var posts = 0
        val request = async {
            submitCheckIn(buildCheckInSubmission(state.copy(manualArrival = "2026-10-06T18:37:00+02:00"), origin),
                { posts++; Result.success(created) }, {}, { _, _ -> throw CancellationException("session ended") })
        }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, posts)
    }
}
