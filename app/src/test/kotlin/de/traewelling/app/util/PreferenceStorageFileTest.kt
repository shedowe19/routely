package de.traewelling.app.util

import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreferenceStorageFileTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun realDataStorePreservesCorruptedBytesUntilTheCurrentResetIsConfirmed() = runTest {
        // Plain Android unit-test stubs report SDK 0. Routely supports SDK 26+, whose actual
        // DataStore implementation uses NIO replacement instead of legacy File.renameTo.
        // Exercise that supported branch against real bytes, including on Windows hosts.
        val sdk = SupportedSdkFixture()
        val firstStoreJob = SupervisorJob()
        val secondStoreJob = SupervisorJob()
        try {
            val file = File(files.root, "recovery.preferences_pb")
            val originalBytes = byteArrayOf(0x0a, 0x7f, 0x01, 0x02)
            file.writeBytes(originalBytes)
            val reset = PreferenceCorruptionReset()
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = reset.handler,
                scope = CoroutineScope(backgroundScope.coroutineContext + firstStoreJob),
                produceFile = { file }
            )
            val storage = PreferenceStorage(store, backgroundScope, reset, startObserving = false)

            assertTrue(runCatching { storage.readPreferences() }.exceptionOrNull() is PreferenceStorageException)
            val failureId = storage.state.value.failureId
            assertTrue(storage.state.value.canReset)
            assertArrayEquals(originalBytes, file.readBytes())
            assertTrue(storage.resetCorruptedStorage(failureId + 1).isFailure)
            assertArrayEquals(originalBytes, file.readBytes())

            val recovered = storage.resetCorruptedStorage(failureId)
            recovered.getOrThrow()
            assertTrue(recovered.isSuccess)
            assertFalse(originalBytes.contentEquals(file.readBytes()))
            val confirmed = store.data.first()
            assertNull(confirmed[AuthSessionPreferences.accessToken])
            assertNull(confirmed[AuthSessionPreferences.activeStatusId])
            assertNull(confirmed[AuthSessionPreferences.trackingState])
            assertEquals(false, confirmed[AuthSessionPreferences.recognitionEnabled])
            assertNotEquals("legacy", AuthSessionPreferences.read(confirmed).revision)
            assertTrue(storage.state.value.isReady)
            val revision = AuthSessionPreferences.read(confirmed).revision

            firstStoreJob.cancelAndJoin()
            val reopened = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(backgroundScope.coroutineContext + secondStoreJob),
                produceFile = { file }
            ).data.first()
            assertNull(reopened[AuthSessionPreferences.accessToken])
            assertNull(reopened[AuthSessionPreferences.activeStatusId])
            assertNull(reopened[AuthSessionPreferences.trackingState])
            assertEquals(false, reopened[AuthSessionPreferences.recognitionEnabled])
            assertEquals(revision, AuthSessionPreferences.read(reopened).revision)
        } finally {
            withContext(NonCancellable) {
                try {
                    firstStoreJob.cancelAndJoin()
                    secondStoreJob.cancelAndJoin()
                } finally {
                    sdk.close()
                }
            }
        }
    }
}

/** JVM-only SDK fixture; no production platform state or files are changed. */
private class SupportedSdkFixture : AutoCloseable {
    private val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
    private val original = field.getInt(null)
    // Reflective access keeps this JVM test harness out of the Android boot classpath.
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null)
    }
    private val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    private val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
    private val putInt = unsafeClass.getMethod("putIntVolatile", Any::class.java, java.lang.Long.TYPE, java.lang.Integer.TYPE)
    init { putInt.invoke(unsafe, base, offset, maxOf(26, original)) }
    override fun close() { putInt.invoke(unsafe, base, offset, original) }
}
