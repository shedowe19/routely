package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.TrainStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class DepartureSearchRequest(val stationId: Int, val whenTime: String?)

internal fun departureSearchRequest(state: CheckInUiState, station: TrainStation): DepartureSearchRequest {
    val id = requireNotNull(station.id?.takeIf { it > 0 }) { "Bahnhof hat keine gültige ID." }
    return DepartureSearchRequest(id, state.departureSearchTimeMillis?.let { Instant.ofEpochMilli(it).toString() })
}

/** Only a still-current query may replace the visible departures, even if a provider ignores cancellation. */
internal suspend fun queryCheckInDepartures(
    request: DepartureSearchRequest,
    fetch: suspend (Int, String?) -> Result<List<DepartureTrip>>,
    isCurrent: () -> Boolean,
    publish: (Result<List<DepartureTrip>>) -> Unit
) {
    val result = fetch(request.stationId, request.whenTime)
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    currentCoroutineContext().ensureActive()
    if (isCurrent()) publish(result)
}

/** Changing the query time invalidates its dependent selections but keeps the user's draft. */
internal fun withDepartureSearchTime(state: CheckInUiState, timeMillis: Long?): CheckInUiState? {
    if (state.hasPendingSubmission || state.step !in listOf(CheckInStep.STATION, CheckInStep.DEPARTURES)) return null
    return state.copy(
        departureSearchTimeMillis = timeMillis, departures = emptyList(), selectedDeparture = null,
        selectedTripDetails = null, resolvedOriginStop = null, filteredDestinations = emptyList(),
        selectedDestination = null, isLoading = false, error = null,
        // A cancelled pending lookup is not proof that the retained text found no stations.
        stationQuery = if (state.step == CheckInStep.STATION && state.isLoading && state.searchResults.isEmpty()) ""
            else state.stationQuery
    )
}

internal object DepartureSearchTime {
    fun datePickerMillis(instantMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(instantMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromLocalSelection(
        datePickerMillis: Long, hour: Int, minute: Int, zone: ZoneId, preferredOffset: ZoneOffset? = null
    ): Long {
        val date = Instant.ofEpochMilli(datePickerMillis).atOffset(ZoneOffset.UTC).toLocalDate()
        val local = LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) {
            "Diese Uhrzeit existiert wegen der Zeitumstellung nicht. Bitte wähle eine andere Uhrzeit."
        }
        val offset = preferredOffset?.takeIf { it in offsets } ?: offsets.first()
        return local.toInstant(offset).toEpochMilli()
    }

    fun label(timeMillis: Long?, zone: ZoneId): String = timeMillis?.let {
        Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm XXX"))
    } ?: "Jetzt"

    fun departureDate(value: String?, zone: ZoneId): String = value?.let {
        runCatching { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull()
    } ?: "–"
}
