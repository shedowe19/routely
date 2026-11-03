package de.traewelling.app.util

import de.traewelling.app.data.repository.apiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StorageActionTest {
    @Test fun aCancelledRepositoryActionCannotReturnAnOrdinaryStorageFailure() = runTest {
        var resultReturned = false
        val action = launch {
            apiResult<Unit> {
                currentCoroutineContext().cancel()
                throw PreferenceStorageException(PreferenceStoragePhase.IO_ERROR)
            }
            resultReturned = true
        }
        action.join()
        assertTrue(action.isCancelled)
        assertFalse(resultReturned)
    }

    @Test fun aCancelledOperationCannotReportAStorageFailureIntoANewerViewState() = runTest {
        var reported = 0
        val action = launch {
            runStorageAction(onFailure = { reported++ }) {
                currentCoroutineContext().cancel()
                throw PreferenceStorageException(PreferenceStoragePhase.IO_ERROR)
            }
        }
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(0, reported)
    }

    @Test fun failedActionIsReportedOnceWithoutRepeatingTheBusinessWrite() = runTest {
        var writes = 0; var reported = 0
        runStorageAction(onFailure = { reported++ }) {
            writes++
            throw PreferenceStorageException(PreferenceStoragePhase.IO_ERROR)
        }
        assertEquals(1, writes); assertEquals(1, reported)
    }

    @Test fun cancellationIsNotPresentedAsAStorageFailure() = runTest {
        val cancellation = CancellationException("cancelled"); var reported = 0
        try { runStorageAction(onFailure = { reported++ }) { throw cancellation }; fail("cancellation expected") }
        catch (failure: CancellationException) { assertSame(cancellation, failure) }
        assertEquals(0, reported)
    }

    @Test fun programmingErrorsRemainTheirOriginalFailures() = runTest {
        val invalid = IllegalArgumentException("invalid input"); var reported = 0
        try { runStorageAction(onFailure = { reported++ }) { throw invalid }; fail("validation failure expected") }
        catch (failure: IllegalArgumentException) { assertSame(invalid, failure) }
        assertEquals(0, reported)
    }
}
