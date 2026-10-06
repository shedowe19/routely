package de.traewelling.app.data.model

/** Public timetable identity only. Realtime, platforms and device fixes are deliberately absent. */
data class TransitRouteVisit(
    val key: String,
    val stationId: Int?,
    val stationUuid: String?,
    val arrivalPlannedMillis: Long?,
    val departurePlannedMillis: Long?,
    val point: RoutePoint,
    val cancelled: Boolean = false
)

/** The complete, ordered checked-in range, including cancelled intermediate visits. */
data class TransitRouteRequest(
    val statusId: Int,
    val tripIdentity: String,
    val visits: List<TransitRouteVisit>
)

/** Locally bound native geometry; this does not certify the current official track routing. */
data class TransitRouteGeometry(
    val request: TransitRouteRequest,
    val segments: List<GpsSegmentGeometry>,
    val fetchedAtMillis: Long
)
