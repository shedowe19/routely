package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.StopRealtimeInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.service.JourneyTime
import de.traewelling.app.service.JourneyTimeSource

enum class StopEvent { ARRIVAL, DEPARTURE }

data class PlatformPresentation(val label: String, val changed: Boolean)

data class RealtimeRetrievalPresentation(
    val label: String,
    val detail: String,
    val recentlyRetrieved: Boolean
)

fun eventRealtimeInfo(stop: StopStation?, event: StopEvent): StopRealtimeInfo? = when (event) {
    StopEvent.ARRIVAL -> stop?.arrivalRealtimeInfo
    StopEvent.DEPARTURE -> stop?.departureRealtimeInfo
}

fun eventPlatformRealtimeInfo(stop: StopStation, event: StopEvent): StopRealtimeInfo? = when (event) {
    StopEvent.ARRIVAL -> stop.arrivalPlatformRealtimeInfo
    StopEvent.DEPARTURE -> stop.departurePlatformRealtimeInfo
}

/** A departure change must never be shown as the arrival platform, or vice versa. */
fun platformPresentation(stop: StopStation, event: StopEvent): PlatformPresentation? {
    val planned = (when (event) {
        StopEvent.ARRIVAL -> stop.arrivalPlatformPlanned
        StopEvent.DEPARTURE -> stop.departurePlatformPlanned
    }).platformValue()
    val real = (when (event) {
        StopEvent.ARRIVAL -> stop.arrivalPlatformReal
        StopEvent.DEPARTURE -> stop.departurePlatformReal
    }).platformValue()
    val platform = real ?: planned ?: stop.platform.platformValue() ?: return null
    val changed = planned != null && real != null && !planned.equals(real, ignoreCase = true)
    return PlatformPresentation(
        if (changed) "Gleis $platform statt $planned" else "Gleis $platform", changed
    )
}

private fun String?.platformValue(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    ?.replace(Regex("^(?:Gleis\\s+|Gl\\.\\s*|Gl\\s+)", RegexOption.IGNORE_CASE), "")
    ?.trim()?.takeIf { it.isNotEmpty() }

/** The timestamp proves a client retrieval, never how fresh the provider's underlying feed is. */
fun realtimeRetrievalPresentation(
    info: StopRealtimeInfo?,
    nowMillis: Long,
    refreshFailed: Boolean = false
): RealtimeRetrievalPresentation {
    val source = info?.sourceLabel?.takeIf { it.isNotBlank() } ?: "Quelle unbekannt"
    val age = info?.fetchedAtMillis?.takeIf { it > 0 && it <= nowMillis }?.let { nowMillis - it }
    val ageLabel = when {
        age == null -> "Abrufzeit unbekannt"
        age < 60_000 -> "abgerufen vor ${age / 1_000} s"
        age < 3_600_000 -> "abgerufen vor ${age / 60_000} min"
        else -> "abgerufen vor ${age / 3_600_000} h"
    }
    val fresh = !refreshFailed && info?.isFresh(nowMillis) == true
    val label = when {
        refreshFailed -> "Offline / Abruffehler"
        info == null || age == null -> "Abruf nicht bestätigt"
        fresh -> "Daten abgerufen"
        else -> "Abruf veraltet"
    }
    return RealtimeRetrievalPresentation(label, "$source · $ageLabel", fresh)
}

/** Report the oldest included event, so a fresh IRIS departure cannot hide an old arrival. */
fun stopoversRetrievalPresentation(
    stops: List<StopStation>,
    nowMillis: Long,
    refreshFailed: Boolean
): RealtimeRetrievalPresentation {
    val events = stops.flatMap { stop -> listOfNotNull(
        stop.arrivalRealtimeInfo, stop.departureRealtimeInfo, stop.cancellationRealtimeInfo,
        stop.arrivalPlatformRealtimeInfo, stop.departurePlatformRealtimeInfo
    ) }
    val unknownEvent = stops.any { stop ->
        (stop.arrivalPlanned != null || stop.arrivalReal != null) && stop.arrivalRealtimeInfo == null ||
            (stop.departurePlanned != null || stop.departureReal != null) && stop.departureRealtimeInfo == null
    }
    val oldest = if (unknownEvent) null else (events.firstOrNull {
        it.fetchedAtMillis <= 0 || it.fetchedAtMillis > nowMillis
    } ?: events.minByOrNull { it.fetchedAtMillis })
    val presentation = realtimeRetrievalPresentation(oldest, nowMillis, refreshFailed)
    val sources = events.map { it.sourceLabel }.filter { it.isNotBlank() }.distinct().joinToString(" + ")
    return if (sources.isEmpty() || oldest == null) presentation else presentation.copy(
        detail = "$sources · ${presentation.detail.removePrefix("${oldest.sourceLabel.takeIf { it.isNotBlank() } ?: "Quelle unbekannt"} · ")}"
    )
}

/** Manual and GPS displays keep their own source; provider provenance belongs only to API time. */
fun journeyTimeSourceLabel(
    time: JourneyTime,
    info: StopRealtimeInfo?,
    nowMillis: Long,
    refreshFailed: Boolean
): String {
    if (time.source != JourneyTimeSource.API_REALTIME) return time.sourceLabel
    val retrieval = realtimeRetrievalPresentation(info, nowMillis, refreshFailed)
    return if (retrieval.recentlyRetrieved) retrieval.detail
    else "${retrieval.detail} · ${retrieval.label.lowercase()}"
}
