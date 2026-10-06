package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.GpsGeometrySource

/** The service's current ordered visit; device locations are never exposed. */
data class TrackingLiveState(
    val statusId: Int,
    val nextStopKey: String?,
    val nextIndex: Int,
    val stop: StopStation?,
    val arrivedAtCurrent: Boolean,
    val completed: Boolean,
    val source: TrackingSource,
    val gpsTimes: GpsJourneyTimes? = null,
    val sevStops: Map<String, SevStopInfo> = emptyMap(),
    val gpsTimeUnavailableReason: GpsTimeUnavailableReason? = null,
    val sessionRevision: String? = null,
    val gpsGeometrySource: GpsGeometrySource? = null,
    val locationError: String? = null,
    val speechError: String? = null
)
