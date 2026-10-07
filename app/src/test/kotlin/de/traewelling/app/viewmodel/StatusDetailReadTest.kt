package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.repository.StatusDetailSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusDetailReadTest {
    private val old = status(null)
    private val corrected = status("2026-10-07T12:05:00Z")

    @Test fun timeCorrectionDuringStopoverWaitReplacesBothOldStatusAndStopoverResponse() = runTest {
        var revision = 0L
        var reads = 0
        var stopReads = 0
        val oldStops = CompletableDeferred<Result<List<StopStation>>>()
        val freshStops = listOf(StopStation(uuid = "fresh-visit"))
        val read = async {
            readConsistentStatusDetail(
                readStatus = {
                    ++reads
                    Result.success(StatusDetailSnapshot(if (revision == 0L) old else corrected, revision))
                },
                readStopovers = {
                    ++stopReads
                    if (stopReads == 1) oldStops.await() else Result.success(freshStops)
                },
                isCurrentRevision = { it == revision }
            )
        }
        runCurrent()
        assertFalse(read.isCompleted)
        revision = 1L // The separate CheckIn SUCCESS time-PUT has now committed.
        oldStops.complete(Result.success(listOf(StopStation(uuid = "old-visit"))))
        val result = read.await().getOrThrow()
        assertEquals(corrected.checkin?.manualDeparture, result.status.checkin?.manualDeparture)
        assertEquals(freshStops, result.stopovers?.getOrThrow())
        assertEquals(2, reads)
    }

    @Test fun anUnchangedSnapshotRetainsTheSeparateStopoverErrorForTheExistingFallback() = runTest {
        val failure = IllegalStateException("stops unavailable")
        val result = readConsistentStatusDetail(
            { Result.success(StatusDetailSnapshot(corrected, 1)) },
            { Result.failure(failure) },
            { it == 1L }
        ).getOrThrow()
        assertSame(corrected, result.status)
        assertSame(failure, result.stopovers?.exceptionOrNull())
    }

    @Test fun repeatedContentChangesFailWithoutPublishingEitherOldSnapshotOrLoopingForever() = runTest {
        var reads = 0
        val result = readConsistentStatusDetail(
            { ++reads; Result.success(StatusDetailSnapshot(old, reads.toLong())) },
            { Result.success(emptyList()) },
            { false }
        )
        assertTrue(result.isFailure)
        assertEquals(2, reads)
    }

    @Test fun aStatusWithoutATripHasNoStopoverCallButStillNeedsTheRevisionCheck() = runTest {
        val status = Gson().fromJson("{\"id\":42}", Status::class.java)
        var checks = 0
        val result = readConsistentStatusDetail(
            { Result.success(StatusDetailSnapshot(status, 0)) },
            { error("No trip means no stopover request") },
            { ++checks; true }
        ).getOrThrow()
        assertSame(status, result.status)
        assertNull(result.stopovers)
        assertEquals(1, checks)
    }

    @Test fun aSessionChangeDuringTheSecondEndpointRemainsCoroutineCancellation() = runTest {
        try {
            readConsistentStatusDetail(
                { Result.success(StatusDetailSnapshot(old, 0)) },
                { Result.success(emptyList()) },
                { throw CancellationException("session changed") }
            )
            fail("Session cancellation must not become an ordinary detail result")
        } catch (_: CancellationException) {
            // The owning ViewModel request is cancelled rather than publishing another login.
        }
    }

    private fun status(departure: String?): Status = Gson().fromJson(
        """{"id":42,"checkin":{"trip":9,"manualDeparture":${Gson().toJson(departure)}}}""",
        Status::class.java
    )
}
