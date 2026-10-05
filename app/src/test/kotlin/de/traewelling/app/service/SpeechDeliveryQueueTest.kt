package de.traewelling.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDeliveryQueueTest {
    @Test
    fun changeAndStationEntriesWithTheSameKeyCannotConsumeEachOther() {
        val queue = SpeechDeliveryQueue()
        val stop = queue.begin(42, 1, "same")
        val change = queue.begin(42, 1, "same", SpeechDeliveryKind.TRIP_CHANGE)
        assertNotEquals(stop.utteranceId, change.utteranceId)
        assertEquals(SpeechDeliveryKind.TRIP_CHANGE, queue.finish(change.utteranceId)?.kind)
        assertFalse(queue.isEmpty)
        assertEquals(stop, queue.find(42, 1, "same"))
    }
    @Test
    fun firstCompletionKeepsTheFollowingAnnouncementActive() {
        val queue = SpeechDeliveryQueue()
        val first = queue.begin(42, 1, "maubis")
        val next = queue.begin(42, 1, "rathaus")

        assertEquals(first, queue.finish(first.utteranceId))
        assertFalse(queue.isEmpty)
        assertTrue(queue.contains(next.utteranceId))
        queue.finish(next.utteranceId)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun aLateCallbackCannotRemoveARetryOfTheSameVisit() {
        val queue = SpeechDeliveryQueue()
        val interrupted = queue.begin(42, 1, "rathaus")
        queue.finish(interrupted.utteranceId)
        val retry = queue.begin(42, 1, "rathaus")

        assertNotEquals(interrupted.utteranceId, retry.utteranceId)
        assertNull(queue.finish(interrupted.utteranceId))
        assertEquals(retry, queue.find(42, 1, "rathaus"))
        assertFalse(queue.isEmpty)
    }

    @Test
    fun aPreviousTripCallbackCannotConsumeTheNewTripsAnnouncement() {
        val queue = SpeechDeliveryQueue()
        val previousTrip = queue.begin(42, 1, "rathaus")
        queue.clear()
        val newTrip = queue.begin(43, 2, "rathaus")

        assertNull(queue.finish(previousTrip.utteranceId))
        assertEquals(newTrip, queue.find(43, 2, "rathaus"))
        assertNull(queue.find(42, 1, "rathaus"))
    }

    @Test
    fun duplicateRequestsShareOnlyTheCurrentAttempt() {
        val queue = SpeechDeliveryQueue()
        val original = queue.begin(42, 1, "rathaus")
        assertEquals(original, queue.begin(42, 1, "rathaus"))
        val laterGeneration = queue.begin(42, 2, "rathaus")

        assertNotEquals(original.utteranceId, laterGeneration.utteranceId)
        queue.finish(original.utteranceId)
        assertNull(queue.finish(original.utteranceId))
        assertTrue(queue.contains(laterGeneration.utteranceId))
    }
}
