package de.traewelling.app.data.repository

import de.traewelling.app.data.model.TrainStation

/** Similar names and nearby coordinates do not prove that two boarding stations are identical. */
internal fun deduplicateNearbyStations(stations: List<TrainStation>): List<TrainStation> {
    val known = mutableSetOf<String>()
    return stations.filter { station ->
        val key = station.id?.takeIf { it > 0 }?.let { "id:$it" }
            ?: station.uuid?.takeIf { it.isNotBlank() }?.let { "uuid:$it" }
        key == null || known.add(key)
    }
}
