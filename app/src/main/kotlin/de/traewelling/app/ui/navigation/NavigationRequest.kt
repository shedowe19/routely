package de.traewelling.app.ui.navigation

/** A new token allows the same notification destination to be opened more than once. */
data class NavigationRequest(
    val token: Long,
    val statusId: Int? = null,
    val showCheckIn: Boolean = false,
    val authSessionRevision: String? = null
)
