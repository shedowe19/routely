package de.traewelling.app.data.model

/** Public replacement-stop map snapshot, independent of Träwelling station identity. */
data class SevMap(
    val slug: String,
    val sourceUrl: String,
    val stationLatitude: Double,
    val stationLongitude: Double,
    val points: List<SevPoint>,
    val notes: List<String>,
    val fetchedAtMillis: Long
)

data class SevPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String?,
    val version: String? = null
)

/** A visit's public guidance; coordinates are absent until the point is unambiguous. */
data class SevStopInfo(
    val sourceUrl: String,
    val label: String?,
    val guidance: String,
    val latitude: Double?,
    val longitude: Double?,
    val reason: String? = null
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}
