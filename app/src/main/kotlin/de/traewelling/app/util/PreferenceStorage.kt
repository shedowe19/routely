package de.traewelling.app.util

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class PreferenceStoragePhase { LOADING, READY, RETRYING, IO_ERROR, CORRUPTED }

/** Contains no preference values or exception text. Revision changes after a successful recovery. */
data class PreferenceStorageState(
    val phase: PreferenceStoragePhase = PreferenceStoragePhase.LOADING,
    val failureId: Long = 0,
    val revision: Long = 0,
    val hasConfirmedSnapshot: Boolean = false
) {
    val isReady: Boolean get() = phase == PreferenceStoragePhase.READY
    val isBusy: Boolean get() = phase == PreferenceStoragePhase.LOADING || phase == PreferenceStoragePhase.RETRYING
    val canReset: Boolean get() = phase == PreferenceStoragePhase.CORRUPTED
}

class PreferenceStorageException internal constructor(
    val storagePhase: PreferenceStoragePhase,
    cause: Throwable? = null
) : IOException(
    if (storagePhase == PreferenceStoragePhase.CORRUPTED)
        "Die gespeicherten App-Daten konnten nicht gelesen werden."
    else "Die App-Daten konnten gerade nicht sicher gelesen oder gespeichert werden.",
    cause
)

fun isPreferenceStorageFailure(failure: Throwable): Boolean = failure is PreferenceStorageException

/** Only the current, explicitly confirmed recovery operation can replace corrupted contents. */
internal class PreferenceCorruptionReset {
    private val authorized = AtomicBoolean(false)
    val handler = ReplaceFileCorruptionHandler<Preferences> { failure -> replacement(failure) }

    fun authorize() { check(authorized.compareAndSet(false, true)) }
    fun revoke() { authorized.set(false) }

    fun replacement(failure: CorruptionException): Preferences {
        if (!authorized.compareAndSet(true, false)) throw failure
        return mutablePreferencesOf().also {
            AuthSessionPreferences.renew(it)
            it[AuthSessionPreferences.recognitionEnabled] = false
        }.toPreferences()
    }
}

/**
 * Owns storage health once per DataStore. Confirmed values are an observation for presentation,
 * never a replacement for a fresh read or the current value inside a DataStore transaction.
 */
