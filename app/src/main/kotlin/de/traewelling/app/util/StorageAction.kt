package de.traewelling.app.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A failed local action is reported once; neither business writes nor accepted HTTP calls are replayed. */
internal suspend fun runStorageAction(
    onFailure: (PreferenceStorageException) -> Unit,
    action: suspend () -> Unit
) {
    try {
        action()
    } catch (failure: PreferenceStorageException) {
        currentCoroutineContext().ensureActive()
        onFailure(failure)
    }
}
