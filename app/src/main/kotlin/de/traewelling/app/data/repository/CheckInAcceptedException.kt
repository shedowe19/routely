package de.traewelling.app.data.repository

/** HTTP accepted creation: an incomplete response must never offer another create attempt. */
internal class CheckInAcceptedException : IllegalStateException(
    "Der Server hat den Check-in angenommen, aber keine auswertbare Fahrt zurückgegeben. " +
        "Bitte aktualisiere Feed oder Profil und prüfe die Fahrt, bevor du einen neuen Check-in startest."
)
