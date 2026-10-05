package de.traewelling.app.service

import de.traewelling.app.data.model.StopStation

/** The service's current ordered visit; device locations are never exposed. */
data class TrackingLiveState(
    val statusId: Int,
    val nextStopKey: String?,
    val nextIndex: Int,
    val stop: StopStation?,
    val arrivedAtCurrent: Boolean,
    val completed: Boolean,
    val source: TrackingSource
)
