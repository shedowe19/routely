package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Notification
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

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
        val page = if (refresh) 1 else _uiState.value.currentPage

        viewModelScope.launch {
            _uiState.update {
                if (refresh) it.copy(isRefreshing = true, error = null)
                else         it.copy(isLoading = true, error = null)
            }

            repo.getNotifications(page)
                .onSuccess { response ->
                    val fetched = response.data ?: emptyList()
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
                            notifications   = newNotifications,
                            hasMore         = hasMore,
                            currentPage     = (meta?.currentPage ?: 1) + 1,
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
        if (!_uiState.value.isLoading && _uiState.value.hasMore) {
            loadNotifications(refresh = false)
        }
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            // Optimistic update
            _uiState.update { state ->
                state.copy(
                    notifications = state.notifications.map { n ->
                        if (n.id == notificationId && n.readAt == null) {
                            n.copy(readAt = "now")
                        } else n
                    },
                    unreadCount = (state.unreadCount - 1).coerceAtLeast(0)
                )
            }
            repo.markNotificationRead(notificationId).onFailure {
                // Revert on failure
                refresh()
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            // Optimistic update
            _uiState.update { state ->
                state.copy(
                    notifications = state.notifications.map { it.copy(readAt = it.readAt ?: "now") },
                    unreadCount = 0
                )
            }
            repo.markAllNotificationsRead().onFailure {
                refresh()
            }
        }
    }

    private suspend fun refreshUnreadCount() {
        repo.getUnreadNotificationCount().onSuccess { count ->
            _uiState.update { it.copy(unreadCount = count) }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
