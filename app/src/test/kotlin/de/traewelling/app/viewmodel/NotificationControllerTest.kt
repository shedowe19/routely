package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Notification
import de.traewelling.app.data.model.NotificationListResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationControllerTest {
    @Test fun successfulReadSurvivesAnOlderListReplyAfterThePutCompletes() = runTest {
        val api = Gateway()
        val controller = NotificationController(backgroundScope, api, pollUnreadCount = false)
        controller.loadNotifications(); runCurrent()
        val oldGet = CompletableDeferred<Result<NotificationListResponse>>()
        api.list = { oldGet.await() }
        controller.refresh(); runCurrent()
        api.count = { Result.success(0) }
        controller.markAsRead("a"); runCurrent()
        oldGet.complete(Result.success(response("a"))); runCurrent()
        assertEquals(listOf("a"), api.readCalls)
        assertNotNull(controller.uiState.value.notifications.single().readAt)
        assertEquals(0, controller.uiState.value.unreadCount)
        controller.markAsRead("a"); runCurrent()
        assertEquals(1, api.readCalls.size)
    }

    @Test fun aFailedReadRollsBackItsOwnMarkWhileAnotherReadRemainsPending() = runTest {
        val api = Gateway().apply { list = { Result.success(response("a", "b")) }; count = { Result.success(5) } }
        val controller = NotificationController(backgroundScope, api, pollUnreadCount = false)
        controller.loadNotifications(); runCurrent()
        val readA = CompletableDeferred<Result<Unit>>()
        val readB = CompletableDeferred<Result<Unit>>()
        api.read = { if (it == "a") readA.await() else readB.await() }
        api.count = { Result.failure(IllegalStateException("count unavailable")) }
        controller.markAsRead("a"); controller.markAsRead("b"); runCurrent()
        readA.complete(Result.failure(IllegalStateException("PUT failed"))); runCurrent()
        assertNull(controller.uiState.value.notifications[0].readAt)
        assertNotNull(controller.uiState.value.notifications[1].readAt)
        assertEquals(4, controller.uiState.value.unreadCount)
        readB.complete(Result.success(Unit)); runCurrent()
        assertEquals(4, controller.uiState.value.unreadCount)
    }

    @Test fun markAllCoversOlderUnseenRowsButDoesNotMarkNewNotificationsRead() = runTest {
        val api = Gateway()
        val controller = NotificationController(backgroundScope, api, pollUnreadCount = false)
        controller.loadNotifications(); runCurrent()
        val oldGet = CompletableDeferred<Result<NotificationListResponse>>()
        api.list = { oldGet.await() }
        controller.refresh(); runCurrent()
        api.count = { Result.success(0) }
        controller.markAllAsRead(); runCurrent()
        oldGet.complete(Result.success(response("a", "previously-unloaded"))); runCurrent()
        assertTrue(controller.uiState.value.notifications.all { it.readAt != null })
        api.list = { Result.success(response("a", "previously-unloaded", "new-after-mark-all")) }
        api.count = { Result.success(1) }
        controller.refresh(); runCurrent()
        val rows = controller.uiState.value.notifications.associateBy { it.id }
        assertNotNull(rows["a"]?.readAt)
        assertNotNull(rows["previously-unloaded"]?.readAt)
        assertNull(rows["new-after-mark-all"]?.readAt)
    }

    @Test fun failedMarkAllRestoresRowsAndBadgeAfterAPendingListRefresh() = runTest {
        val api = Gateway().apply { count = { Result.success(5) } }
        val controller = NotificationController(backgroundScope, api, pollUnreadCount = false)
        controller.loadNotifications(); runCurrent()
        val put = CompletableDeferred<Result<Unit>>()
        api.all = { put.await() }
        api.count = { Result.failure(IllegalStateException("count unavailable")) }
        controller.markAllAsRead(); runCurrent()
        api.list = { Result.success(response("a", "b")) }
        controller.refresh(); runCurrent()
        assertTrue(controller.uiState.value.notifications.all { it.readAt != null })
        put.complete(Result.failure(IllegalStateException("PUT failed"))); runCurrent()
        assertEquals(5, controller.uiState.value.unreadCount)
        assertTrue(controller.uiState.value.notifications.all { it.readAt == null })
        assertNotNull(controller.uiState.value.error)
    }

    @Test fun countRequestsAreSerializedAndAnOlderCountCannotPublishAfterANewerRequest() = runTest {
        val oldCount = CompletableDeferred<Result<Int>>()
        val latestCount = CompletableDeferred<Result<Int>>()
        var calls = 0
        val api = Gateway().apply { count = { if (++calls == 1) oldCount.await() else latestCount.await() } }
        val controller = NotificationController(backgroundScope, api)
        runCurrent()
        controller.loadNotifications(); runCurrent()
        assertEquals(1, calls)
        oldCount.complete(Result.success(5)); runCurrent()
        assertEquals(2, calls)
        assertEquals(0, controller.uiState.value.unreadCount)
        assertEquals(1, api.maxActiveCounts)
        latestCount.complete(Result.success(6)); runCurrent()
        assertEquals(6, controller.uiState.value.unreadCount)
    }

    @Test fun readMutationInvalidatesAnAlreadyRunningCount() = runTest {
        val oldCount = CompletableDeferred<Result<Int>>()
        val put = CompletableDeferred<Result<Unit>>()
        var calls = 0
        val api = Gateway().apply {
            count = { if (++calls == 1) oldCount.await() else Result.success(0) }
            read = { put.await() }
        }
        val controller = NotificationController(backgroundScope, api)
        runCurrent()
        controller.loadNotifications(); runCurrent()
        controller.markAsRead("a"); runCurrent()
        oldCount.complete(Result.success(5)); runCurrent()
        assertEquals(0, controller.uiState.value.unreadCount)
        put.complete(Result.success(Unit)); runCurrent()
        assertEquals(0, controller.uiState.value.unreadCount)
    }

    @Test fun aCancelledOlderRefreshCannotReplaceTheNewerList() = runTest {
        val oldGet = CompletableDeferred<Result<NotificationListResponse>>()
        val api = Gateway().apply { list = { withContext(NonCancellable) { oldGet.await() } } }
        val controller = NotificationController(backgroundScope, api, pollUnreadCount = false)
        controller.loadNotifications(); runCurrent()
        api.list = { Result.success(response("new")) }
        controller.refresh(); runCurrent()
        oldGet.complete(Result.success(response("old"))); runCurrent()
        assertEquals(listOf("new"), controller.uiState.value.notifications.map { it.id })
    }

    private class Gateway : NotificationGateway {
        var list: suspend (Int) -> Result<NotificationListResponse> = { Result.success(response("a")) }
        var count: suspend () -> Result<Int> = { Result.success(1) }
        var read: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        var all: suspend () -> Result<Unit> = { Result.success(Unit) }
        val readCalls = mutableListOf<String>()
        private var activeCounts = 0
        var maxActiveCounts = 0
        override suspend fun getNotifications(page: Int) = list(page)
        override suspend fun getUnreadNotificationCount(): Result<Int> {
            ++activeCounts
            maxActiveCounts = maxOf(maxActiveCounts, activeCounts)
            return try { count() } finally { --activeCounts }
        }
        override suspend fun markNotificationRead(id: String): Result<Unit> {
            readCalls.add(id)
            return read(id)
        }
        override suspend fun markAllNotificationsRead() = all()
    }

    companion object {
        private fun response(vararg ids: String) = NotificationListResponse(ids.map { id ->
            Notification(id, null, null, null, null, null, null, null, null, null)
        }, null, null)
    }
}
