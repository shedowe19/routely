package de.traewelling.app.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Reject malformed or credential-bearing servers before a token can be sent or logged. */
internal object ApiServerUrl {
    fun normalize(value: String): String {
        val url = value.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) {
            "Bitte eine gültige HTTPS-Server-URL ohne Zugangsdaten, Query oder Fragment eingeben."
        }
        return url.toString().trimEnd('/')
    }
}
