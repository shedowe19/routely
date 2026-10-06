package de.traewelling.app.service

internal data class TrackingSpeechConfiguration(
    val enabled: Boolean = false,
    val engine: String? = null,
    val language: String? = null,
    val voice: String? = null
)

internal enum class SpeechEngineState { DISABLED, INITIALIZING, READY, RETRY_WAIT, FAILED }

/** Separate engine attempts from their configuration owner, so retries stay bounded. */
internal class SpeechInitializationLifecycle {
    private val retries = LifecycleRetryBudget()
    private var sequence = 0L
    private var owner = 0L
    private var attempt: Long? = null
    var state: SpeechEngineState = SpeechEngineState.DISABLED
        private set

    fun restart(enabled: Boolean) {
        owner = ++sequence
        attempt = null
        retries.stop()
        state = if (enabled) SpeechEngineState.RETRY_WAIT else SpeechEngineState.DISABLED
        if (enabled) retries.begin(owner)
    }

    fun beginAttempt(): Long? {
        if (state != SpeechEngineState.RETRY_WAIT) return null
        return (++sequence).also { attempt = it; state = SpeechEngineState.INITIALIZING }
    }

    fun owns(token: Long): Boolean = attempt == token && state != SpeechEngineState.DISABLED

    fun ready(token: Long): Boolean {
        if (!owns(token) || state != SpeechEngineState.INITIALIZING) return false
        retries.success(owner)
        state = SpeechEngineState.READY
        return true
    }

    fun failed(token: Long): Long? {
        if (!owns(token) || state != SpeechEngineState.INITIALIZING) return null
        attempt = null
        val delay = retries.failure(owner)
        state = if (delay == null) SpeechEngineState.FAILED else SpeechEngineState.RETRY_WAIT
        return delay
    }
}
