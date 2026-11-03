package de.traewelling.app.service

import de.traewelling.app.util.PreferenceStoragePhase
import de.traewelling.app.util.isPreferenceStorageFailure
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A failed storage generation can only resume through a new, visibly authorized service start. */
internal class StorageRecoveryServiceBoundary(
    private val pause: () -> Unit,
    private val isStorageFailure: (Throwable) -> Boolean = ::isPreferenceStorageFailure
) {
    private val paused = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()

    fun onPhase(phase: PreferenceStoragePhase) {
        when (phase) {
            PreferenceStoragePhase.RETRYING,
            PreferenceStoragePhase.IO_ERROR,
            PreferenceStoragePhase.CORRUPTED -> pauseOnce()
            PreferenceStoragePhase.LOADING, PreferenceStoragePhase.READY -> Unit
        }
    }

    fun onFailure(failure: Throwable) {
        if (failure is CancellationException || !isStorageFailure(failure)) throw failure
        pauseOnce()
    }

    private fun pauseOnce() {
        if (paused.compareAndSet(false, true)) pause()
    }
}