internal class PreferenceStorage(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val corruptionReset: PreferenceCorruptionReset = PreferenceCorruptionReset(),
    private val retryDelaysMillis: List<Long> = listOf(250L, 750L),
    startObserving: Boolean = true
) {
    private val operation = Mutex()
    private val _state = MutableStateFlow(PreferenceStorageState())
    val state: StateFlow<PreferenceStorageState> = _state.asStateFlow()
    private val confirmed = MutableStateFlow<Preferences?>(null)
    private val restartObservation = Channel<Unit>(Channel.CONFLATED)
    private var writeProbeRequired = false
    private var failureSequence = 0L
    init { if (startObserving) scope.launch { observeStore() } }

    fun observePreferences(): Flow<Preferences> = confirmed.filterNotNull()

    suspend fun readPreferences(): Preferences = operation.withLock {
        requireAvailable()
        readWithRetries()
    }

    suspend fun editPreferences(transform: suspend (MutablePreferences) -> Unit): Preferences = operation.withLock {
        requireAvailable()
        if (!_state.value.isReady) readWithRetries()
        try {
            store.edit(transform).also(::confirm)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            writeProbeRequired = true
            throw recordFailure(failure)
        }
    }

    suspend fun retryStorage(): Result<Unit> = recover {
        if (writeProbeRequired) {
            // Equal/no-op updates may avoid disk IO. A private marker proves a durable write.
            val values = store.edit { it[PROBE_REVISION] = UUID.randomUUID().toString() }
            writeProbeRequired = false
            confirm(values)
        } else readWithRetries()
    }

    suspend fun resetCorruptedStorage(expectedFailureId: Long): Result<Unit> = operation.withLock {
        currentCoroutineContext().ensureActive()
        val previous = _state.value
        if (!previous.canReset || previous.failureId != expectedFailureId) {
            return@withLock Result.failure(PreferenceStorageException(previous.phase))
        }
        _state.value = previous.copy(phase = PreferenceStoragePhase.RETRYING)
        corruptionReset.authorize()
        try {
            // The handler is part of this same DataStore's read/write boundary. No external delete.
            val values = store.data.first()
            writeProbeRequired = false
            confirm(values)
            restartObservation.trySend(Unit)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (_state.value.phase == PreferenceStoragePhase.RETRYING) _state.value = previous
            throw cancelled
        } catch (failure: IOException) {
            // DataStore preserves its original CorruptionException and suppresses a failed
            // replacement write onto it. That is a write failure, not renewed reset consent.
            val writeFailure = if (failure is CorruptionException)
                failure.suppressed.filterIsInstance<IOException>().firstOrNull { it !is CorruptionException }
            else null
            val reportedFailure = writeFailure ?: failure
            if (reportedFailure !is CorruptionException) writeProbeRequired = true
            Result.failure(recordFailure(reportedFailure))
        } catch (failure: Exception) {
            if (_state.value.phase == PreferenceStoragePhase.RETRYING) _state.value = previous
            throw failure
        } finally {
            corruptionReset.revoke()
        }
    }

    private suspend fun recover(action: suspend () -> Unit): Result<Unit> = operation.withLock {
        currentCoroutineContext().ensureActive()
        val previous = _state.value
        if (previous.isReady) return@withLock Result.success(Unit)
        _state.value = previous.copy(phase = PreferenceStoragePhase.RETRYING)
        try {
            action()
            restartObservation.trySend(Unit)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (_state.value.phase == PreferenceStoragePhase.RETRYING) _state.value = previous
            throw cancelled
        } catch (failure: IOException) {
            Result.failure(if (failure is PreferenceStorageException) failure else recordFailure(failure))
        } catch (failure: Exception) {
            if (_state.value.phase == PreferenceStoragePhase.RETRYING) _state.value = previous
            throw failure
        }
    }

    private suspend fun readWithRetries(): Preferences {
        var attempt = 0
        val previous = _state.value
        try {
            while (true) {
                try {
                    return store.data.first().also(::confirm)
                } catch (failure: CorruptionException) {
                    throw recordFailure(failure)
                } catch (failure: IOException) {
                    if (attempt >= retryDelaysMillis.size) throw recordFailure(failure)
                    _state.value = _state.value.copy(phase = PreferenceStoragePhase.RETRYING)
                    delay(retryDelaysMillis[attempt++])
                }
            }
        } catch (cancelled: CancellationException) {
            // Includes cancellation during backoff, not only during the actual read.
            if (_state.value.phase == PreferenceStoragePhase.RETRYING) _state.value = previous
            throw cancelled
        }
    }

    private suspend fun observeStore() {
        var attempt = 0
        while (true) {
            var observedRevision = _state.value.revision
            var observedFailure = _state.value.failureId
            try {
                store.data.collect {
                    operation.withLock {
                        val phase = _state.value.phase
                        if (!writeProbeRequired && phase != PreferenceStoragePhase.IO_ERROR && phase != PreferenceStoragePhase.CORRUPTED) {
                            // Re-read under the operation lock so a queued old emission cannot beat a write.
                            confirm(store.data.first())
                            attempt = 0
                            observedRevision = _state.value.revision
                            observedFailure = _state.value.failureId
                        }
                    }
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                var supersededByRecovery = false
                val retry = operation.withLock {
                    if (_state.value.revision != observedRevision || _state.value.failureId != observedFailure) {
                        // A fresh read/write or another failure won while this collector was unwinding.
                        supersededByRecovery = _state.value.isReady
                        false
                    } else if (failure !is CorruptionException && !writeProbeRequired && attempt < retryDelaysMillis.size) {
                        _state.value = _state.value.copy(phase = PreferenceStoragePhase.RETRYING)
                        true
                    } else {
                        recordFailure(failure)
                        false
                    }
                }
                if (supersededByRecovery) attempt = 0
                else if (retry) delay(retryDelaysMillis[attempt++]) else {
                    restartObservation.receive()
                    attempt = 0
                }
            }
        }
    }

    private fun requireAvailable() {
        val phase = _state.value.phase
        if (phase != PreferenceStoragePhase.READY && phase != PreferenceStoragePhase.LOADING) {
            throw PreferenceStorageException(phase)
        }
    }

    private fun confirm(values: Preferences) {
        confirmed.value = values.toPreferences()
        val previous = _state.value
        _state.value = previous.copy(
            phase = PreferenceStoragePhase.READY,
            revision = if (previous.isReady) previous.revision else previous.revision + 1,
            hasConfirmedSnapshot = true
        )
    }

    private fun recordFailure(failure: IOException): PreferenceStorageException {
        val phase = if (failure is CorruptionException) PreferenceStoragePhase.CORRUPTED else PreferenceStoragePhase.IO_ERROR
        _state.value = _state.value.copy(phase = phase, failureId = ++failureSequence)
        return PreferenceStorageException(phase, failure)
    }

    private companion object {
        val PROBE_REVISION = stringPreferencesKey("storage_probe_revision")
    }
}
