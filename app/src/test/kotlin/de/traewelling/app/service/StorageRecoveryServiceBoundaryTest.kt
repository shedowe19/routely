package de.traewelling.app.service

import de.traewelling.app.util.PreferenceStoragePhase
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class StorageRecoveryServiceBoundaryTest {
    @Test fun initialLoadingAndFreshReadyDoNotStopAnInitializingService() {
        var pauses = 0
        val boundary = StorageRecoveryServiceBoundary({ pauses++ })
        boundary.onPhase(PreferenceStoragePhase.LOADING)
        boundary.onPhase(PreferenceStoragePhase.READY)
        assertEquals(0, pauses)
        assertFalse(boundary.isPaused)
    }

    @Test fun retryAndFinalFailureShareOneSynchronousTeardown() {
        var pauses = 0
        val boundary = StorageRecoveryServiceBoundary({ pauses++ })
        boundary.onPhase(PreferenceStoragePhase.RETRYING)
        boundary.onPhase(PreferenceStoragePhase.IO_ERROR)
        boundary.onPhase(PreferenceStoragePhase.CORRUPTED)
        assertEquals(1, pauses)
        assertTrue(boundary.isPaused)
    }

    @Test fun resumedStorageDoesNotReviveTheOldServiceGeneration() {
        var pauses = 0
        val boundary = StorageRecoveryServiceBoundary({ pauses++ })
        boundary.onPhase(PreferenceStoragePhase.IO_ERROR)
        boundary.onPhase(PreferenceStoragePhase.READY)
        boundary.onPhase(PreferenceStoragePhase.RETRYING)
        assertTrue(boundary.isPaused)
        assertEquals(1, pauses)
    }

    @Test fun runningReadFailureAndStateObserverCannotPerformTeardownTwice() {
        val storageFailure = IOException("synthetic storage failure")
        var pauses = 0
        val boundary = StorageRecoveryServiceBoundary({ pauses++ }, { it === storageFailure })
        boundary.onFailure(storageFailure)
        boundary.onPhase(PreferenceStoragePhase.IO_ERROR)
        boundary.onFailure(storageFailure)
        assertEquals(1, pauses)
        assertTrue(boundary.isPaused)
    }

    @Test fun cancellationPropagatesWithoutBecomingStorageFailure() {
        val cancellation = CancellationException("cancelled")
        val boundary = StorageRecoveryServiceBoundary({ fail("must not pause") }, { true })
        try {
            boundary.onFailure(cancellation)
            fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertFalse(boundary.isPaused)
    }

    @Test fun unrelatedFailureKeepsItsIdentityAndIsNotSilentlyHandled() {
        val failure = IllegalStateException("synthetic programming error")
        val boundary = StorageRecoveryServiceBoundary({ fail("must not pause") }, { false })
        try {
            boundary.onFailure(failure)
            fail("unrelated failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertFalse(boundary.isPaused)
    }
}
