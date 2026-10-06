package de.traewelling.app.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow

/** A lease identifies one Composition; it is not the lifetime of an editor or mutation. */
class StatusDetailAttachment internal constructor(
    internal val token: Long,
    val statusId: Int,
    internal val changedStatus: Boolean
)

/** Retained by the session ViewModel and shared by successive screen compositions. */
internal class StatusDetailPresentation {
    val state = MutableStateFlow(StatusDetailUiState())
    var statusId: Int? = null
        private set
    var generation = 0L
        private set
    private var nextToken = 0L
    private var attachment: StatusDetailAttachment? = null
    val isObserved: Boolean get() = attachment != null

    fun attach(statusId: Int): StatusDetailAttachment {
        require(statusId > 0)
        val changed = this.statusId != statusId
        if (changed) {
            ++generation
            this.statusId = statusId
            state.value = StatusDetailUiState()
        }
        return StatusDetailAttachment(++nextToken, statusId, changed).also { attachment = it }
    }

    fun detach(owner: StatusDetailAttachment): Boolean {
        if (attachment != owner) return false
        attachment = null
        return true
    }

    fun clear(expectedStatusId: Int? = null): Boolean {
        if (expectedStatusId != null && expectedStatusId != statusId) return false
        ++generation
        attachment = null
        statusId = null
        state.value = StatusDetailUiState()
        return true
    }

    /** Check retained state synchronously; a Compose enabled flag may still be one frame old. */
    fun leave(expectedStatusId: Int? = null): Boolean {
        if (state.value.isUpdating || state.value.isDeleting) return false
        return clear(expectedStatusId)
    }
}
