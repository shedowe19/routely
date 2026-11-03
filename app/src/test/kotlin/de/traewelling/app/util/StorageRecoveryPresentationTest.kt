package de.traewelling.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRecoveryPresentationTest {
    private val ready = PreferenceStorageState(PreferenceStoragePhase.READY, revision = 4, hasConfirmedSnapshot = true)

    @Test fun coldStartCannotDisplaySyntheticSignedOutSessionBeforeAuthHydrates() {
        assertFalse(canUseStoredSession(ready, false, "legacy", false, ConfirmedStorageSession("legacy", false, 4)))
        assertTrue(canUseStoredSession(ready, true, "legacy", false, ConfirmedStorageSession("legacy", false, 4)))
    }

    @Test fun recoveryNeedsFreshSessionProbeEvenWhenAnAuthSnapshotWasLoadedEarlier() {
        assertFalse(canUseStoredSession(ready, true, "account-a", true, null))
        assertTrue(canUseStoredSession(ready, true, "account-a", true, ConfirmedStorageSession("account-a", true, 4)))
    }

    @Test fun resetOrAccountChangeMustHydrateBeforeRootInteractionsReturn() {
        assertFalse(canUseStoredSession(ready, true, "old", true, ConfirmedStorageSession("reset", false, 4)))
        assertTrue(canUseStoredSession(ready, true, "reset", false, ConfirmedStorageSession("reset", false, 4)))
    }

    @Test fun matchingRevisionDoesNotAcceptSyntheticLoggedOutValue() {
        assertFalse(canUseStoredSession(ready, true, "legacy", false, ConfirmedStorageSession("legacy", true, 4)))
    }

    @Test fun preservedSessionNeverAuthorizesInteractionWhileStorageIsUnavailable() {
        for (phase in listOf(PreferenceStoragePhase.LOADING, PreferenceStoragePhase.RETRYING,
            PreferenceStoragePhase.IO_ERROR, PreferenceStoragePhase.CORRUPTED)) {
            assertFalse(canUseStoredSession(ready.copy(phase = phase), true, "a", true, ConfirmedStorageSession("a", true, 4)))
        }
    }

    @Test fun oldProbeCannotOpenRootBeforeNewRecoveryGenerationIsProbed() {
        val confirmed = ConfirmedStorageSession("a", true, 4)
        assertTrue(canUseStoredSession(ready, true, "a", true, confirmed))
        assertFalse(canUseStoredSession(ready.copy(revision = 5), true, "a", true, confirmed))
        assertTrue(canUseStoredSession(ready.copy(revision = 5), true, "a", true, confirmed.copy(storageRevision = 5)))
    }

    @Test fun onlyCurrentCorruptionCanUseItsOwnResetConsent() {
        val corrupted = ready.copy(phase = PreferenceStoragePhase.CORRUPTED, failureId = 9)
        assertTrue(canConfirmStorageReset(9, corrupted))
        assertFalse(canConfirmStorageReset(null, corrupted))
        assertFalse(canConfirmStorageReset(8, corrupted))
        assertFalse(canConfirmStorageReset(9, corrupted.copy(failureId = 10)))
        assertFalse(canConfirmStorageReset(9, corrupted.copy(phase = PreferenceStoragePhase.READY)))
        assertFalse(canConfirmStorageReset(9, corrupted.copy(phase = PreferenceStoragePhase.IO_ERROR)))
    }

    @Test fun suspendedStartReadCannotCrossFailureOrSuccessfulRetry() {
        assertTrue(canResumeStoredServices(4, ready, false))
        assertFalse(canResumeStoredServices(4, ready.copy(phase = PreferenceStoragePhase.IO_ERROR), false))
        assertFalse(canResumeStoredServices(4, ready.copy(revision = 5), false))
    }

    @Test fun sameSavedTripWaitsForOldServiceTeardownThenCanResume() {
        assertFalse(canResumeStoredServices(4, ready, true))
        assertTrue(canResumeStoredServices(4, ready, false))
    }
}
