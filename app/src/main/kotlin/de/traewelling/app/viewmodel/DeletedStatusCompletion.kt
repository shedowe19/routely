package de.traewelling.app.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A committed DELETE remains successful even if its local tracking cleanup fails. */
internal suspend fun completeDeletedStatus(
    cleanup: suspend () -> Unit,
    completion: (Exception?) -> Unit
) {
    val localFailure = try {
        cleanup()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    coroutineContext.ensureActive()
    completion(localFailure)
}
