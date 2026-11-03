package de.traewelling.app.ui.navigation

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface NotificationTarget {
    data class Status(val id: Int) : NotificationTarget
    data class UserProfile(val username: String) : NotificationTarget
}

/** Resolve only app-supported links belonging to the currently authenticated instance. */
internal object NotificationTargetResolver {
    private val usernamePattern = Regex("[A-Za-z0-9_]{1,25}")

    fun resolve(link: String?, serverUrl: String): NotificationTarget? {
        val server = serverUrl.toHttpUrlOrNull() ?: return null
        if (!server.isHttps || server.username.isNotEmpty() || server.password.isNotEmpty() ||
            server.query != null || server.fragment != null) return null

        if (link == null || link.any { it.isISOControl() || it == '\\' }) return null
        val value = link.trim().takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("//")) return null
        val target = if (value.startsWith('/')) server.resolve(value) else value.toHttpUrlOrNull()
        target ?: return null
        if (target.scheme != server.scheme || target.host != server.host || target.port != server.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty()) return null

        // Preserve a custom instance's installation path, rather than opening another app on its host.
        val baseSegments = server.pathSegments.dropLastWhile { it.isEmpty() }
        val targetSegments = target.pathSegments.let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        if (targetSegments.take(baseSegments.size) != baseSegments) return null
        val route = targetSegments.drop(baseSegments.size)
        if (route.size == 2 && route[0] == "status") {
            val id = route[1].takeIf { it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()
            return id?.takeIf { it > 0 }?.let { NotificationTarget.Status(it) }
        }
        if (route.size == 1 && route[0].startsWith('@')) {
            val username = route[0].drop(1)
            return username.takeIf(usernamePattern::matches)?.let { NotificationTarget.UserProfile(it) }
        }
        return null
    }
}
