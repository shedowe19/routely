package de.traewelling.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal fun journeyDateTimeInstant(value: String?): Instant? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

internal fun journeyDateTimeLabel(value: String, zone: ZoneId, is24Hour: Boolean, locale: Locale): String? =
    journeyDateTimeInstant(value)?.atZone(zone)?.format(
        DateTimeFormatter.ofPattern(if (is24Hour) "dd.MM.yyyy · HH:mm" else "dd.MM.yyyy · h:mm a", locale)
    )

internal fun journeyDateTimeZoneLabel(value: String?, zone: ZoneId): String {
    val offset = journeyDateTimeInstant(value)?.atZone(zone)?.offset?.id
    return zone.id + (offset?.let { " (UTC${if (it == "Z") "+00:00" else it})" } ?: "")
}

/** Resolve an explicit wall-clock choice; never silently shift a missing DST clock time. */
internal fun journeyDateTimeSelection(
    date: LocalDate,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    originalValue: String
): String {
    val local = LocalDateTime.of(date, LocalTime.of(hour, minute))
    val validOffsets = zone.rules.getValidOffsets(local)
    require(validOffsets.isNotEmpty()) {
        "Diese Uhrzeit gibt es wegen der Zeitumstellung nicht. Bitte wähle eine andere Uhrzeit."
    }
    val original = journeyDateTimeInstant(originalValue)?.atZone(zone)
    val preferredOffset = original?.offset?.takeIf {
        original.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) == local && it in validOffsets
    }
    return ZonedDateTime.ofLocal(local, zone, preferredOffset).toInstant().truncatedTo(ChronoUnit.MINUTES).toString()
}
