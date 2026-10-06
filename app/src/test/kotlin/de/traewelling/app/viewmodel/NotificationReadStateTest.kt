package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationReadStateTest {
    private fun notification(id: String, readAt: String? = null) = Notification(
        id, null, null, null, null, null, null, readAt, null, null
    )
    private val state = NotificationUiState(
        notifications = listOf(notification("a"), notification("b"), notification("already", "yesterday")), unreadCount = 5
    )

    @Test fun unknownIdsDoNotReduceTheUnreadBadge() {
        assertNull(markNotificationReadLocally(state, "unknown"))
    }

    @Test fun alreadyReadIdsDoNotReduceTheUnreadBadge() {
        assertNull(markNotificationReadLocally(state, "already"))
    }

    @Test fun aRepeatedTapConsumesTheUnreadCountOnlyOnce() {
        val changed = requireNotNull(markNotificationReadLocally(state, "a"))
        assertEquals(4, changed.unreadCount)
        assertNull(markNotificationReadLocally(changed, "a"))
        assertEquals(null, changed.notifications[1].readAt)
    }

    @Test fun failedReadRestoresItsCountWithoutUndoingAnotherPendingRead() {
        val first = requireNotNull(markNotificationReadLocally(state, "a"))
        val both = requireNotNull(markNotificationReadLocally(first, "b"))
        val restored = rollbackNotificationReadLocally(both, "a", state.unreadCount - first.unreadCount)
        assertEquals(4, restored.unreadCount)
        assertNull(restored.notifications[0].readAt)
        assertEquals("now", restored.notifications[1].readAt)
    }

    @Test fun aZeroBadgeNeverBecomesNegativeOrInflatedOnRollback() {
        val zero = state.copy(unreadCount = 0)
        val changed = requireNotNull(markNotificationReadLocally(zero, "a"))
        assertEquals(0, changed.unreadCount)
        assertEquals(0, rollbackNotificationReadLocally(changed, "a", zero.unreadCount - changed.unreadCount).unreadCount)
    }

    @Test fun failedMarkAllRestoresTheCountEvenWhenTheCountRequestAlsoFails() {
        val optimistic = state.copy(unreadCount = 0, notifications = state.notifications.map { it.copy(readAt = it.readAt ?: "now") })
        val restored = rollbackAllNotificationsReadLocally(optimistic, state)
        assertEquals(state.unreadCount, restored.unreadCount)
        assertEquals(state.notifications, restored.notifications)
    }
}
