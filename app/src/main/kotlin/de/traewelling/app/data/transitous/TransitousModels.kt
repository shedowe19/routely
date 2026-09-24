package de.traewelling.app.data.transitous

import com.google.gson.annotations.SerializedName
import java.time.Instant

data class TransitousCoordinate(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Geographic bounds of the visible map; anti-meridian crossing is currently unsupported. */
data class TransitousBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun isValid(): Boolean = TransitousCoordinate(south, west).isValid() &&
        TransitousCoordinate(north, east).isValid() && south < north && west < east

    fun contains(point: TransitousCoordinate): Boolean =
        point.latitude in south..north && point.longitude in west..east

    /** Limit the API request to the visible center when zoomed far out. */
    internal fun limited(maxLatitudeSpan: Double = 1.0, maxLongitudeSpan: Double = 1.0): TransitousBounds {
        val latitudeCenter = (south + north) / 2.0
        val longitudeCenter = (west + east) / 2.0
        val halfLatitude = minOf((north - south) / 2.0, maxLatitudeSpan / 2.0)
        val halfLongitude = minOf((east - west) / 2.0, maxLongitudeSpan / 2.0)
        return TransitousBounds(
            latitudeCenter - halfLatitude,
            longitudeCenter - halfLongitude,
            latitudeCenter + halfLatitude,
            longitudeCenter + halfLongitude,
        )
    }
}

/** A position inferred from trip times and path geometry; never a reported GPS fix. */
data class TransitousVehicleMarker(
    val tripId: String,
    val displayName: String,
    val mode: String,
    val routeColor: String?,
    val position: TransitousCoordinate,
    val fromName: String,
    val toName: String,
    val departure: Instant,
    val arrival: Instant,
    val realTime: Boolean,
    val delayMinutes: Int?,
)

/** Errors surfaced to the UI without exposing a large HTML/server response. */
class TransitousApiException(val httpStatus: Int) : Exception("Transitous request failed (HTTP $httpStatus)")

// The stable MOTIS 2.10.2 map/trips response. Nullable fields defend against
// incomplete feeds, and String preserves future transport modes.
internal data class TransitousTripSegmentDto(
    val trips: List<TransitousTripInfoDto>?,
    val mode: String?,
    val routeColor: String?,
    @SerializedName("from") val from: TransitousPlaceDto?,
    @SerializedName("to") val to: TransitousPlaceDto?,
    val departure: String?,
    val arrival: String?,
    val scheduledDeparture: String?,
    val scheduledArrival: String?,
    val realTime: Boolean?,
    val polyline: String?,
)

internal data class TransitousTripInfoDto(val tripId: String?, val displayName: String?)

internal data class TransitousPlaceDto(val name: String?, val lat: Double?, val lon: Double?)
