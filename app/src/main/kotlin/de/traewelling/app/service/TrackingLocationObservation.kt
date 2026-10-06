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

internal data class TrackingLocationObservation(
    val fix: LocationFix?,
    val clockChanged: Boolean,
    val isNew: Boolean
)

/**
 * Generation-local adapter: order/age use the provider's monotonic clock, while
 * event times use the current human clock. Location.time is deliberately absent.
 */
internal class TrackingLocationClock {
    private var latestElapsedRealtimeNanos: Long? = null
    private var cachedFix: LocationFix? = null
    private var clockOffsetMillis: Long? = null

    fun observe(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        elapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
        nowMillis: Long,
        hasSpeed: Boolean,
        speedMetersPerSecond: Double
    ): TrackingLocationObservation {
        if (nowElapsedRealtimeNanos <= 0 || nowMillis <= 0) {
            return TrackingLocationObservation(null, false, false)
        }
        val offset = nowMillis - nowElapsedRealtimeNanos / NANOS_PER_MILLI
        val clockChanged = clockOffsetMillis?.let { previous ->
            kotlin.math.abs(offset - previous) > MAX_CLOCK_DRIFT_MILLIS
        } == true
        clockOffsetMillis = offset
        if (clockChanged) cachedFix = null
        if (elapsedRealtimeNanos <= 0 || elapsedRealtimeNanos > nowElapsedRealtimeNanos) {
            return TrackingLocationObservation(null, clockChanged, false)
        }
        val ageNanos = nowElapsedRealtimeNanos - elapsedRealtimeNanos
        if (ageNanos > StationTrackingEngine.MAX_FIX_AGE_MILLIS * NANOS_PER_MILLI) {
            return TrackingLocationObservation(null, clockChanged, false)
        }
        val previousElapsed = latestElapsedRealtimeNanos
        if (previousElapsed != null && elapsedRealtimeNanos <= previousElapsed) {
            return TrackingLocationObservation(
                cachedFix.takeIf { elapsedRealtimeNanos == previousElapsed }, clockChanged, false
            )
        }
        val eventTime = nowMillis - ageNanos / NANOS_PER_MILLI
        if (eventTime <= 0) return TrackingLocationObservation(null, clockChanged, false)
        latestElapsedRealtimeNanos = elapsedRealtimeNanos
        cachedFix = trackingLocationFix(latitude, longitude, accuracyMeters, eventTime,
            hasSpeed, speedMetersPerSecond)
        return TrackingLocationObservation(cachedFix, clockChanged, true)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_CLOCK_DRIFT_MILLIS = 1_000L
    }
}
