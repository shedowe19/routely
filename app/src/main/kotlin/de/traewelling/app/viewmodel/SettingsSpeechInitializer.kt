package de.traewelling.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class SettingsTtsEngineOption(val name: String, val label: String)

internal enum class SettingsSpeechState { DISABLED, INITIALIZING, READY, FAILED }
internal enum class SettingsSpeechFailure { INITIALIZATION, CONSTRUCTION, TIMEOUT }

/** The settings preview owns its engine independently of the tracking service. */
internal class SettingsSpeechInitializer<Engine>(
    private val scope: CoroutineScope,
    private val discoverEngines: () -> List<SettingsTtsEngineOption>,
    private val createEngine: (String?, (Boolean) -> Unit) -> Engine,
    private val shutdown: (Engine) -> Unit,
    private val onReady: (Engine) -> Unit,
    private val onChanged: (SettingsSpeechState, SettingsSpeechFailure?, List<SettingsTtsEngineOption>) -> Unit,
    private val timeoutMillis: Long = 10_000L
) {
    var state = SettingsSpeechState.DISABLED
        private set
    var instance: Engine? = null
        private set
    private var generation = 0L
    private var timeoutJob: Job? = null
    private var engines: List<SettingsTtsEngineOption> = emptyList()

    fun restart(engine: String?) {
        val token = ++generation
        releaseInstance()
        state = SettingsSpeechState.INITIALIZING
        // Discovery is independent of engine binding or synthesis readiness.
        // A transient query failure must not erase an already known alternative.
        engines = runCatching { discoverEngines() }.getOrElse { engines }
        onChanged(state, null, engines)
        try {
            instance = createEngine(engine) { success ->
                scope.launch {
                    // Android may invoke onInit before its constructor returns.
                    // Defer even a synchronous callback until instance is assigned.
                    yield()
                    if (token != generation || state != SettingsSpeechState.INITIALIZING) return@launch
                    if (!success) {
                        fail(token, SettingsSpeechFailure.INITIALIZATION)
                        return@launch
                    }
                    val ready = instance ?: return@launch
                    timeoutJob?.cancel()
                    timeoutJob = null
                    try {
                        onReady(ready)
                        state = SettingsSpeechState.READY
                        onChanged(state, null, engines)
                    } catch (_: Exception) {
                        fail(token, SettingsSpeechFailure.INITIALIZATION)
                    }
                }
            }
        } catch (_: Exception) {
            fail(token, SettingsSpeechFailure.CONSTRUCTION)
            return
        }
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            fail(token, SettingsSpeechFailure.TIMEOUT)
        }
    }

    fun disable() {
        ++generation
        releaseInstance()
        state = SettingsSpeechState.DISABLED
        onChanged(state, null, engines)
    }

    private fun fail(token: Long, failure: SettingsSpeechFailure) {
        if (token != generation || state != SettingsSpeechState.INITIALIZING) return
        releaseInstance()
        state = SettingsSpeechState.FAILED
        onChanged(state, failure, engines)
    }

    private fun releaseInstance() {
        timeoutJob?.cancel()
        timeoutJob = null
        instance?.let { runCatching { shutdown(it) } }
        instance = null
    }
}
