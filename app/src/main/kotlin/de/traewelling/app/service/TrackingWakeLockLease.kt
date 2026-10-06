package de.traewelling.app.service

/** Platform adapter; the Android handle is non-reference-counted and timeout bounded. */
internal interface TrackingWakeLockHandle {
    fun acquire(timeoutMillis: Long)
    fun release()
}

/**
 * Only the current tracking generation may renew its CPU lease. A platform timeout
 * remains the final backstop if the process or its renewal coroutine stops running.
 */
internal class TrackingWakeLockLease(
    private val handle: TrackingWakeLockHandle,
    private val timeoutMillis: Long = TIMEOUT_MILLIS
) {
    private var ownerGeneration: Long? = null

    init {
        require(timeoutMillis > 0L)
    }

    fun start(generation: Long): Boolean {
        ownerGeneration = generation
        return renew(generation)
    }

    fun renew(generation: Long): Boolean {
        if (ownerGeneration != generation) return false
        return runCatching { handle.acquire(timeoutMillis) }.isSuccess
    }

    fun stop() {
        if (ownerGeneration == null) return
        // Clear ownership first: release failure must never permit a stale renewal.
        ownerGeneration = null
        runCatching { handle.release() }
    }

    companion object {
        const val TIMEOUT_MILLIS = 120_000L
        const val RENEW_INTERVAL_MILLIS = 60_000L
    }
}
