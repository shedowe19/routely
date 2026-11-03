package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.data.repository.apiResult
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class NowTimeEvent(val label: String) { DEPARTURE("Abfahrt"), ARRIVAL("Ankunft") }

internal data class NowTimeSubmission(
    val statusId: Int,
    val event: NowTimeEvent,
    val time: Instant,
    val request: UpdateStatusRequest
)

internal data class NowTimeCompletion(val status: Status?, val warning: String?)

internal val StatusDetailUiState.canRecordNow: Boolean
    get() = isOwnStatus && status?.checkin != null && status.id > 0 &&
        !isEditing && !isUpdating && !isDeleting && deletedStatusId == null

/** Owns one synchronous click, so an old Compose enabled flag cannot submit twice. */
internal class NowTimeCorrectionGate {
    private var pending: NowTimeSubmission? = null

    fun begin(state: StatusDetailUiState, event: NowTimeEvent, now: Instant): NowTimeSubmission? {
        if (pending != null || !state.canRecordNow) return null
        return buildNowTimeSubmission(requireNotNull(state.status), event, now).also { pending = it }
    }

    fun finish(submission: NowTimeSubmission) {
        if (pending === submission) pending = null
    }

    fun reset() { pending = null }
}

/** The server stores minute precision; GPS display estimates never become edit baselines. */
internal fun buildNowTimeSubmission(status: Status, event: NowTimeEvent, now: Instant): NowTimeSubmission {
    require(status.id > 0) { "Diese Fahrt hat keine gültige ID." }
    val checkin = requireNotNull(status.checkin) { "Diese Fahrt enthält keine Check-in-Zeiten." }
    val time = now.truncatedTo(ChronoUnit.MINUTES)
    fun parsed(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val departure = parsed(checkin.manualDeparture) ?: parsed(checkin.origin?.departureReal)
        ?: parsed(checkin.origin?.departurePlanned)
    val arrival = parsed(checkin.manualArrival) ?: parsed(checkin.destination?.arrivalReal)
        ?: parsed(checkin.destination?.arrivalPlanned)
    if (event == NowTimeEvent.ARRIVAL) {
        require(departure != null) { "Die Abfahrtszeit fehlt. Bitte aktualisiere oder bearbeite die Fahrt." }
        require(!time.isBefore(departure)) {
            "Die Ankunft darf nicht vor der gespeicherten Abfahrt liegen. Bitte korrigiere zuerst die Abfahrt."
        }
    } else {
        require(arrival != null) { "Die Ankunftszeit fehlt. Bitte aktualisiere oder bearbeite die Fahrt." }
        require(!time.isAfter(arrival)) {
            "Die Abfahrt darf nicht nach der gespeicherten Ankunft liegen. Bitte prüfe die Zeiten in der Bearbeitung."
        }
    }
    return NowTimeSubmission(status.id, event, time,
        if (event == NowTimeEvent.DEPARTURE) UpdateStatusRequest(departure = time.toString())
        else UpdateStatusRequest(arrival = time.toString()))
}

/** A committed correction must not become retryable because local completion failed. */
internal suspend fun submitNowTimeCorrection(
    submission: NowTimeSubmission,
    session: AuthSession,
    update: suspend (Int, UpdateStatusRequest, AuthSession, suspend (AuthSession) -> Unit) -> Result<Status>,
    finishArrival: suspend (Int, AuthSession) -> Unit,
    onCleanupFailure: (Exception) -> Unit = {},
    cleanupTimeoutMillis: Long = 15_000L
): Result<NowTimeCompletion> = apiResult {
    var committed = false
    var warning: String? = null
    val response = update(submission.statusId, submission.request, session) { owner ->
        require(owner == session) { "Fahrt gehört nicht mehr zur aktuellen Sitzung." }
        committed = true
        if (submission.event == NowTimeEvent.ARRIVAL) {
            val failure = try {
                withTimeout(cleanupTimeoutMillis) { finishArrival(submission.statusId, owner) }
                null
            } catch (timeout: TimeoutCancellationException) {
                IllegalStateException("Lokale Begleitung konnte nicht rechtzeitig beendet werden.", timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            if (failure != null) {
                warning = "Ankunft gespeichert; die lokale Begleitung konnte nicht beendet werden. Bitte stoppe sie manuell."
                runCatching { onCleanupFailure(failure) }
            }
        }
    }
    if (!committed) response.getOrThrow()
    val status = response.getOrNull()
    if (status == null && committed) {
        warning = listOfNotNull(warning,
            "Zeit gespeichert; die Antwort ist unvollständig. Bitte aktualisiere die Fahrt.").joinToString(" ")
    }
    NowTimeCompletion(status, warning)
}
