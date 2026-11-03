package de.traewelling.app.data.repository

import de.traewelling.app.data.model.CheckInResult

/** HTTP accepted creation: an incomplete response must never offer another create attempt. */
internal class CheckInAcceptedException(
    val created: CheckInResult? = null,
    cause: Throwable? = null
) : IllegalStateException(
    "Der Server hat den Check-in angenommen. Die Antwort oder die lokale Prüfung konnte nicht abgeschlossen werden. " +
        "Bitte prüfe die Fahrt im Feed oder Profil; ein erneuter Check-in ist nicht nötig.", cause
)
