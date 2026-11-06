package de.traewelling.app.util

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun formatTimestamp(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val zdt = ZonedDateTime.parse(isoTimestamp)
        val local = zdt.withZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    } catch (_: Exception) {
        isoTimestamp.take(16).replace("T", " ")
    }
}

/**
 * Compact timestamp for dense lists. Falls back to the absolute timestamp for
 * unparseable values, so an API change cannot hide the time completely.
 */
fun formatRelativeTimestamp(
    isoTimestamp: String,
    now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val then = ZonedDateTime.parse(isoTimestamp)
            .withZoneSameInstant(now.zone)
        val minutes = Duration.between(then, now).toMinutes()
        when {
            minutes < 1 -> "gerade eben"
            minutes < 60 -> "vor $minutes Min"
            minutes < 24 * 60 -> "vor ${minutes / 60} Std"
            then.toLocalDate() == now.toLocalDate().minusDays(1) ->
                "gestern, ${then.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            then.year == now.year ->
                then.format(DateTimeFormatter.ofPattern("dd.MM., HH:mm"))
            else ->
                then.format(DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm"))
        }
    } catch (_: Exception) {
        formatTimestamp(isoTimestamp)
    }
}
