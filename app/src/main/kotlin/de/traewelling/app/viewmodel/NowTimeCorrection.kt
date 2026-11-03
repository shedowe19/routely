package de.traewelling.app.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class NowTimeEvent(val label: String) { DEPARTURE("Abfahrt"), ARRIVAL("Ankunft") }

/** Now changes only the editor draft; both times can be corrected before one explicit save. */
internal fun StatusDetailUiState.withEditTimeNow(event: NowTimeEvent, now: Instant): StatusDetailUiState {
    if (!isEditing || !isOwnStatus || isUpdating || isDeleting || deletedStatusId != null ||
        status?.checkin == null || status.id <= 0) return this
    val time = now.truncatedTo(ChronoUnit.MINUTES).toString()
    return if (event == NowTimeEvent.DEPARTURE) copy(editDeparture = time, error = null)
        else copy(editArrival = time, editArrivalManuallyChanged = true, editArrivalNowRequested = true, error = null)
}

/** Only a successful server commit calls this, once, inside the ordered write boundary. */
internal suspend fun finishAcceptedArrival(
    timeoutMillis: Long = 15_000L,
    cleanup: suspend () -> Unit
): String? = try {
    withTimeout(timeoutMillis) { cleanup() }
    null
} catch (_: TimeoutCancellationException) {
    "Ankunft gespeichert; die lokale Begleitung konnte nicht rechtzeitig beendet werden. Bitte prüfe sie nach der Wiederherstellung."
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    "Ankunft gespeichert; der lokale Abschluss konnte nicht gespeichert werden. Bitte prüfe die Fahrt nach der Wiederherstellung."
}
