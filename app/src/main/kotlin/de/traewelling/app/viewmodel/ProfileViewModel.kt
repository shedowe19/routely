package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.repository.StatusMutationEvents
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val repository = TraewellingRepository(application, prefs)
    private val controller = ProfileController(viewModelScope, object : ProfileGateway {
        override suspend fun getCurrentUser() = repository.getCurrentUser()
        override suspend fun getStatistics() = repository.getStatistics()
        override suspend fun getUserStatuses(username: String, page: Int) = repository.getUserStatuses(username, page)
    }, prefs::getAuthSession, StatusMutationEvents.events)
    val uiState = controller.uiState

    fun loadProfile(refresh: Boolean = false) = controller.loadProfile(refresh)
    fun refresh() = controller.refresh()
    fun clearError() = controller.clearError()
}
