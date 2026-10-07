package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.repository.StatusDetailSnapshot
import de.traewelling.app.data.repository.apiResult

internal data class StatusDetailRead(
    val status: Status,
    val stopovers: Result<List<StopStation>>?
)

/** The status revision must survive the second endpoint, including an external time correction. */
internal suspend fun readConsistentStatusDetail(
    readStatus: suspend () -> Result<StatusDetailSnapshot>,
    readStopovers: suspend (Int) -> Result<List<StopStation>>,
    isCurrentRevision: suspend (Long) -> Boolean
): Result<StatusDetailRead> = apiResult {
    repeat(2) {
        val snapshot = readStatus().getOrThrow()
        val stops = snapshot.status.checkin?.trip?.let { readStopovers(it) }
        if (isCurrentRevision(snapshot.revision)) {
            return@apiResult StatusDetailRead(snapshot.status, stops)
        }
    }
    error("Fahrt wurde während des Abrufs geändert. Bitte erneut aktualisieren.")
}
