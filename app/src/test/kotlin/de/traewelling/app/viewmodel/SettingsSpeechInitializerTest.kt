package de.traewelling.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSpeechInitializerTest {
    @Test
    fun failedDefaultEngineStillOffersInstalledAlternativeAndRetryCanStartIt() = runTest {
        val harness = Harness(this)
        harness.initializer.restart(null)
        assertEquals(harness.catalog, harness.latest!!.engines)
        harness.callbacks[0](false)
        runCurrent()
        assertEquals(SettingsSpeechState.FAILED, harness.latest!!.state)
        assertEquals(SettingsSpeechFailure.INITIALIZATION, harness.latest!!.failure)
        assertEquals(harness.catalog, harness.latest!!.engines)
        assertEquals(listOf(1), harness.closed)

        harness.initializer.restart("alternative")
        assertEquals("alternative", harness.requests.last())
        harness.callbacks[1](true)
        runCurrent()
        assertEquals(SettingsSpeechState.READY, harness.latest!!.state)
        assertNull(harness.latest!!.failure)
        assertEquals(listOf(2), harness.ready)
        harness.initializer.disable()
    }

    @Test
    fun replacementEngineIgnoresLateResultOfOldEngine() = runTest {
        val harness = Harness(this)
        harness.initializer.restart(null)
        harness.initializer.restart("alternative")
        harness.callbacks[0](true)
        runCurrent()
        assertEquals(SettingsSpeechState.INITIALIZING, harness.latest!!.state)
        assertTrue(harness.ready.isEmpty())
        assertEquals(listOf(1), harness.closed)
        harness.callbacks[1](true)
        runCurrent()
        assertEquals(listOf(2), harness.ready)
        harness.callbacks[0](false)
        runCurrent()
        assertEquals(SettingsSpeechState.READY, harness.latest!!.state)
        harness.initializer.disable()
    }

    @Test
    fun unansweredInitializationTimesOutAndLateSuccessCannotReviveIt() = runTest {
        val harness = Harness(this)
        harness.initializer.restart(null)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(SettingsSpeechState.INITIALIZING, harness.latest!!.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SettingsSpeechFailure.TIMEOUT, harness.latest!!.failure)
        assertEquals(listOf(1), harness.closed)
        assertNull(harness.initializer.instance)
        assertEquals(harness.catalog, harness.latest!!.engines)
        harness.callbacks[0](true)
        runCurrent()
        assertTrue(harness.ready.isEmpty())
        assertEquals(SettingsSpeechState.FAILED, harness.latest!!.state)
    }

    @Test
    fun constructorFailureHasActionableStateWithoutLosingEngineCatalog() = runTest {
        val harness = Harness(this, constructorFails = true)
        harness.initializer.restart(null)
        assertEquals(SettingsSpeechState.FAILED, harness.latest!!.state)
        assertEquals(SettingsSpeechFailure.CONSTRUCTION, harness.latest!!.failure)
        assertEquals(harness.catalog, harness.latest!!.engines)
        assertNull(harness.initializer.instance)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SettingsSpeechFailure.CONSTRUCTION, harness.latest!!.failure)
    }

    @Test
    fun synchronousSuccessWaitsUntilConstructedEngineIsAvailable() = runTest {
        val harness = Harness(this, synchronousSuccess = true)
        harness.initializer.restart(null)
        runCurrent()
        assertEquals(SettingsSpeechState.READY, harness.latest!!.state)
        assertEquals(listOf(1), harness.ready)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SettingsSpeechState.READY, harness.latest!!.state)
        assertTrue(harness.closed.isEmpty())
        harness.initializer.disable()
    }

    @Test
    fun disablingCancelsDeadlineAndDiscardsLateCallbacks() = runTest {
        val harness = Harness(this)
        harness.initializer.restart(null)
        harness.initializer.disable()
        harness.callbacks[0](true)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SettingsSpeechState.DISABLED, harness.latest!!.state)
        assertNull(harness.latest!!.failure)
        assertNull(harness.initializer.instance)
        assertEquals(listOf(1), harness.closed)
        assertTrue(harness.ready.isEmpty())
        assertEquals(harness.catalog, harness.latest!!.engines)
    }

    @Test
    fun temporaryDiscoveryFailurePreservesPreviouslyAvailableAlternative() = runTest {
        val harness = Harness(this)
        harness.initializer.restart(null)
        harness.callbacks[0](false)
        runCurrent()
        harness.discoveryFails = true
        harness.initializer.restart("alternative")
        assertEquals(harness.catalog, harness.latest!!.engines)
        assertEquals(SettingsSpeechState.INITIALIZING, harness.latest!!.state)
        harness.callbacks[1](true)
        runCurrent()
        assertEquals(SettingsSpeechState.READY, harness.latest!!.state)
        assertFalse(harness.latest!!.engines.isEmpty())
        harness.initializer.disable()
    }

    private data class Snapshot(
        val state: SettingsSpeechState,
        val failure: SettingsSpeechFailure?,
        val engines: List<SettingsTtsEngineOption>
    )

    private class Harness(
        scope: CoroutineScope,
        constructorFails: Boolean = false,
        synchronousSuccess: Boolean = false
    ) {
        val catalog = listOf(SettingsTtsEngineOption("alternative", "Alternative Sprachengine"))
        val requests = mutableListOf<String?>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val ready = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        var latest: Snapshot? = null
        var discoveryFails = false
        val initializer = SettingsSpeechInitializer(
            scope = scope,
            discoverEngines = {
                if (discoveryFails) error("Package manager temporarily unavailable")
                catalog
            },
            createEngine = { engine, callback ->
                requests += engine
                if (constructorFails) error("Engine construction failed")
                callbacks += callback
                if (synchronousSuccess) callback(true)
                callbacks.size
            },
            shutdown = { engine: Int -> closed += engine },
            onReady = { ready += it },
            onChanged = { state, failure, engines -> latest = Snapshot(state, failure, engines) }
        )
    }
}
