package de.traewelling.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartRequestsTest {
    @Test fun staleNewerStartCannotSupersedeTheNewAccountsPendingStart() {
        val requests = SessionStartRequests()
        val newAccount = requests.begin("b")
        val stale = requests.begin("a")
        assertFalse(requests.accept(stale, "b"))
        requests.finish(stale)
        assertTrue(requests.hasPending("b"))
        assertTrue(requests.accept(newAccount, "b"))
    }

    @Test fun newerValidatedStartPreventsAnOlderInitializationFromRestartingIt() {
        val requests = SessionStartRequests()
        val older = requests.begin("b")
        val newer = requests.begin("b")
        assertTrue(requests.accept(newer, "b"))
        requests.finish(newer)
        assertFalse(requests.accept(older, "b"))
    }

    @Test fun staleOnlyOrClearedRequestsDoNotKeepAnIdleServiceAlive() {
        val requests = SessionStartRequests()
        val stale = requests.begin("a")
        assertFalse(requests.hasPending("b"))
        requests.finish(stale)
        assertFalse(requests.hasPending("a"))
        val fresh = requests.begin("b")
        requests.clear()
        assertFalse(requests.hasPending("b"))
        assertFalse(requests.accept(fresh, "b"))
    }

    @Test fun anotherPendingTripCannotKeepTheReplacedTripStartupAlive() {
        val requests = SessionStartRequests()
        requests.begin("b", 42)
        assertTrue(requests.hasPending("b", 42))
        assertFalse(requests.hasPending("b", 43))
        assertFalse(requests.hasPending("a", 42))
    }
}
