package de.traewelling.app.util

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
