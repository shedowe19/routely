package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Notification
import de.traewelling.app.data.model.NotificationListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

internal interface NotificationGateway {
    suspend fun getNotifications(page: Int): Result<NotificationListResponse>
    suspend fun getUnreadNotificationCount(): Result<Int>
    suspend fun markNotificationRead(id: String): Result<Unit>
    suspend fun markAllNotificationsRead(): Result<Unit>
}

data class NotificationUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val notifications: List<Notification> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val unreadCount: Int = 0
)

/** Account-scoped state and request ordering, independent of the Android lifecycle adapter. */
internal class NotificationController(
    private val scope: CoroutineScope, private val gateway: NotificationGateway,
    pollUnreadCount: Boolean = true
) {
    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var countRevision = 0L
    private val pendingReads = mutableSetOf<String>()
    private var markingAll = false
    private val pendingAllReads = mutableSetOf<String>()
    private val confirmedReads = mutableMapOf<String, Long>()
    private var countRequest = 0L
    private val countMutex = Mutex()

    init {
        // Poll unread count every 60 seconds
        if (pollUnreadCount) scope.launch {
            while (isActive) {
                refreshUnreadCount()
                delay(60_000)
            }
        }
    }

    fun loadNotifications(refresh: Boolean = false) {
        if (!refresh && (_uiState.value.isLoading || _uiState.value.isRefreshing)) return
        if (refresh && _uiState.value.isRefreshing) return
        val page = if (refresh) 1 else _uiState.value.currentPage
        val request = ++generation
        val readRevision = countRevision
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null) }

        loadJob = scope.launch {
            val result = gateway.getNotifications(page)
            coroutineContext.ensureActive()
            if (request != generation) return@launch
            result
                .onSuccess { response ->
                    val fetched = response.data.orEmpty().filter { !it.id.isNullOrBlank() }.map { notification ->
                        val id = requireNotNull(notification.id)
                        val confirmed = confirmedReads[id]
                        // A GET that already started before a successful PUT may still report unread.
                        if (notification.readAt != null && confirmed != null && readRevision > confirmed) {
                            confirmedReads.remove(id)
                        }
                        if (notification.readAt == null && (id in pendingAllReads || id in pendingReads ||
                            id in confirmedReads)) {
                            notification.copy(readAt = "now")
                        } else notification
                    }
                    val newNotifications = if (refresh || page == 1) {
                        fetched
                    } else {
                        _uiState.value.notifications + fetched
                    }
                    val hasMore = response.links?.next != null
                    val meta = response.meta
                    _uiState.update {
                        it.copy(
                            isLoading       = false,
                            isRefreshing    = false,
                            notifications   = newNotifications.distinctBy { notification -> notification.id },
                            unreadCount     = if (markingAll) maxOf(it.unreadCount,
                                newNotifications.distinctBy { notification -> notification.id }.count { notification -> notification.readAt == null }
                            ) else it.unreadCount,
                            hasMore         = hasMore,
                            currentPage     = (meta?.currentPage ?: page) + 1,
                            error           = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, error = e.message)
                    }
                }

            // Also refresh unread count
            refreshUnreadCount()
        }
    }

    fun refresh() = loadNotifications(refresh = true)

    fun loadMore() {
        if (!_uiState.value.isLoading && !_uiState.value.isRefreshing && _uiState.value.hasMore) {
            loadNotifications(refresh = false)
        }
    }

    fun markAsRead(notificationId: String) {
        if (notificationId.isBlank() || markingAll || notificationId in pendingReads) return
        val previous = _uiState.value
        val changed = markNotificationReadLocally(previous, notificationId) ?: return
        val unreadDelta = previous.unreadCount - changed.unreadCount
        pendingReads.add(notificationId)
        ++countRevision
        _uiState.value = changed
        scope.launch {
            try {
                val result = gateway.markNotificationRead(notificationId)
                coroutineContext.ensureActive()
                result.onSuccess { confirmedReads[notificationId] = countRevision }
                result.onFailure { e ->
                    _uiState.update { state -> rollbackNotificationReadLocally(state, notificationId, unreadDelta).copy(
                        error = "Meldung konnte nicht als gelesen markiert werden: ${e.message}"
                    ) }
                }
            } finally {
                pendingReads.remove(notificationId)
                ++countRevision
            }
            refreshUnreadCount()
        }
    }

    fun markAllAsRead() {
        if (markingAll || pendingReads.isNotEmpty() || _uiState.value.unreadCount == 0) return
        val previous = _uiState.value
        // A later list can contain notifications created after the server commits this PUT.
        // Its request start is not evidence of its server snapshot, so only known IDs qualify.
        pendingAllReads.addAll(previous.notifications.mapNotNull { it.id })
        markingAll = true
        ++countRevision
        _uiState.update { state -> state.copy(
            notifications = state.notifications.map { it.copy(readAt = it.readAt ?: "now") }, unreadCount = 0
        ) }
        scope.launch {
            var succeeded = false
            try {
                val result = gateway.markAllNotificationsRead()
                coroutineContext.ensureActive()
                result.onSuccess {
                    succeeded = true
                    pendingAllReads.forEach { id -> confirmedReads[id] = countRevision }
                }
                result.onFailure { e ->
                    _uiState.update { state -> rollbackAllNotificationsReadLocally(state, previous).copy(
                        error = "Meldungen konnten nicht als gelesen markiert werden: ${e.message}"
                    ) }
                }
            } finally {
                markingAll = false
                pendingAllReads.clear()
                ++countRevision
            }
            if (succeeded) {
                // Previously unseen rows require a post-PUT server snapshot, not a blanket
                // read overlay. Starting this reload also cancels/fences every older list.
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                loadNotifications(refresh = true)
            } else refreshUnreadCount()
        }
    }

    private suspend fun refreshUnreadCount() {
        val request = ++countRequest
        val revision = countRevision
        countMutex.withLock {
            if (request != countRequest || revision != countRevision || markingAll || pendingReads.isNotEmpty()) return@withLock
            gateway.getUnreadNotificationCount().onSuccess { count ->
                coroutineContext.ensureActive()
                if (request == countRequest && revision == countRevision && !markingAll && pendingReads.isEmpty()) {
                    _uiState.update { it.copy(unreadCount = count.coerceAtLeast(0)) }
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

/** Unknown or already-read IDs must not consume an unread badge count. */
internal fun markNotificationReadLocally(state: NotificationUiState, id: String): NotificationUiState? {
    if (state.notifications.none { it.id == id && it.readAt == null }) return null
    return state.copy(
        notifications = state.notifications.map { if (it.id == id && it.readAt == null) it.copy(readAt = "now") else it },
        unreadCount = (state.unreadCount - 1).coerceAtLeast(0)
    )
}

internal fun rollbackNotificationReadLocally(state: NotificationUiState, id: String, unreadDelta: Int): NotificationUiState =
    state.copy(
        notifications = state.notifications.map { if (it.id == id && it.readAt == "now") it.copy(readAt = null) else it },
        unreadCount = state.unreadCount + unreadDelta.coerceAtLeast(0)
    )

internal fun rollbackAllNotificationsReadLocally(state: NotificationUiState, previous: NotificationUiState): NotificationUiState {
    val readTimes = previous.notifications.associate { it.id to it.readAt }
    val restored = state.notifications.map { if (it.readAt == "now") it.copy(readAt = readTimes[it.id]) else it }
    return state.copy(
        notifications = restored,
        // Preserve the old total, but never underreport new unread rows received during the PUT.
        unreadCount = maxOf(previous.unreadCount, restored.count { it.readAt == null })
    )
}
