package de.traewelling.app.util

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object OAuthHelper {

    /** Generate a cryptographically-random PKCE code_verifier */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Derive the code_challenge from a code_verifier (S256 method) */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Build the full OAuth authorization URL */
    fun buildAuthorizationUrl(
        serverUrl: String,
        clientId: String,
        redirectUri: String,
        scopes: String,
        codeChallenge: String,
        state: String
    ): String {
        return Uri.parse("$serverUrl/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /** Extract the authorization code from the callback URI */
    fun extractCode(callbackUri: Uri): String? =
        callbackUri.getQueryParameter("code")

    /** Extract the state from the callback URI for CSRF verification */
    fun extractState(callbackUri: Uri): String? =
        callbackUri.getQueryParameter("state")

    /** Generate a random state string for CSRF protection */
    fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
