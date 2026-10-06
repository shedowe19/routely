package de.traewelling.app.util

import androidx.lifecycle.ViewModel
import org.junit.Assert.*
import org.junit.Test

class SessionViewModelStoreTest {
    private class Feature : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun sameSessionRetainsFeatureStateAcrossRecreation() {
        val holder = SessionViewModelStore()
        val store = holder.forSession("a")
        assertSame(store, holder.forSession("a"))
    }

    @Test fun differentSessionClearsPreviousViewModels() {
        val holder = SessionViewModelStore()
        val old = holder.forSession("a")
        val feature = Feature()
        old.put("feed", feature)
        assertNotSame(old, holder.forSession("b"))
        assertTrue(feature.cleared)
        assertNull(holder.forSession("b").get("feed"))
    }

    @Test fun logoutClearsEvenBeforeAnotherLogin() {
        val holder = SessionViewModelStore()
        val old = holder.forSession("a")
        val feature = Feature()
        old.put("feed", feature)
        holder.clearSession()
        assertTrue(feature.cleared)
        assertNotSame(old, holder.forSession("a"))
    }
}
