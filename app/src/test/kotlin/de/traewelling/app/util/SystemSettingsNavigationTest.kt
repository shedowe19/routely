package de.traewelling.app.util

import de.traewelling.app.util.SystemSettingsNavigation.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSettingsNavigationTest {
    @Test fun modernLiveUpdateSettingsFallBackFromMissingAndDeniedPages() {
        val attempts = mutableListOf<Page>()
        assertTrue(SystemSettingsNavigation.liveUpdates(36) { page ->
            attempts += page
            page == Page.APP_DETAILS
        })
        assertEquals(listOf(Page.LIVE_UPDATES, Page.NOTIFICATIONS, Page.APP_DETAILS), attempts)
    }

    @Test fun olderAndroidSkipsUnsupportedPromotionSettings() {
        val attempts = mutableListOf<Page>()
        assertTrue(SystemSettingsNavigation.liveUpdates(35) { page ->
            attempts += page
            page == Page.NOTIFICATIONS
        })
        assertEquals(listOf(Page.NOTIFICATIONS), attempts)
    }

    @Test fun allUnavailablePagesReturnFailureForVisibleManualInstructions() {
        val attempts = mutableListOf<Page>()
        assertFalse(SystemSettingsNavigation.liveUpdates(36) { page -> attempts += page; false })
        assertEquals(listOf(Page.LIVE_UPDATES, Page.NOTIFICATIONS, Page.APP_DETAILS, Page.GENERAL), attempts)
    }

    @Test fun locationSettingsFallBackToAppDetailsWithoutLaunchingMoreAfterSuccess() {
        val attempts = mutableListOf<Page>()
        assertTrue(SystemSettingsNavigation.location { page -> attempts += page; page == Page.APP_DETAILS })
        assertEquals(listOf(Page.LOCATION, Page.APP_DETAILS), attempts)
    }

    @Test fun permanentlyDeniedPermissionStillHasAFallbackAndReportsCompleteFailure() {
        val attempts = mutableListOf<Page>()
        assertFalse(SystemSettingsNavigation.permissions { page -> attempts += page; false })
        assertEquals(listOf(Page.APP_DETAILS, Page.GENERAL), attempts)
    }
}
