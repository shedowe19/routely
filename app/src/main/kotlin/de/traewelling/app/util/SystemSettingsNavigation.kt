package de.traewelling.app.util

/** Ordered platform fallbacks; a failed or denied launch never counts as opening settings. */
internal object SystemSettingsNavigation {
    enum class Page { LIVE_UPDATES, NOTIFICATIONS, APP_DETAILS, LOCATION, GENERAL }

    fun liveUpdates(apiLevel: Int, launch: (Page) -> Boolean): Boolean {
        val pages = buildList {
            if (apiLevel >= 36) add(Page.LIVE_UPDATES)
            add(Page.NOTIFICATIONS)
            add(Page.APP_DETAILS)
            add(Page.GENERAL)
        }
        return pages.any(launch)
    }

    fun location(launch: (Page) -> Boolean): Boolean =
        listOf(Page.LOCATION, Page.APP_DETAILS, Page.GENERAL).any(launch)

    fun permissions(launch: (Page) -> Boolean): Boolean =
        listOf(Page.APP_DETAILS, Page.GENERAL).any(launch)
}
