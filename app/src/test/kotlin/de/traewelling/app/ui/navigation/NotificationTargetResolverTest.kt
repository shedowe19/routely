package de.traewelling.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTargetResolverTest {
    private val server = "https://traewelling.example"

    @Test fun opensStatusLinksFromTheCurrentInstance() {
        assertEquals(NotificationTarget.Status(123), resolve("$server/status/123"))
        assertEquals(NotificationTarget.Status(123), resolve("/status/123/"))
    }

    @Test fun opensProfileLinksUsingTheUpstreamUsernameContract() {
        assertEquals(NotificationTarget.UserProfile("Alice_42"), resolve("$server/@Alice_42"))
        assertEquals(NotificationTarget.UserProfile("alice"), resolve("/@alice"))
    }

    @Test fun ignoresQueryAndFragmentWithoutChangingTheInternalDestination() {
        assertEquals(NotificationTarget.Status(123), resolve("/status/123?source=notification#details"))
    }

    @Test fun respectsCustomInstancePortsAndInstallationPaths() {
        val custom = "https://instance.example:8443/traewelling/"
        assertEquals(NotificationTarget.Status(7), NotificationTargetResolver.resolve(
            "https://instance.example:8443/traewelling/status/7", custom))
        assertEquals(NotificationTarget.UserProfile("alice"), NotificationTargetResolver.resolve(
            "/traewelling/@alice", custom))
        assertNull(NotificationTargetResolver.resolve("https://instance.example:8443/status/7", custom))
        assertNull(NotificationTargetResolver.resolve("https://instance.example/traewelling/status/7", custom))
        assertNull(NotificationTargetResolver.resolve("/traewelling-other/status/7", custom))
    }

    @Test fun doesNotOpenLinksFromAnotherInstanceOrAnImpersonatedHost() {
        listOf(
            "https://other.example/status/123",
            "https://traewelling.example.attacker.test/status/123",
            "https://traewelling.example:8443/status/123",
            "https://traewelling.example@attacker.test/status/123",
            "https://name:secret@traewelling.example/status/123"
        ).forEach { assertNull(it, resolve(it)) }
    }

    @Test fun rejectsUnsafeSchemesAndAmbiguousLinks() {
        listOf(
            "http://traewelling.example/status/123", "javascript:alert(1)",
            "//traewelling.example/status/123", "status/123", "/status\\123",
            "/status/1\n23", "/status/1\t23", "\n/status/123", "not a URL"
        ).forEach { assertNull(it, resolve(it)) }
    }

    @Test fun requiresAPositiveStatusIdThatTheDetailScreenCanRepresent() {
        listOf("0", "-1", "+1", "2147483648", "one", "1.0", "１２３", "")
            .forEach { assertNull(it, resolve("/status/$it")) }
    }

    @Test fun rejectsMalformedProfileNamesAndEncodedPathInjection() {
        listOf(
            "/@", "/@two%20words", "/@alice%2Fstatus%2F123", "/@alice%3Fquery",
            "/@alice%23fragment", "/@alice%00", "/@alice-name", "/@alice.name",
            "/@" + "a".repeat(26)
        ).forEach { assertNull(it, resolve(it)) }
    }

    @Test fun leavesUnsupportedNotificationDestinationsWithoutNavigation() {
        listOf("/settings/followers", "/settings/security", "/event/conference", "/user/alice",
            "/status/123/extra", "/@alice/extra", "/status/123//")
            .forEach { assertNull(it, resolve(it)) }
        assertNull(resolve(null))
        assertNull(resolve(""))
    }

    @Test fun rejectsAnInvalidOrCredentialBearingActiveServer() {
        listOf("invalid", "http://traewelling.example", "https://user:secret@traewelling.example",
            "$server?token=secret", "$server#fragment")
            .forEach { assertNull(it, NotificationTargetResolver.resolve("/status/123", it)) }
    }

    private fun resolve(link: String?) = NotificationTargetResolver.resolve(link, server)
}
