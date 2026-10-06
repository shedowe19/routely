package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import java.time.OffsetDateTime

/** A recurring station needs the departure's visit time, not just its station ID. */
internal fun resolveCheckInOriginIndex(
    stops: List<StopStation>, origin: TrainStation?, departure: DepartureTrip
): Int {
    if (origin == null) return -1
    val ibnr = origin.identifier("de_db_ibnr")
    val matches = stops.indices.filter { index ->
        val stop = stops[index]
        (origin.id != null && stop.stationId == origin.id) ||
            (ibnr != null && stop.stationIdentifier("de_db_ibnr") == ibnr)
    }
    val timedMatches = matches.filter { index ->
        val stop = stops[index]
        sameCheckInInstant(stop.departurePlanned, departure.plannedWhen) ||
            sameCheckInInstant(stop.effectiveDeparture, departure.realWhen)
    }
    return timedMatches.singleOrNull() ?: matches.singleOrNull() ?: -1
}

internal fun validCheckInDestinations(stops: List<StopStation>, originIndex: Int): List<StopStation> =
    if (originIndex !in stops.indices || stops[originIndex].cancelled == true) emptyList()
    else stops.drop(originIndex + 1).filter {
        it.cancelled != true && it.stationId != null &&
            !(it.arrivalPlanned ?: it.effectiveArrival).isNullOrBlank()
    }

internal fun sameCheckInInstant(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    return runCatching {
        OffsetDateTime.parse(first).toInstant() == OffsetDateTime.parse(second).toInstant()
    }.getOrDefault(false)
}
