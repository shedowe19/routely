package de.traewelling.app.data.repository

import de.traewelling.app.data.model.Status

/** HTTP committed, but local storage cannot currently confirm session ownership for publication/cleanup. */
internal class StatusWriteAcceptedException(
    val statusId: Int,
    val status: Status? = null,
    val deleted: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(
    "Der Server hat die Änderung angenommen. Die lokale Prüfung konnte nicht abgeschlossen werden. " +
        "Bitte aktualisiere die Fahrt nach der Wiederherstellung; die Änderung muss nicht erneut gesendet werden.", cause
)
