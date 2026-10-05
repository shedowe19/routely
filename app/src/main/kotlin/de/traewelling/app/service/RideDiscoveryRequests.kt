package de.traewelling.app.service

import kotlinx.coroutines.CancellationException

/** One discovery stage: partial and successful empty responses remain valid. */
internal class RideDiscoveryRequests {
    private var hasSuccess = false
    private var firstFailure: Throwable? = null

    fun <T : Any> valueOrNull(result: Result<T>): T? {
        val value = result.getOrElse { error ->
            // Repository runCatching can wrap cancellation; it must still stop discovery.
            if (error is CancellationException) throw error
            if (firstFailure == null) firstFailure = error
            return null
        }
        hasSuccess = true
        return value
    }

    fun throwIfAllFailed() {
        // No requests means there were no nearby stations or matching departures.
        if (!hasSuccess) firstFailure?.let { throw it }
    }
}
