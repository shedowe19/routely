package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.repository.StatusMutationEvents
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager

class UserProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val repository = TraewellingRepository(application, prefs)
    private val controller = UserProfileController(viewModelScope, object : UserProfileGateway {
        override suspend fun getUserProfile(username: String) = repository.getUserProfile(username)
        override suspend fun getUserStatuses(username: String, page: Int) = repository.getUserStatuses(username, page)
        override suspend fun followUser(id: Int) = repository.followUser(id)
        override suspend fun unfollowUser(id: Int) = repository.unfollowUser(id)
    }, prefs::getAuthSession, StatusMutationEvents.events)
    val uiState = controller.uiState

    fun loadUserProfile(username: String, refresh: Boolean = false) = controller.loadUserProfile(username, refresh)
    fun loadMoreStatuses() = controller.loadMoreStatuses()
    fun toggleFollow() = controller.toggleFollow()
    fun refresh() = controller.refresh()
    fun reset(username: String? = null) = controller.reset(username)
    fun clearError() = controller.clearError()
}
