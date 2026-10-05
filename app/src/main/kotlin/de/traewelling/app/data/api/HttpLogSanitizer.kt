package de.traewelling.app.data.api

/** Nearby station lookups must not leave device coordinates in debug HTTP logs. */
internal object HttpLogSanitizer {
    private val locationQuery = Regex("([?&](?:latitude|longitude|lat|lon|min_lat|max_lat|min_lon|max_lon)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    fun sanitize(message: String): String = locationQuery.replace(message) { "${it.groupValues[1]}[redacted]" }
}
