package de.traewelling.app.ui.screens

import de.traewelling.app.data.model.DepartureTrip
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DepartureTimePresentation(
    val time: String,
    val plannedTime: String?,
    val deviation: String?,
    val earlier: Boolean,
    val delayed: Boolean
)

/** Display the usable real departure, including early departures, with its planned reference. */
internal fun departureTimePresentation(
    departure: DepartureTrip?, zoneId: ZoneId = ZoneId.systemDefault()
): DepartureTimePresentation {
    fun parse(value: String?): Instant? = value?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
    fun format(value: Instant): String = value.atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))
    val planned = parse(departure?.plannedWhen)
    val real = parse(departure?.realWhen)
    val effective = real ?: planned
    val minutes = if (planned != null && real != null) Duration.between(planned, real).toMinutes() else 0L
    return DepartureTimePresentation(
        time = effective?.let(::format) ?: "–",
        plannedTime = planned?.takeIf { real != null && it != real }?.let(::format),
        deviation = minutes.takeIf { it != 0L }?.let { "${if (it > 0) "+" else ""}${it} min" },
        earlier = minutes < 0,
        delayed = minutes > 0
    )
}
