package de.traewelling.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Coroutine cancellation must never become an ordinary API error or an offline cache hit. */
internal suspend inline fun <T> apiResult(block: () -> T): Result<T> = try {
    currentCoroutineContext().ensureActive()
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
