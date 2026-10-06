package de.traewelling.app.service

import org.junit.Assert.*
import org.junit.Test

class SpeechInitializationLifecycleTest {
    @Test fun lateInitializationCannotEnableAReplacedEngine() {
        val lifecycle = SpeechInitializationLifecycle()
        lifecycle.restart(true)
        val oldEngine = lifecycle.beginAttempt()!!
        lifecycle.restart(true)
        val selectedEngine = lifecycle.beginAttempt()!!
        assertFalse(lifecycle.ready(oldEngine))
        assertNull(lifecycle.failed(oldEngine))
        assertTrue(lifecycle.ready(selectedEngine))
        assertEquals(SpeechEngineState.READY, lifecycle.state)
    }

    @Test fun disablingSpeechInvalidatesReadyAndInitializingCallbacks() {
        val lifecycle = SpeechInitializationLifecycle()
        lifecycle.restart(true)
        val attempt = lifecycle.beginAttempt()!!
        lifecycle.ready(attempt)
        lifecycle.restart(false)
        assertFalse(lifecycle.owns(attempt))
        assertFalse(lifecycle.ready(attempt))
        assertNull(lifecycle.beginAttempt())
        assertEquals(SpeechEngineState.DISABLED, lifecycle.state)
    }

    @Test fun initializationFailureAndTimeoutShareOneBoundedBudget() {
        val lifecycle = SpeechInitializationLifecycle()
        lifecycle.restart(true)
        for (delay in listOf(2_000L, 5_000L, 15_000L)) {
            val attempt = lifecycle.beginAttempt()!!
            assertEquals(delay, lifecycle.failed(attempt))
            assertNull(lifecycle.failed(attempt))
            assertFalse(lifecycle.ready(attempt))
        }
        assertNull(lifecycle.failed(lifecycle.beginAttempt()!!))
        assertEquals(SpeechEngineState.FAILED, lifecycle.state)
        assertNull(lifecycle.beginAttempt())
    }

    @Test fun correctedConfigurationCanRecoverAnExhaustedEngine() {
        val lifecycle = SpeechInitializationLifecycle()
        lifecycle.restart(true)
        repeat(4) { lifecycle.failed(lifecycle.beginAttempt()!!) }
        lifecycle.restart(true)
        val corrected = lifecycle.beginAttempt()!!
        assertTrue(lifecycle.ready(corrected))
        assertTrue(lifecycle.owns(corrected))
    }

    @Test fun lateSpeechCallbackCannotOwnTheNewTripsEngine() {
        val lifecycle = SpeechInitializationLifecycle()
        lifecycle.restart(true)
        val priorTrip = lifecycle.beginAttempt()!!
        lifecycle.ready(priorTrip)
        lifecycle.restart(false)
        lifecycle.restart(true)
        val currentTrip = lifecycle.beginAttempt()!!
        lifecycle.ready(currentTrip)
        assertFalse(lifecycle.owns(priorTrip))
        assertTrue(lifecycle.owns(currentTrip))
    }
}
