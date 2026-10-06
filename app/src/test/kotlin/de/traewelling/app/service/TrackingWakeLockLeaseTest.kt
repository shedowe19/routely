package de.traewelling.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingWakeLockLeaseTest {
    @Test
    fun idleLeaseDoesNotAcquireOrRelease() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)

        assertFalse(lease.renew(1L))
        lease.stop()

        assertTrue(handle.acquisitions.isEmpty())
        assertEquals(0, handle.releaseCount)
    }

    @Test
    fun activeLeaseIsAlwaysAcquiredWithBoundedTimeout() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)

        assertTrue(lease.start(1L))
        assertTrue(lease.renew(1L))

        assertTrue(handle.held)
        assertEquals(listOf(120_000L, 120_000L), handle.acquisitions)
        assertTrue(TrackingWakeLockLease.RENEW_INTERVAL_MILLIS < TrackingWakeLockLease.TIMEOUT_MILLIS)
    }

    @Test
    fun stoppedLeaseCannotBeReacquiredByDelayedRenewal() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)
        lease.start(1L)

        lease.stop()
        lease.stop()

        assertFalse(lease.renew(1L))
        assertFalse(handle.held)
        assertEquals(1, handle.acquisitions.size)
        assertEquals(1, handle.releaseCount)
    }

    @Test
    fun previousTripGenerationCannotRenewReplacementTripLease() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)
        lease.start(1L)
        lease.stop()
        lease.start(2L)

        assertFalse(lease.renew(1L))
        assertTrue(lease.renew(2L))
        assertEquals(3, handle.acquisitions.size)
        assertTrue(handle.held)
    }

    @Test
    fun expiredPlatformLeaseCanBeReacquiredWhileTrackingRemainsActive() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)
        lease.start(1L)
        handle.held = false

        assertTrue(lease.renew(1L))

        assertTrue(handle.held)
        assertEquals(2, handle.acquisitions.size)
    }

    @Test
    fun failedAcquisitionCanRetryAndStopStillClearsOwnership() {
        val handle = FakeHandle().apply { failAcquire = true }
        val lease = TrackingWakeLockLease(handle)

        assertFalse(lease.start(1L))
        handle.failAcquire = false
        assertTrue(lease.renew(1L))
        lease.stop()

        assertFalse(lease.renew(1L))
        assertFalse(handle.held)
        assertEquals(1, handle.releaseCount)
    }

    @Test
    fun releaseFailureDoesNotAllowFurtherRenewals() {
        val handle = FakeHandle().apply { failRelease = true }
        val lease = TrackingWakeLockLease(handle)
        lease.start(1L)

        lease.stop()

        assertFalse(lease.renew(1L))
        assertEquals(1, handle.acquisitions.size)
        assertEquals(1, handle.releaseCount)
    }

    @Test
    fun leaseCanStartAgainAfterCompletionOrStop() {
        val handle = FakeHandle()
        val lease = TrackingWakeLockLease(handle)
        lease.start(1L)
        lease.stop()

        assertTrue(lease.start(3L))
        lease.stop()

        assertFalse(handle.held)
        assertEquals(2, handle.acquisitions.size)
        assertEquals(2, handle.releaseCount)
    }

    private class FakeHandle : TrackingWakeLockHandle {
        val acquisitions = mutableListOf<Long>()
        var releaseCount = 0
        var held = false
        var failAcquire = false
        var failRelease = false

        override fun acquire(timeoutMillis: Long) {
            acquisitions += timeoutMillis
            if (failAcquire) throw SecurityException("Test acquisition rejected")
            held = true
        }

        override fun release() {
            releaseCount++
            if (failRelease) throw IllegalStateException("Test release failed")
            held = false
        }
    }
}
