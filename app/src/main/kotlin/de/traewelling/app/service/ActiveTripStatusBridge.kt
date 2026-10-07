package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.data.repository.StatusMutationSnapshot

/** The active service model and every status read share the repository's committed revision. */
internal class ActiveTripStatusBridge {
    private var statusId: Int? = null
    private var sessionRevision: String? = null
    private var adoptedRevision: Long? = null
    val revision: Long? get() = adoptedRevision

    fun bind(statusId: Int, sessionRevision: String) {
        this.statusId = statusId
        this.sessionRevision = sessionRevision
        adoptedRevision = null
    }

    fun clear() { statusId = null; sessionRevision = null; adoptedRevision = null }

    fun owns(event: StatusMutation): Boolean = event.statusId == statusId && event.sessionRevision == sessionRevision

    fun canPublish(snapshot: StatusMutationSnapshot): Boolean = adoptedRevision == snapshot.revision

    fun acceptsRead(revision: Long, current: StatusMutationSnapshot): Boolean = revision == current.revision

    fun acceptsRestoredCache(current: StatusMutationSnapshot): Boolean = current.revision == 0L && current.mutation == null

    fun adopt(revision: Long) { adoptedRevision = revision }

    fun replacement(snapshot: StatusMutationSnapshot): Status? =
        (snapshot.mutation as? StatusMutation.Updated)?.takeIf(::owns)?.status

    fun wasDeleted(snapshot: StatusMutationSnapshot): Boolean =
        snapshot.mutation is StatusMutation.Deleted && owns(snapshot.mutation)
}

internal data class ActiveTripRouteUpdate(
    val checkin: CheckinInfo,
    val stops: List<StopStation>,
    val rawStops: List<StopStation>,
    val fullStops: List<StopStation>,
    val boundariesChanged: Boolean,
    val tripChanged: Boolean,
    val rebasedProgress: TrackingProgress?
)

/** Builds the whole status/visit basis before the service changes any of its fields. */
internal fun prepareActiveTripRoute(
    status: Status,
    fullStops: List<StopStation>,
    previousCheckin: CheckinInfo?,
    previousStops: List<StopStation>,
    previousProgress: TrackingProgress?
): ActiveTripRouteUpdate? {
    val checkin = status.checkin?.takeIf { it.trip != null } ?: return null
    val rawRoute = selectCheckedInRoute(fullStops, checkin, applyManualTimes = false)
    if (rawRoute.isEmpty()) return null
    val route = selectCheckedInRoute(fullStops, checkin)
    val tripChanged = previousCheckin != null &&
        (previousCheckin.trip != checkin.trip || previousCheckin.tripUuid != checkin.tripUuid)
    fun sameVisit(first: StopStation?, second: StopStation?): Boolean =
        if (first == null || second == null) first == second else first.matchesStopover(second)
    val boundariesChanged = previousCheckin != null && (tripChanged ||
        !sameVisit(previousCheckin.origin, checkin.origin) || !sameVisit(previousCheckin.destination, checkin.destination))
    val progress = previousProgress?.let { old ->
        if (tripChanged) TrackingProgress() else {
            val oldKey = old.nextStopKey
            val retainedIndex = route.withIndex().firstOrNull { (index, stop) -> activeTripStopKey(stop, index) == oldKey }?.index
            val followingIndex = if (retainedIndex == null) previousStops.withIndex().drop(old.nextIndex + 1)
                .firstNotNullOfOrNull { (previousIndex, previous) -> route.withIndex().firstOrNull { (index, stop) ->
                    activeTripStopKey(stop, index) == activeTripStopKey(previous, previousIndex)
                }?.index } else null
            val index = retainedIndex ?: followingIndex ?: old.nextIndex.coerceIn(0, route.lastIndex)
            val next = route[index]
            val previous = previousStops.getOrNull(old.nextIndex)
            val key = activeTripStopKey(next, index)
            old.copy(nextIndex = index, nextStopKey = key,
                arrivedAtCurrent = old.arrivedAtCurrent && oldKey == key &&
                    previous?.station?.latitude == next.station?.latitude &&
                    previous?.station?.longitude == next.station?.longitude,
                announcedKeys = old.announcedKeys.intersect(route.mapIndexed { i, stop -> activeTripStopKey(stop, i) }.toSet()),
                completed = old.completed && !boundariesChanged)
        }
    }
    return ActiveTripRouteUpdate(checkin, route, rawRoute, fullStops, boundariesChanged, tripChanged, progress)
}

internal fun selectCheckedInRoute(stops: List<StopStation>, checkin: CheckinInfo, applyManualTimes: Boolean = true): List<StopStation> {
    val origin = stops.indexOfFirst { it.matchesStopover(checkin.origin) }
    val destination = stops.indexOfFirst { it.matchesStopover(checkin.destination) }
    if (origin < 0 || destination < origin) return emptyList()
    val route = stops.subList(origin, destination + 1)
    if (!applyManualTimes) return route
    return route.map { stop -> stop.copy(
        departureReal = if (stop.matchesStopover(checkin.origin)) checkin.manualDeparture ?: stop.departureReal else stop.departureReal,
        arrivalReal = if (stop.matchesStopover(checkin.destination)) checkin.manualArrival ?: stop.arrivalReal else stop.arrivalReal
    ) }
}

internal fun activeTripStopKey(stop: StopStation, index: Int): String = stop.uuid
    ?: "${stop.stationId ?: "unknown"}:${stop.arrivalPlanned}:${stop.departurePlanned}:$index"
