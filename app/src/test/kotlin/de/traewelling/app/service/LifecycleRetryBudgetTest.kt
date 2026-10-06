package de.traewelling.app.service

import org.junit.Assert.*
import org.junit.Test

class LifecycleRetryBudgetTest {
    @Test fun registrationFailureHasOnlyThreeRetries() {
        val budget = LifecycleRetryBudget()
        assertTrue(budget.begin(1))
        assertEquals(2_000L, budget.failure(1))
        assertEquals(5_000L, budget.failure(1))
        assertEquals(15_000L, budget.failure(1))
        assertNull(budget.failure(1))
        assertNull(budget.failure(1))
    }

    @Test fun anIdempotentStartDoesNotTurnFailureIntoAnUnlimitedLoop() {
        val budget = LifecycleRetryBudget(listOf(10))
        budget.begin(7)
        assertEquals(10L, budget.failure(7))
        assertTrue(budget.begin(7))
        assertNull(budget.failure(7))
    }

    @Test fun aLateFailedRegistrationCannotConsumeTheNewTripsBudget() {
        val budget = LifecycleRetryBudget(listOf(10))
        budget.begin(1)
        budget.begin(2)
        assertNull(budget.failure(1))
        assertFalse(budget.success(1))
        assertFalse(budget.begin(1))
        assertEquals(10L, budget.failure(2))
    }

    @Test fun permissionLossTombstonesTheOwnerUntilNewAuthorization() {
        val budget = LifecycleRetryBudget(listOf(10))
        budget.begin(5)
        budget.stop()
        assertNull(budget.failure(5))
        assertFalse(budget.success(5))
        assertFalse(budget.begin(5))
        assertTrue(budget.begin(6))
        assertEquals(10L, budget.failure(6))
    }

    @Test fun aSuccessfulRegistrationRestoresItsRetryBudget() {
        val budget = LifecycleRetryBudget(listOf(10))
        budget.begin(1)
        budget.failure(1)
        assertTrue(budget.success(1))
        assertEquals(10L, budget.failure(1))
    }
}
