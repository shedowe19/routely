package de.traewelling.app.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeletedStatusCompletionTest {
    @Test fun aStorageFailureAfterServerDeletionStillCompletesExactlyOnceWithAWarning() = runTest {
        var cleanups = 0
        var completions = 0
        var warning: Exception? = null
        completeDeletedStatus(cleanup = { ++cleanups; throw IOException("storage unavailable") }) {
            ++completions
            warning = it
        }
        assertEquals(1, cleanups)
        assertEquals(1, completions)
        assertTrue(warning is IOException)
    }

    @Test fun successfulCleanupCompletesOnceWithoutWarning() = runTest {
        var completions = 0
        completeDeletedStatus(cleanup = {}) {
            ++completions
            assertNull(it)
        }
        assertEquals(1, completions)
    }

    @Test fun cancellationIsNeverConvertedIntoASuccessOrWarning() = runTest {
        var completions = 0
        try {
            completeDeletedStatus(cleanup = { throw CancellationException("view closed") }) { ++completions }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, completions)
        }
    }

    @Test fun aCancelledViewDoesNotNavigateWhenDelayedCleanupReturns() = runTest {
        val delayed = CompletableDeferred<Unit>()
        var completions = 0
        val task = backgroundScope.launch {
            completeDeletedStatus(cleanup = { withContext(NonCancellable) { delayed.await() } }) { ++completions }
        }
        runCurrent()
        task.cancel()
        delayed.complete(Unit)
        runCurrent()
        assertTrue(task.isCancelled)
        assertEquals(0, completions)
    }

    @Test fun slowCommittedCleanupProducesABoundedWarningWithoutRetryingDeletion() = runTest {
        var cleanups = 0
        var completions = 0
        var warning: Exception? = null
        completeDeletedStatus(
            cleanup = { ++cleanups; awaitCancellation() },
            completion = { ++completions; warning = it }, cleanupTimeoutMillis = 15
        )
        assertEquals(1, cleanups)
        assertEquals(1, completions)
        assertTrue(warning is IllegalStateException)
    }
}
