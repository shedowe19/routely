package de.traewelling.app.service

/** Preserve reported invalid speed for the engines' distinct invalid/absent checks. */
internal fun trackingLocationFix(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Double,
    timeMillis: Long,
    hasSpeed: Boolean,
    speedMetersPerSecond: Double
): LocationFix = LocationFix(
    latitude, longitude, accuracyMeters, timeMillis,
    if (hasSpeed) speedMetersPerSecond else null
)
