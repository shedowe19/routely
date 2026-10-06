package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.CheckInResult
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.data.repository.apiResult
import de.traewelling.app.data.repository.CheckInAcceptedException
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.OffsetDateTime

internal data class CheckInSubmission(val request: CheckInRequest, val timeCorrection: UpdateStatusRequest?)
internal data class CheckInCompletion(val result: CheckInResult?, val warning: String? = null)

/** Planned markers identify the visits; manual real times belong only to the subsequent status PUT. */
internal fun buildCheckInSubmission(state: CheckInUiState, origin: StopStation?): CheckInSubmission {
    val departure = requireNotNull(state.selectedDeparture) { "Bitte wähle eine Abfahrt." }
    val destination = requireNotNull(state.selectedDestination) { "Bitte wähle einen Zielhalt." }
    val startId = origin?.stationId ?: departure.station?.id ?: state.selectedStation?.id
    require(startId != null && startId > 0 && destination.stationId != null && destination.stationId!! > 0) {
        "Start- oder Zielbahnhof hat keine gültige ID."
    }
    val plannedDeparture = origin?.departurePlanned ?: departure.plannedWhen
    val plannedArrival = destination.arrivalPlanned
    fun parsed(value: String?, description: String): Instant = value?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    } ?: throw IllegalArgumentException("$description fehlt oder ist ungültig.")
    val departureMarker = parsed(plannedDeparture, "Die geplante Abfahrtszeit")
    val arrivalMarker = parsed(plannedArrival, "Die geplante Ankunftszeit")
    require(departureMarker <= arrivalMarker) { "Die Ankunft muss nach der Abfahrt liegen." }
    fun manual(value: String, description: String): String? = value.trim().takeIf(String::isNotEmpty)?.let {
        val instant = runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: throw IllegalArgumentException("$description: Bitte ISO-Zeit mit Zeitzone eingeben, z. B. 2026-10-06T18:05:00+02:00.")
        instant.toString()
    }
    val manualDeparture = manual(state.manualDeparture, "Abfahrt")
    val manualArrival = manual(state.manualArrival, "Ankunft")
    if (manualDeparture != null || manualArrival != null) {
        val realDeparture = manualDeparture?.let(Instant::parse)
            ?: origin?.effectiveDeparture?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: departureMarker
        val realArrival = manualArrival?.let(Instant::parse)
            ?: destination.effectiveArrival?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: arrivalMarker
        require(realDeparture <= realArrival) { "Die korrigierte Ankunft muss nach der Abfahrt liegen." }
    }
    return CheckInSubmission(
        request = CheckInRequest(
            tripId = departure.tripId, lineName = departure.line?.name.orEmpty(),
            startStationId = startId, destinationStationId = requireNotNull(destination.stationId),
            departure = requireNotNull(plannedDeparture), arrival = requireNotNull(plannedArrival),
            body = state.statusBody.ifBlank { null }, business = state.travelReason.apiValue
        ),
        timeCorrection = if (manualDeparture == null && manualArrival == null) null
            else UpdateStatusRequest(departure = manualDeparture, arrival = manualArrival)
    )
}

/** There is exactly one create call. Any later failure belongs to that already-created check-in. */
internal suspend fun submitCheckIn(
    submission: CheckInSubmission,
    create: suspend (CheckInRequest) -> Result<CheckInResult?>,
    onCreated: suspend (CheckInResult?) -> Unit,
    correctTimes: suspend (Int, UpdateStatusRequest) -> Result<Status>
): Result<CheckInCompletion> = apiResult {
    val response = create(submission.request)
    if (response.exceptionOrNull() is CheckInAcceptedException || response.isSuccess &&
        (response.getOrNull()?.status?.id ?: 0) <= 0) {
        onCreated(null)
        return@apiResult CheckInCompletion(null, CheckInAcceptedException().message)
    }
    val created = requireNotNull(response.getOrThrow())
    val statusId = requireNotNull(created.status).id
    val warnings = mutableListOf<String>()
    try {
        onCreated(created)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        warnings += "Du bist eingecheckt, aber die lokale Reisebegleitung konnte nicht aktiviert werden. Öffne die Fahrt im Profil."
    }
    var result = created
    submission.timeCorrection?.let { request ->
        apiResult { correctTimes(statusId, request).getOrThrow() }
            .onSuccess { status -> result = created.copy(status = status) }
            .onFailure {
                warnings += "Du bist eingecheckt. Die manuelle Zeitkorrektur konnte nicht gespeichert werden. Bearbeite die erstellte Fahrt im Profil; ein erneuter Check-in ist nicht nötig."
            }
    }
    CheckInCompletion(result, warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
}
