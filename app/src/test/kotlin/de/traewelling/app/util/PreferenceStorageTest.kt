package de.traewelling.app.util

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreferenceStorageTest {
    private val setting = booleanPreferencesKey("test_setting")

    @Test fun initialReadExhaustsABoundedRetryBudgetWithoutInventingALogout() = runTest {
        val store = StorageFixture().apply { readFailures = Int.MAX_VALUE }
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = listOf(10, 20), startObserving = false)
        val error = runCatching { storage.readPreferences() }.exceptionOrNull()
        assertTrue(error is PreferenceStorageException)
        assertEquals(3, store.readAttempts)
        assertEquals(PreferenceStoragePhase.IO_ERROR, storage.state.value.phase)
        assertFalse(storage.state.value.hasConfirmedSnapshot)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
        assertEquals("42", store.saved[AuthSessionPreferences.activeStatusId])
    }

    @Test fun transientReadRetriesReturnTheActualSavedSessionAndTrip() = runTest {
        val store = StorageFixture().apply { readFailures = 2 }
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = listOf(10, 20), startObserving = false)
        val values = storage.readPreferences()
        assertEquals(3, store.readAttempts)
        assertEquals("test-token", values[AuthSessionPreferences.accessToken])
        assertEquals("42", values[AuthSessionPreferences.activeStatusId])
        assertTrue(storage.state.value.isReady)
        assertEquals(1L, storage.state.value.revision)
    }

    @Test fun failedLaterReadRetainsOnlyTheConfirmedPresentationWithoutAuthorizingFreshReads() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList(), startObserving = false)
        val before = storage.readPreferences()
        store.readFailures = Int.MAX_VALUE
        assertTrue(runCatching { storage.readPreferences() }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(before, storage.observePreferences().first())
        val attempts = store.readAttempts
        assertTrue(runCatching { storage.readPreferences() }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(attempts, store.readAttempts)
        assertTrue(storage.state.value.hasConfirmedSnapshot)
        assertFalse(storage.state.value.isReady)
    }

    @Test fun explicitRetryReopensReadAccessAndChangesTheReadinessRevision() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList(), startObserving = false)
        storage.readPreferences()
        val revision = storage.state.value.revision
        store.readFailures = 1
        runCatching { storage.readPreferences() }
        assertTrue(storage.retryStorage().isSuccess)
        assertTrue(storage.state.value.isReady)
        assertEquals(revision + 1, storage.state.value.revision)
        assertEquals("cached-route", storage.readPreferences()[AuthSessionPreferences.trackingState])
    }

    @Test fun sharedObserverStopsAfterBoundedFailuresAndCanResumeAfterManualRetry() = runTest {
        val store = StorageFixture().apply { readFailures = Int.MAX_VALUE }
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = listOf(10, 20))
        runCurrent()
        advanceTimeBy(31)
        runCurrent()
        assertEquals(3, store.readAttempts)
        assertEquals(PreferenceStoragePhase.IO_ERROR, storage.state.value.phase)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, store.readAttempts)
        store.readFailures = 0
        assertTrue(storage.retryStorage().isSuccess)
        runCurrent()
        assertEquals("test-token", storage.observePreferences().first()[AuthSessionPreferences.accessToken])
        assertTrue(storage.state.value.isReady)
    }

    @Test fun failedWriteNeverPublishesAnOptimisticValueOrRetriesTheBusinessTransform() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val original = storage.readPreferences()
        store.writeFailure = IOException("private-path-not-for-ui")
        var transforms = 0
        val error = runCatching { storage.editPreferences { transforms++; it[setting] = true } }.exceptionOrNull()
        assertTrue(error is PreferenceStorageException)
        assertFalse(error!!.message!!.contains("private-path"))
        assertEquals(1, transforms)
        assertEquals(1, store.writeAttempts)
        assertEquals(original, store.saved)
        assertEquals(original, storage.observePreferences().first())
        assertFalse(storage.state.value.isReady)
    }

    @Test fun readAvailabilityAloneDoesNotClearAFailedWrite() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        storage.readPreferences()
        store.writeFailure = IOException("disk full")
        runCatching { storage.editPreferences { it[setting] = true } }
        assertTrue(storage.retryStorage().isFailure)
        assertEquals(2, store.writeAttempts)
        assertEquals(PreferenceStoragePhase.IO_ERROR, storage.state.value.phase)
        assertNull(store.saved[setting])
    }

    @Test fun durableRetryProbePreservesCredentialsAndTripAndDoesNotReplayFailedAction() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val original = storage.readPreferences()
        store.writeFailure = IOException("disk full")
        var businessTransforms = 0
        runCatching { storage.editPreferences { businessTransforms++; it[setting] = true } }
        store.writeFailure = null
        assertTrue(storage.retryStorage().isSuccess)
        assertEquals(1, businessTransforms)
        assertNull(store.saved[setting])
        for ((key, value) in original.asMap()) assertEquals(value, store.saved.asMap()[key])
        assertNotNull(store.saved[stringPreferencesKey("storage_probe_revision")])
        assertTrue(storage.state.value.isReady)
    }

    @Test fun businessEditsStillUseTheCurrentAtomicSessionGuard() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val old = AuthSessionPreferences.read(storage.readPreferences())
        storage.editPreferences { AuthSessionPreferences.saveValidated(it, old.serverUrl, "new-token", "bob") }
        var changed = true
        storage.editPreferences { changed = AuthSessionPreferences.clearIfMatches(it, old) }
        assertFalse(changed)
        assertEquals("new-token", storage.readPreferences()[AuthSessionPreferences.accessToken])
    }

    @Test fun cancellationDuringReadPropagatesWithoutEmptyValuesOrRetry() = runTest {
        val store = StorageFixture().apply { beforeRead = { awaitCancellation() } }
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val task = launch { storage.readPreferences() }
        runCurrent()
        task.cancelAndJoin()
        assertEquals(1, store.readAttempts)
        assertEquals(PreferenceStoragePhase.LOADING, storage.state.value.phase)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
    }

    @Test fun cancelledWriteIsNotRelabeledAsStorageFailure() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        storage.readPreferences()
        val cancelled = CancellationException("caller cancelled")
        store.writeFailure = cancelled
        assertSame(cancelled, runCatching { storage.editPreferences { it[setting] = true } }.exceptionOrNull())
        assertTrue(storage.state.value.isReady)
        assertNull(store.saved[setting])
    }

    @Test fun cancellingAReadDuringBackoffRestoresItsPreviousState() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = listOf(250, 750), startObserving = false)
        storage.readPreferences()
        val before = storage.state.value
        store.readFailures = 1
        val task = launch { storage.readPreferences() }
        runCurrent()
        assertEquals(PreferenceStoragePhase.RETRYING, storage.state.value.phase)
        task.cancelAndJoin()
        assertEquals(before, storage.state.value)
        assertEquals(2, store.readAttempts)
    }

    @Test fun programmingFailuresAreNotRetriedOrMisrepresentedAsStorageIO() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val readBug = IllegalStateException("programming failure")
        store.beforeRead = { throw readBug }
        assertSame(readBug, runCatching { storage.readPreferences() }.exceptionOrNull())
        assertEquals(1, store.readAttempts)
        store.beforeRead = null
        storage.readPreferences()
        val validation = IllegalArgumentException("invalid value")
        assertSame(validation, runCatching { storage.editPreferences { throw validation } }.exceptionOrNull())
        assertTrue(storage.state.value.isReady)
    }

    @Test fun corruptionNeverResetsAutomaticallyOrConsumesOrdinaryIORetries() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        assertTrue(runCatching { storage.readPreferences() }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(1, store.readAttempts)
        assertTrue(storage.state.value.canReset)
        assertEquals(0, store.replacements)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
    }

    @Test fun explicitCurrentCorruptionConsentCreatesAFreshSignedOutGeneration() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset)
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        val old = AuthSessionPreferences.read(storage.readPreferences())
        store.corrupted = true
        runCatching { storage.readPreferences() }
        assertTrue(storage.resetCorruptedStorage(storage.state.value.failureId).isSuccess)
        val fresh = storage.readPreferences()
        assertNull(fresh[AuthSessionPreferences.accessToken])
        assertNull(fresh[AuthSessionPreferences.activeStatusId])
        assertNull(fresh[AuthSessionPreferences.trackingState])
        assertEquals(false, fresh[AuthSessionPreferences.recognitionEnabled])
        assertNotEquals(old.revision, AuthSessionPreferences.read(fresh).revision)
        assertNotEquals("legacy", AuthSessionPreferences.read(fresh).revision)
        assertEquals(1, store.replacements)
        assertTrue(storage.state.value.isReady)
    }

    @Test fun resetRequiresCorruptionAndTheCurrentFailureId() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset)
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        storage.readPreferences()
        assertTrue(storage.resetCorruptedStorage(0).isFailure)
        store.corrupted = true
        runCatching { storage.readPreferences() }
        assertTrue(storage.resetCorruptedStorage(storage.state.value.failureId + 1).isFailure)
        assertEquals(0, store.replacements)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
    }

    @Test fun consentOpenedBeforeRecoveryCannotEraseARecoveredSession() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        runCatching { storage.readPreferences() }
        val consent = storage.state.value.failureId
        store.corrupted = false
        assertTrue(storage.retryStorage().isSuccess)
        assertTrue(storage.resetCorruptedStorage(consent).isFailure)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
        assertEquals(0, store.replacements)
    }

    @Test fun aNewCorruptionCycleRejectsThePreviousResetDialog() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        runCatching { storage.readPreferences() }
        val oldConsent = storage.state.value.failureId
        assertTrue(storage.retryStorage().isFailure)
        assertNotEquals(oldConsent, storage.state.value.failureId)
        assertTrue(storage.resetCorruptedStorage(oldConsent).isFailure)
        assertEquals(0, store.replacements)
        assertTrue(storage.resetCorruptedStorage(storage.state.value.failureId).isSuccess)
    }

    @Test fun cancelledResetRevokesItsOneShotAuthorization() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        runCatching { storage.readPreferences() }
        val failureId = storage.state.value.failureId
        store.beforeRead = { awaitCancellation() }
        val task = launch { storage.resetCorruptedStorage(failureId) }
        runCurrent()
        task.cancelAndJoin()
        store.beforeRead = null
        assertTrue(storage.state.value.canReset)
        assertTrue(runCatching { reset.replacement(CorruptionException("later")) }.exceptionOrNull() is CorruptionException)
        assertEquals(0, store.replacements)
    }

    @Test fun failedResetWriteKeepsOriginalDataAndNeedsANewCorruptionDecision() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        runCatching { storage.readPreferences() }
        val oldConsent = storage.state.value.failureId
        store.writeFailure = IOException("write refused")
        assertTrue(storage.resetCorruptedStorage(oldConsent).isFailure)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
        assertEquals(PreferenceStoragePhase.IO_ERROR, storage.state.value.phase)
        store.writeFailure = null
        assertTrue(storage.retryStorage().isFailure)
        assertTrue(storage.state.value.canReset)
        assertTrue(storage.resetCorruptedStorage(oldConsent).isFailure)
        assertTrue(storage.resetCorruptedStorage(storage.state.value.failureId).isSuccess)
        assertNull(store.saved[AuthSessionPreferences.accessToken])
    }

    @Test fun recoveryCancellationRestoresThePriorFailureInsteadOfLeavingAnEndlessSpinner() = runTest {
        val store = StorageFixture().apply { readFailures = 1 }
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList(), startObserving = false)
        runCatching { storage.readPreferences() }
        val previous = storage.state.value
        store.beforeRead = { awaitCancellation() }
        val retry = launch { storage.retryStorage() }
        runCurrent()
        assertTrue(storage.state.value.isBusy)
        retry.cancelAndJoin()
        assertEquals(previous, storage.state.value)
    }

    @Test fun dataStoreSuppressedReplacementWriteFailureRequiresIORetryBeforeNewConsent() = runTest {
        val reset = PreferenceCorruptionReset()
        val store = StorageFixture(reset).apply { corrupted = true }
        val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)
        runCatching { storage.readPreferences() }
        val oldConsent = storage.state.value.failureId
        store.writeFailure = IOException("replacement write rejected")
        store.suppressResetWriteFailure = true
        assertTrue(storage.resetCorruptedStorage(oldConsent).isFailure)
        assertEquals(PreferenceStoragePhase.IO_ERROR, storage.state.value.phase)
        assertFalse(storage.state.value.canReset)
        assertEquals("test-token", store.saved[AuthSessionPreferences.accessToken])
        store.writeFailure = null
        assertTrue(storage.retryStorage().isFailure)
        assertTrue(storage.state.value.canReset)
        assertTrue(storage.resetCorruptedStorage(oldConsent).isFailure)
        assertTrue(storage.resetCorruptedStorage(storage.state.value.failureId).isSuccess)
    }

    @Test fun aQueuedOldObservationCannotRollbackAnAlreadyCommittedSetting() = runTest {
        val initial = fixturePreferences()
        val gate = CompletableDeferred<Unit>()
        var saved = initial
        var reads = 0
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                val number = ++reads
                emit(saved)
                if (number == 1) { gate.await(); emit(initial); awaitCancellation() }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(saved).also { saved = it }
        }
        val storage = PreferenceStorage(store, backgroundScope)
        runCurrent()
        storage.editPreferences { it[setting] = true }
        gate.complete(Unit)
        runCurrent()
        assertEquals(true, storage.observePreferences().first()[setting])
        assertTrue(storage.state.value.isReady)
    }

    @Test fun serializedConcurrentEditsKeepBothChanges() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        storage.readPreferences()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { storage.editPreferences { entered.complete(Unit); release.await(); it[setting] = true } }
        entered.await()
        val second = async { storage.editPreferences { it[stringPreferencesKey("other")] = "kept" } }
        runCurrent()
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(true, storage.readPreferences()[setting])
        assertEquals("kept", storage.readPreferences()[stringPreferencesKey("other")])
    }

    @Test fun anOldObserverFailureCannotUndoASuccessfulManualRecovery() = runTest {
        val initial = fixturePreferences()
        val releaseOldFailure = CompletableDeferred<Unit>()
        var readAttempts = 0
        var failNextRead = false
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                val number = ++readAttempts
                if (failNextRead) { failNextRead = false; throw IOException("temporary") }
                emit(initial)
                if (number == 1) { releaseOldFailure.await(); throw IOException("old observer") }
                awaitCancellation()
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(initial)
        }
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList())
        runCurrent()
        failNextRead = true
        runCatching { storage.readPreferences() }
        assertTrue(storage.retryStorage().isSuccess)
        val recovered = storage.state.value
        releaseOldFailure.complete(Unit)
        runCurrent()
        assertEquals(recovered, storage.state.value)
        assertEquals("test-token", storage.observePreferences().first()[AuthSessionPreferences.accessToken])
    }
}

