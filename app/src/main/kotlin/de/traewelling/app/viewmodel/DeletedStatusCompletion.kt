package de.traewelling.app.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** A committed DELETE remains successful even if its local tracking cleanup fails. */
internal suspend fun completeDeletedStatus(
    cleanup: suspend () -> Unit,
    cleanupTimeoutMillis: Long = 15_000L,
    completion: (Exception?) -> Unit
) {
    val localFailure = try {
        withTimeout(cleanupTimeoutMillis) { cleanup() }
        null
    } catch (timeout: TimeoutCancellationException) {
        IllegalStateException("Local tracking cleanup timed out", timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    coroutineContext.ensureActive()
    completion(localFailure)
}
