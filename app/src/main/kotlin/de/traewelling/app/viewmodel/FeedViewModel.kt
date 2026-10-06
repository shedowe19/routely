package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.repository.StatusMutationEvents
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager

class FeedViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val repo = TraewellingRepository(application, prefs)
    private val controller = FeedController(viewModelScope, object : FeedGateway {
        override suspend fun getDashboard(page: Int) = repo.getDashboard(page)
        override suspend fun getGlobalFeed(page: Int) = repo.getGlobalFeed(page)
        override suspend fun likeStatus(id: Int) = repo.likeStatus(id)
        override suspend fun unlikeStatus(id: Int) = repo.unlikeStatus(id)
    }, prefs::getAuthSession, StatusMutationEvents.events)
    val uiState = controller.uiState

    fun loadFeed(refresh: Boolean = false) = controller.loadFeed(refresh)
    fun refresh() = controller.refresh()
    fun loadMore() = controller.loadMore()
    fun switchFeedType(type: FeedType) = controller.switchFeedType(type)
    fun likeStatus(statusId: Int) = controller.likeStatus(statusId)
    fun clearError() = controller.clearError()
}
