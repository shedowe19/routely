package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Notification
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class NotificationUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val notifications: List<Notification> = emptyList(),
    val error: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val unreadCount: Int = 0
)

class NotificationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
    private var countRevision = 0L
    private val pendingReads = mutableSetOf<String>()
    private var markingAll = false

    init {
        // Poll unread count every 60 seconds
        viewModelScope.launch {
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
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null) }

        loadJob = viewModelScope.launch {
            val result = repo.getNotifications(page)
            coroutineContext.ensureActive()
            if (request != generation) return@launch
            result
                .onSuccess { response ->
                    val fetched = response.data.orEmpty().filter { !it.id.isNullOrBlank() }.map {
                        if ((markingAll || it.id in pendingReads) && it.readAt == null) it.copy(readAt = "now") else it
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
        viewModelScope.launch {
            try {
                val result = repo.markNotificationRead(notificationId)
                coroutineContext.ensureActive()
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
        markingAll = true
        ++countRevision
        val previous = _uiState.value
        _uiState.update { state -> state.copy(
            notifications = state.notifications.map { it.copy(readAt = it.readAt ?: "now") }, unreadCount = 0
        ) }
        viewModelScope.launch {
            try {
                val result = repo.markAllNotificationsRead()
                coroutineContext.ensureActive()
                result.onFailure { e ->
                    _uiState.update { state -> rollbackAllNotificationsReadLocally(state, previous).copy(
                        error = "Meldungen konnten nicht als gelesen markiert werden: ${e.message}"
                    ) }
                }
            } finally {
                markingAll = false
                ++countRevision
            }
            refreshUnreadCount()
        }
    }

    private suspend fun refreshUnreadCount() {
        if (markingAll || pendingReads.isNotEmpty()) return
        val revision = countRevision
        repo.getUnreadNotificationCount().onSuccess { count ->
            coroutineContext.ensureActive()
            if (revision == countRevision && !markingAll && pendingReads.isEmpty()) {
                _uiState.update { it.copy(unreadCount = count.coerceAtLeast(0)) }
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
    return state.copy(
        notifications = state.notifications.map { if (it.readAt == "now") it.copy(readAt = readTimes[it.id]) else it },
        unreadCount = previous.unreadCount
    )
}
