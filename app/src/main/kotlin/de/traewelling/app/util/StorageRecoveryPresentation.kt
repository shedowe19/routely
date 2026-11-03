package de.traewelling.app.util

/** Identity only: never expose stored credentials in a presentation or saved Compose state. */
internal data class ConfirmedStorageSession(val revision: String, val isLoggedIn: Boolean, val storageRevision: Long)

internal fun canUseStoredSession(
    storage: PreferenceStorageState,
    hasLoadedSession: Boolean,
    sessionRevision: String,
    isLoggedIn: Boolean,
    confirmed: ConfirmedStorageSession?
): Boolean = storage.isReady && hasLoadedSession && confirmed != null &&
    confirmed.storageRevision == storage.revision && confirmed.revision == sessionRevision &&
    confirmed.isLoggedIn == isLoggedIn

internal fun canConfirmStorageReset(expectedFailureId: Long?, storage: PreferenceStorageState): Boolean =
    expectedFailureId != null && storage.canReset && storage.failureId == expectedFailureId

/** A read may suspend across a failure, successful retry, or old service teardown. */
internal fun canResumeStoredServices(
    requestedRevision: Long,
    storage: PreferenceStorageState,
    storagePauseInProgress: Boolean
): Boolean = storage.isReady && storage.revision == requestedRevision && !storagePauseInProgress