internal fun fixturePreferences(): Preferences = mutablePreferencesOf().also {
    AuthSessionPreferences.saveValidated(it, "https://example.test", "test-token", "alice")
    AuthSessionPreferences.saveActiveStatusId(it, 42)
    AuthSessionPreferences.saveTrackingState(it, 42, "cached-route", AuthSessionPreferences.read(it))
}.toPreferences()

internal class StorageFixture(private val reset: PreferenceCorruptionReset = PreferenceCorruptionReset()) : DataStore<Preferences> {
    private val values = MutableStateFlow(fixturePreferences())
    var saved: Preferences
        get() = values.value
        set(value) { values.value = value.toPreferences() }
    var readFailures = 0
    var readAttempts = 0
    var writeAttempts = 0
    var writeFailure: Exception? = null
    var corrupted = false
    var replacements = 0
    var suppressResetWriteFailure = false
    var beforeRead: (suspend () -> Unit)? = null

    private suspend fun load() {
        beforeRead?.invoke()
        if (readFailures > 0) { readFailures--; throw IOException("transient private-path") }
        if (corrupted) {
            val replacement = reset.replacement(CorruptionException("invalid file bytes"))
            writeFailure?.let {
                if (suppressResetWriteFailure && it is IOException) {
                    throw CorruptionException("corrupt original").apply { addSuppressed(it) }
                }
                throw it
            }
            saved = replacement
            corrupted = false
            replacements++
        }
    }

    override val data: Flow<Preferences> = flow {
        readAttempts++
        load()
        emitAll(values)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        writeAttempts++
        load()
        val next = transform(saved)
        writeFailure?.let { throw it }
        saved = next
        return saved
    }
}
