package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager

class NotificationViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = TraewellingRepository(application, PreferencesManager(application))
    private val controller = NotificationController(viewModelScope, object : NotificationGateway {
        override suspend fun getNotifications(page: Int) = repo.getNotifications(page)
        override suspend fun getUnreadNotificationCount() = repo.getUnreadNotificationCount()
        override suspend fun markNotificationRead(id: String) = repo.markNotificationRead(id)
        override suspend fun markAllNotificationsRead() = repo.markAllNotificationsRead()
    })
    val uiState = controller.uiState

    fun loadNotifications(refresh: Boolean = false) = controller.loadNotifications(refresh)
    fun refresh() = controller.refresh()
    fun loadMore() = controller.loadMore()
    fun markAsRead(notificationId: String) = controller.markAsRead(notificationId)
    fun markAllAsRead() = controller.markAllAsRead()
    fun clearError() = controller.clearError()
}
