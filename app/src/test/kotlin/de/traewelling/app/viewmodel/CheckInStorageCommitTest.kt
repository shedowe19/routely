package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.CheckInResult
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.data.repository.CheckInAcceptedException
import de.traewelling.app.data.repository.StatusWriteAcceptedException
import de.traewelling.app.util.PreferenceStorageException
import de.traewelling.app.util.PreferenceStoragePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CheckInStorageCommitTest {
    private val submission = CheckInSubmission(
        CheckInRequest("trip", "RE 1", 1, 2, "2026-11-03T10:00:00Z", "2026-11-03T11:00:00Z"),
        UpdateStatusRequest(arrival = "2026-11-03T10:30:00Z")
    )
    private val storageFailure = PreferenceStorageException(PreferenceStoragePhase.IO_ERROR)
    private fun status(body: String = "created"): Status =
        Gson().fromJson("{\"id\":42,\"body\":\"$body\"}", Status::class.java)

    @Test fun acceptedCreationRetainsTheIdDespiteLocalCallbackFailureAndSkipsCorrection() = runBlocking {
        val created = CheckInResult(status(), null)
        var posts = 0
        var confirmedId: Int? = null
        var puts = 0
        val completion = submitCheckIn(submission,
            create = { posts++; Result.failure(CheckInAcceptedException(created, storageFailure)) },
            onCreated = { confirmedId = it?.status?.id; throw storageFailure },
            correctTimes = { _, _ -> puts++; Result.success(status("corrected")) }
        ).getOrThrow()
        assertEquals(42, confirmedId)
        assertSame(created, completion.result)
        assertTrue(completion.warning.orEmpty().contains("angenommen"))
        assertEquals(1, posts)
        assertEquals(0, puts)
    }

    @Test fun typedStorageFailureAfterNormalCreationIsConfirmedWithoutUnsafeCorrection() = runBlocking {
        val created = CheckInResult(status(), null)
        var posts = 0
        var puts = 0
        val completion = submitCheckIn(submission,
            create = { posts++; Result.success(created) },
            onCreated = { assertSame(created, it); throw storageFailure },
            correctTimes = { _, _ -> puts++; Result.success(status("corrected")) }
        ).getOrThrow()
        assertSame(created, completion.result)
        assertTrue(completion.warning.orEmpty().isNotBlank())
        assertEquals(1, posts)
        assertEquals(0, puts)
    }

    @Test fun storageFailureBeforeCreationDoesNotPublishAConfirmationOrDispatchCorrection() = runBlocking {
        var posts = 0
        var confirmations = 0
        var puts = 0
        val result = submitCheckIn(submission,
            create = { posts++; Result.failure(storageFailure) },
            onCreated = { confirmations++ },
            correctTimes = { _, _ -> puts++; Result.success(status()) }
        )
        assertSame(storageFailure, result.exceptionOrNull())
        assertEquals(1, posts)
        assertEquals(0, confirmations)
        assertEquals(0, puts)
    }

    @Test fun bodylessAcceptedCreationStillCompletesOnceWithoutTryingAManualPut() = runBlocking {
        var posts = 0
        var confirmations = 0
        var puts = 0
        val completion = submitCheckIn(submission,
            create = { posts++; Result.failure(CheckInAcceptedException(null, storageFailure)) },
            onCreated = { assertNull(it); confirmations++ },
            correctTimes = { _, _ -> puts++; Result.success(status()) }
        ).getOrThrow()
        assertNull(completion.result)
        assertTrue(completion.warning.orEmpty().contains("angenommen"))
        assertEquals(1, posts)
        assertEquals(1, confirmations)
        assertEquals(0, puts)
    }

    @Test fun acceptedCorrectionRetainsItsReturnedStatusAndReportsAcceptanceWithoutReplay() = runBlocking {
        val created = CheckInResult(status(), null)
        val corrected = status("server accepted correction")
        var posts = 0
        var puts = 0
        val accepted = StatusWriteAcceptedException(42, corrected, cause = storageFailure)
        val completion = submitCheckIn(submission,
            create = { posts++; Result.success(created) },
            onCreated = {},
            correctTimes = { id, _ ->
                assertEquals(42, id)
                puts++
                Result.failure(accepted)
            }
        ).getOrThrow()
        assertSame(corrected, completion.result?.status)
        assertTrue(completion.warning.orEmpty().contains("angenommen"))
        assertEquals(1, posts)
        assertEquals(1, puts)
    }
}
