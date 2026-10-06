package de.traewelling.app.service

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TrackingSource { GPS, TIMETABLE }

/** One visit to a station. [key] identifies the stopover, not the station. */
data class TrackingStop(
    val key: String,
    val stationId: Int?,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val plannedArrivalMillis: Long?,
    val effectiveArrivalMillis: Long?,
    val effectiveDepartureMillis: Long?,
    val cancelled: Boolean = false,
    val isOrigin: Boolean = false,
    val isDestination: Boolean = false,
    val plannedDepartureMillis: Long? = null
)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timeMillis: Long,
    val speedMetersPerSecond: Double? = null
)

/** Persist the visit cursor, source establishment and spoken keys, never location history. */
data class TrackingProgress(
    val nextIndex: Int = 0,
    val nextStopKey: String? = null,
    val arrivedAtCurrent: Boolean = false,
    val announcedKeys: Set<String> = emptySet(),
    val completed: Boolean = false,
    val gpsEstablished: Boolean = false
)

data class TrackingUpdate(
    val stop: TrackingStop?,
    val source: TrackingSource,
    val announcement: TrackingStop? = null,
    val destinationReached: Boolean = false
)

/**
 * Ordered, local station tracking without Android or network dependencies.
 * GPS progress does not depend on scheduled times. The clock fallback can move
 * past timed visits before a GPS fix is available, or past visits lacking
 * coordinates, but cannot establish arrival at the destination.
 */
class StationTrackingEngine(
    stops: List<TrackingStop>,
    progress: TrackingProgress = TrackingProgress(),
    radiusMeters: Int = 0
) {
    private var route = stops.toList()
    private var state = progress.copy(announcedKeys = progress.announcedKeys.toSet())
    private var configuredRadius = normalizeRadius(radiusMeters)
    private var gpsEnabled = true
    private var lastReliableFix: LocationFix? = null
    // Retained across transient invalidation/toggles so queued old fixes cannot
    // become a new proof after location history has deliberately been cleared.
    private var latestAcceptedFixMillis: Long? = null
    // A GPS-established cursor is authoritative, including after restoration.
    // A clock-only cursor remains provisional until a safe GPS re-anchor.
    private var protectCoordinateCursor = progress.gpsEstablished || progress.arrivedAtCurrent
    private var initializationAttempted = protectCoordinateCursor
    private var mayBootstrapOrigin = progress.nextIndex == 0 && !progress.arrivedAtCurrent &&
        !progress.completed && route.firstOrNull()?.isOrigin == true
    private var observedKey: String? = null
    private var previousDistance: Double? = null
    private var minimumDistance: Double? = null
    private var approachConfirmed = false
    private var gapPassCandidateKey: String? = null
    private var insideArrivalSinceMillis: Long? = null
    private var insideArrivalFixCount = 0
    private var dwellAnchor: LocationFix? = null
    private var smoothedSpeed: Double? = null
    // A tunnel can cover more than one ordered visit. This evidence is local
    // and transient; restoring a cursor never restores a location proof.
    private var recoveryEligible = !progress.completed
    private var recoveryPending = false
    private var recoveryCandidate: RecoveryCandidate? = null
    private val releasedAnnouncements = mutableSetOf<String>()

    private data class RecoveryCandidate(
        val key: String,
        val firstFix: LocationFix,
        val latestFix: LocationFix,
        val count: Int
    )

    init {
        alignCursor()
    }

    @Synchronized
    fun updateRoute(stops: List<TrackingStop>) {
        val previousRoute = route
        val oldIndex = state.nextIndex
        val oldKey = state.nextStopKey
        val oldStop = currentStop()
        route = stops.toList()
        val retainedIndex = oldKey?.let { key -> route.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
        // If a refresh removes the current visit, retain the next surviving visit
        // from the old ordered route instead of matching another station by ID.
        val followingIndex = if (retainedIndex == null && oldKey != null) {
            previousRoute.drop(oldIndex + 1).firstNotNullOfOrNull { oldStop ->
                route.indexOfFirst { it.key == oldStop.key }.takeIf { it >= 0 }
            }
        } else null
        val index = retainedIndex ?: followingIndex ?: oldIndex.coerceIn(0, route.size)
        val newKey = route.getOrNull(index)?.key
        val newStop = route.getOrNull(index)
        val sameCoordinates = oldStop?.latitude == newStop?.latitude &&
            oldStop?.longitude == newStop?.longitude
        if (previousRoute.map(::recoveryIdentity) != route.map(::recoveryIdentity)) {
            recoveryCandidate = null
            recoveryEligible = !state.completed
        }
        state = state.copy(
            nextIndex = index,
            nextStopKey = newKey ?: if (route.isEmpty()) oldKey else null,
            arrivedAtCurrent = state.arrivedAtCurrent && oldKey == newKey && sameCoordinates
        )
        if (oldKey != newKey || !sameCoordinates) resetObservation()
        skipCancelled()
    }

    @Synchronized
    fun setRadiusMeters(radiusMeters: Int) {
        configuredRadius = normalizeRadius(radiusMeters)
    }

    /** User-selected clock mode is distinct from a temporary GPS outage. */
    @Synchronized
    fun setGpsEnabled(enabled: Boolean) {
        if (gpsEnabled == enabled) return
        gpsEnabled = enabled
        lastReliableFix = null
        smoothedSpeed = null
        state = state.copy(gpsEstablished = false, arrivedAtCurrent = false)
        protectCoordinateCursor = enabled
        initializationAttempted = false
        mayBootstrapOrigin = enabled
        recoveryEligible = enabled && !state.completed
        recoveryPending = false
        recoveryCandidate = null
        resetObservation()
    }

    /** Losing access to GPS is an outage, not a request to trust the schedule. */
    @Synchronized
    fun invalidateLocation() {
        lastReliableFix = null
        smoothedSpeed = null
        recoveryEligible = gpsEnabled && !state.completed
        recoveryCandidate = null
        resetObservation()
    }

    /** Retry an event if the service could not actually queue its speech. */
    @Synchronized
    fun releaseAnnouncement(key: String) {
        if (key in state.announcedKeys) {
            state = state.copy(announcedKeys = state.announcedKeys - key)
            releasedAnnouncements += key
        }
    }

    @Synchronized
    fun acknowledgeAnnouncement(key: String) {
        state = state.copy(announcedKeys = state.announcedKeys + key)
        releasedAnnouncements -= key
    }

    @Synchronized
    fun getProgress(): TrackingProgress = state.copy(announcedKeys = state.announcedKeys.toSet())

    @Synchronized
    fun hasReliableLocation(nowMillis: Long): Boolean =
        gpsEnabled && lastReliableFix?.let { isReliable(it, nowMillis) } == true

    /** A later physical visit is plausible, but its fresh proof is incomplete. */
    @Synchronized
    fun isReacquiringLocation(): Boolean = recoveryPending && !state.completed

    /** Revalidate delayed TTS work against the current visit, not its old reservation. */
    @Synchronized
    fun isOriginAnnouncementRelevant(key: String, source: TrackingSource, nowMillis: Long): Boolean {
        val stop = currentStop() ?: return false
        if (state.completed || recoveryPending || stop.cancelled || !stop.isOrigin || stop.key != key) return false
        val departure = stop.effectiveDepartureMillis ?: stop.effectiveArrivalMillis ?: return false
        if (departure < nowMillis || departure - nowMillis > TIMETABLE_ANNOUNCEMENT_MILLIS) return false
        val fix = lastReliableFix?.takeIf { gpsEnabled && isReliable(it, nowMillis) }
        // A pending clock event can outlive GPS becoming available. Fresh
        // movement evidence must win over the old event's source label.
        if (fix != null) return shouldAnnounceWaitingOrigin(stop, fix, nowMillis, requireUnannounced = false)
        return source == TrackingSource.TIMETABLE
    }

    @Synchronized
    fun onLocation(fix: LocationFix, nowMillis: Long): TrackingUpdate {
        if (!gpsEnabled) return onTimetable(nowMillis)
        // API/clock refreshes may replay the latest cached fix. They neither
        // add new evidence nor discard a still valid in-progress fix window.
        if (latestAcceptedFixMillis?.let { fix.timeMillis <= it } == true) return onTimetable(nowMillis)
        if (!isReliable(fix, nowMillis)) {
            recoveryCandidate = null
            return onTimetable(nowMillis)
        }

        val oldFix = lastReliableFix
        latestAcceptedFixMillis = fix.timeMillis
        // A gap invalidates the approach trend, not the persisted visit cursor.
        if (oldFix != null && fix.timeMillis - oldFix.timeMillis > MAX_FIX_AGE_MILLIS) {
            val candidate = currentStop()?.takeIf {
                !it.isDestination && approachConfirmed &&
                    (!it.isOrigin || state.arrivedAtCurrent)
            }?.key
            resetObservation()
            recoveryEligible = !state.completed
            recoveryCandidate = null
            gapPassCandidateKey = candidate
            observedKey = candidate
        }
        updateSpeed(fix, oldFix)
        lastReliableFix = fix
        protectCoordinateCursor = true
        if (!initializationAttempted) {
            val anchored = initializeAtMidwayStop(fix, nowMillis)
            // A clock-selected visit is provisional until location proves the
            // visit. A first fix between stations must not lock that selection
            // permanently and prevent a later safe near-station correction.
            if (anchored || state.nextIndex == 0) establishGpsCursor()
        }
        var advanced = false
        if (mayBootstrapOrigin && oldFix != null) {
            advanced = bootstrapDepartedOrigin(oldFix, fix)
            if (advanced) mayBootstrapOrigin = false
        }
        if (!advanced && oldFix != null) advanced = recoverPassedStopAfterGap(oldFix, fix)
        if (advanced) clearPendingRecovery()
        if (!advanced && recoverLaterVisit(fix, oldFix, nowMillis)) {
            // Selection is not an arrival. In particular, none of the missed
            // visits can contribute dwell, speech or destination completion.
            return observeCurrentStop(fix, null, nowMillis, canAdvance = false)
        }
        if (recoveryPending && !nearCurrentVisit(fix)) {
            return TrackingUpdate(currentStop(), TrackingSource.TIMETABLE)
        }
        return observeCurrentStop(fix, oldFix, nowMillis, canAdvance = !advanced)
    }

    private fun observeCurrentStop(
        fix: LocationFix,
        previousFix: LocationFix?,
        nowMillis: Long,
        canAdvance: Boolean
    ): TrackingUpdate {
        skipCancelled()
        if (state.completed) return finishedUpdate(TrackingSource.GPS)
        val stop = currentStop() ?: return TrackingUpdate(null, sourceForCurrent(nowMillis))
        if (!hasCoordinates(stop)) return onTimetable(nowMillis)

        if (observedKey != stop.key) {
            resetObservation()
            observedKey = stop.key
            // A freshly selected successor still has movement evidence in the
            // preceding fix. Reusing that pair avoids losing its announcement
            // when both stops fall within a single sampling interval.
            previousDistance = previousFix?.takeIf {
                fix.timeMillis - it.timeMillis in 1..MAX_FIX_AGE_MILLIS
            }?.let {
                distanceMeters(it.latitude, it.longitude, stop.latitude!!, stop.longitude!!)
            }
        }
        val distance = distanceMeters(fix.latitude, fix.longitude, stop.latitude!!, stop.longitude!!)
        val previous = previousDistance
        val approachChange = max(5.0, fix.accuracyMeters * 0.1)
        val approachingNow = previous != null && previous - distance >= approachChange
        if (approachingNow) {
            approachConfirmed = true
        }
        minimumDistance = minOf(minimumDistance ?: distance, distance)

        val retryPending = stop.key in releasedAnnouncements &&
            (approachConfirmed || state.arrivedAtCurrent)
        // Reserve an event only once we know this visit remains selected. A
        // departing visit must not consume the successor's one event per fix.
        val announcementCandidate = stop.takeIf {
            // Once boarding has been observed, a small inward GPS fluctuation
            // must not announce the origin again while the train is departing.
            (!stop.isOrigin || !state.arrivedAtCurrent) &&
                (approachingNow || retryPending) && distance <= announcementRadius() &&
                stop.key !in state.announcedKeys
        }

        // The accuracy circle must fit inside the arrival zone. The destination
        // uses a wider zone for station/platform geometry, plus slow movement
        // or stationary dwell; approaching it at speed does not finish a trip.
        val arrivalRadius = if (stop.isDestination) DESTINATION_ARRIVAL_RADIUS_METERS else ARRIVAL_RADIUS_METERS
        val insideArrival = distance + fix.accuracyMeters <= arrivalRadius
        updateArrivalObservations(fix, insideArrival)
        if (!stop.isDestination && insideArrival &&
            (approachConfirmed || stop.isOrigin || insideArrivalFixCount >= 2)
        ) {
            state = state.copy(arrivedAtCurrent = true)
            establishGpsCursor()
            confirmCurrentVisit()
        }
        val lowSpeed = fix.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it <= DESTINATION_MAX_SPEED_METERS_PER_SECOND } == true
        val dwelled = insideArrivalSinceMillis?.let {
            fix.timeMillis - it >= DESTINATION_DWELL_MILLIS
        } == true
        if (stop.isDestination && insideArrival && previous != null &&
            ((lowSpeed && insideArrivalFixCount >= 2) || dwelled)
        ) {
            state = state.copy(arrivedAtCurrent = true, completed = true)
            establishGpsCursor()
            confirmCurrentVisit()
            previousDistance = distance
            return TrackingUpdate(stop, TrackingSource.GPS,
                announcementCandidate?.let(::announce), destinationReached = true)
        }

        val rising = previous != null && distance > previous + approachChange
        val riseFromMinimum = distance - (minimumDistance ?: distance)
        val towardSuccessor = previousFix != null &&
            movingTowardSuccessor(stop, previousFix, fix, distance, riseFromMinimum)
        val leftArrival = !stop.isDestination && state.arrivedAtCurrent && rising &&
            (distance > DEPARTURE_RADIUS_METERS || towardSuccessor) &&
            riseFromMinimum >= DEPARTURE_INCREASE_METERS
        val fastPass = !stop.isOrigin && !stop.isDestination && approachConfirmed && rising &&
            (minimumDistance ?: Double.MAX_VALUE) <= FAST_PASS_RADIUS_METERS &&
            (towardSuccessor ||
                (distance > DEPARTURE_RADIUS_METERS && riseFromMinimum >= FAST_PASS_INCREASE_METERS))
        if (canAdvance && (leftArrival || fastPass)) {
            establishGpsCursor()
            advance()
            // Departure/pass establishes the old visit, not the successor.
            // Preserve an active outage recovery until the new visit itself
            // has a physical arrival proof; ordinary established trips keep
            // their already-disabled recovery eligibility.
            clearPendingRecovery()
            // One fix advances at most one non-cancelled visit, then assesses
            // that successor immediately. Close stops do not have to wait for
            // a fixed 220 m bubble around the preceding stop to be left.
            return observeCurrentStop(fix, previousFix, nowMillis, canAdvance = false)
        }
        previousDistance = distance
        val originAnnouncement = stop.takeIf {
            announcementCandidate == null && shouldAnnounceWaitingOrigin(it, fix, nowMillis)
        }
        return TrackingUpdate(stop, sourceForCurrent(nowMillis),
            (announcementCandidate ?: originAnnouncement)?.takeUnless { recoveryPending }?.let(::announce))
    }

    @Synchronized
    fun onTimetable(nowMillis: Long): TrackingUpdate {
        skipCancelled()
        if (state.completed) return finishedUpdate(sourceForCurrent(nowMillis))
        var stop = currentStop() ?: return TrackingUpdate(null, TrackingSource.TIMETABLE)
        if (recoveryPending) return TrackingUpdate(stop, TrackingSource.TIMETABLE)
        if (hasCoordinates(stop) && hasReliableLocation(nowMillis)) {
            val announcement = lastReliableFix?.takeIf {
                shouldAnnounceWaitingOrigin(stop, it, nowMillis)
            }?.let { announce(stop) }
            return TrackingUpdate(stop, sourceForCurrent(nowMillis), announcement)
        }
        // After GPS tracking starts, a tunnel or a lost fix must not make a
        // delayed train jump ahead merely because the schedule has elapsed.
        while ((!gpsEnabled || !hasCoordinates(stop) || !protectCoordinateCursor) && !stop.isDestination) {
            val endTime = stop.effectiveDepartureMillis ?: stop.effectiveArrivalMillis ?: break
            if (endTime >= nowMillis) break
            advance()
            skipCancelled()
            stop = currentStop() ?: return TrackingUpdate(null, TrackingSource.TIMETABLE)
            if (hasCoordinates(stop) && hasReliableLocation(nowMillis)) {
                return TrackingUpdate(stop, sourceForCurrent(nowMillis))
            }
        }
        val eventTime = if (stop.isOrigin) {
            stop.effectiveDepartureMillis ?: stop.effectiveArrivalMillis
        } else stop.effectiveArrivalMillis ?: stop.effectiveDepartureMillis
        val announcement = if (eventTime != null && eventTime >= nowMillis &&
            eventTime - nowMillis <= TIMETABLE_ANNOUNCEMENT_MILLIS &&
            stop.key !in state.announcedKeys
        ) announce(stop) else null
        return TrackingUpdate(stop, TrackingSource.TIMETABLE, announcement)
    }

    /** Departure-time advice remains useful while GPS proves waiting at the origin. */
    private fun shouldAnnounceWaitingOrigin(
        stop: TrackingStop,
        fix: LocationFix,
        nowMillis: Long,
        requireUnannounced: Boolean = true
    ): Boolean {
        if (recoveryPending || !stop.isOrigin || !state.arrivedAtCurrent ||
            (requireUnannounced && stop.key in state.announcedKeys) ||
            insideArrivalFixCount < 2 || !hasCoordinates(stop)
        ) return false
        val departure = stop.effectiveDepartureMillis ?: stop.effectiveArrivalMillis ?: return false
        if (departure < nowMillis || departure - nowMillis > TIMETABLE_ANNOUNCEMENT_MILLIS) return false
        val distance = distanceMeters(fix.latitude, fix.longitude, stop.latitude!!, stop.longitude!!)
        if (distance + fix.accuracyMeters > ARRIVAL_RADIUS_METERS) return false
        val stableSince = insideArrivalSinceMillis ?: return false
        val stableFor = fix.timeMillis - stableSince
        val lowSpeed = fix.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it <= ORIGIN_WAIT_MAX_SPEED_METERS_PER_SECOND } == true
        // The stable anchor resets on movement. Neither a first fix, reported
        // zero speed while driving away, nor elapsed timetable time suffices.
        return stableFor >= ORIGIN_WAIT_MIN_MILLIS &&
            (lowSpeed || (fix.speedMetersPerSecond == null && stableFor >= DESTINATION_DWELL_MILLIS))
    }

    private fun alignCursor() {
        if (route.isEmpty()) return
        val matchingIndex = state.nextStopKey?.let { key -> route.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
        val index = matchingIndex ?: state.nextIndex.coerceIn(0, route.size)
        state = state.copy(nextIndex = index, nextStopKey = route.getOrNull(index)?.key)
        skipCancelled()
    }

    private fun initializeAtMidwayStop(fix: LocationFix, nowMillis: Long): Boolean {
        if (state.gpsEstablished || state.completed || state.arrivedAtCurrent) return false
        val candidates = route.withIndex().filter { (_, stop) ->
            if (stop.cancelled || !hasCoordinates(stop)) return@filter false
            val arrival = stop.effectiveArrivalMillis ?: stop.plannedArrivalMillis
            val departure = stop.effectiveDepartureMillis ?: arrival
            val plausible = listOfNotNull(arrival, departure).any { time ->
                time <= nowMillis + MIDWAY_TIME_WINDOW_MILLIS
            }
            plausible && distanceMeters(fix.latitude, fix.longitude, stop.latitude!!, stop.longitude!!) +
                fix.accuracyMeters <= MIDWAY_RADIUS_METERS
        }
        // Never choose the globally nearest station: repeated visits and nearby
        // platforms may otherwise move the cursor to the wrong part of a loop.
        if (candidates.size == 1 && candidates.single().index <= state.nextIndex) {
            val candidate = candidates.single()
            state = state.copy(nextIndex = candidate.index, nextStopKey = candidate.value.key)
            mayBootstrapOrigin = candidate.index == 0 && candidate.value.isOrigin
            resetObservation()
            clearPendingRecovery()
            return true
        }
        return false
    }

    /** Confirm an ordered forward re-anchor without guessing a nearest visit. */
    private fun recoverLaterVisit(fix: LocationFix, previous: LocationFix?, nowMillis: Long): Boolean {
        if (!recoveryEligible || state.completed || fix.accuracyMeters > RECOVERY_MAX_ACCURACY_METERS ||
            fix.timeMillis > nowMillis
        ) {
            recoveryCandidate = null
            return false
        }
        // Include past/current visits: filtering them out would choose the
        // later occurrence of a loop station or a nearby parallel platform.
        val nearby = route.withIndex().filter { (_, stop) ->
            !stop.cancelled && hasCoordinates(stop) &&
                distanceMeters(fix.latitude, fix.longitude, stop.latitude!!, stop.longitude!!) +
                fix.accuracyMeters <= MIDWAY_RADIUS_METERS
        }
        val candidate = nearby.singleOrNull()?.takeIf { (index, stop) ->
            index > state.nextIndex && stop.key.isNotBlank() &&
                route.count { !it.cancelled && it.key == stop.key } == 1 &&
                route.subList(state.nextIndex, index).none { it.isDestination && !it.cancelled } &&
                (stop.stationId == null || route.count { !it.cancelled && it.stationId == stop.stationId } == 1)
        }
        if (candidate == null) {
            recoveryCandidate = null
            return false
        }
        recoveryPending = true
        val recentPrevious = previous?.takeIf { fix.timeMillis - it.timeMillis in 1..MAX_FIX_AGE_MILLIS }
        if (recentPrevious != null && !coherentRecoveryMovement(recentPrevious, fix)) {
            recoveryCandidate = null
            return false
        }
        val evidence = recoveryCandidate?.takeIf { it.key == candidate.value.key &&
            fix.timeMillis - it.latestFix.timeMillis in 1..MAX_FIX_AGE_MILLIS &&
            coherentRecoveryMovement(it.latestFix, fix)
        }
        val updated = if (evidence == null) RecoveryCandidate(candidate.value.key, fix, fix, 1)
            else evidence.copy(latestFix = fix, count = evidence.count + 1)
        recoveryCandidate = updated
        if (updated.count < RECOVERY_MIN_FIXES ||
            fix.timeMillis - updated.firstFix.timeMillis < RECOVERY_MIN_DURATION_MILLIS
        ) return false

        state = state.copy(nextIndex = candidate.index, nextStopKey = candidate.value.key,
            arrivedAtCurrent = false)
        mayBootstrapOrigin = false
        resetObservation()
        establishGpsCursor()
        // The selected visit may itself already be behind the vehicle. Keep
        // reacquisition armed until an actual arrival or ordered pass proves
        // the new section, otherwise a second tunnel stop could pin it again.
        clearPendingRecovery()
        return true
    }

    private fun coherentRecoveryMovement(previous: LocationFix, fix: LocationFix): Boolean {
        val elapsed = fix.timeMillis - previous.timeMillis
        if (elapsed !in 1..MAX_FIX_AGE_MILLIS) return false
        val movement = distanceMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
        return movement <= RECOVERY_MAX_SPEED_METERS_PER_SECOND * elapsed / 1000.0 +
            previous.accuracyMeters + fix.accuracyMeters
    }

    private fun nearCurrentVisit(fix: LocationFix): Boolean = currentStop()?.let { stop ->
        hasCoordinates(stop) && distanceMeters(fix.latitude, fix.longitude,
            stop.latitude!!, stop.longitude!!) + fix.accuracyMeters <= MIDWAY_RADIUS_METERS
    } == true

    private fun recoveryIdentity(stop: TrackingStop): List<Any?> = listOf(
        stop.key, stop.stationId, stop.latitude, stop.longitude, stop.cancelled, stop.isDestination
    )

    private fun confirmCurrentVisit() {
        recoveryEligible = false
        clearPendingRecovery()
    }

    private fun clearPendingRecovery() {
        recoveryPending = false
        recoveryCandidate = null
    }

    private fun bootstrapDepartedOrigin(previous: LocationFix, fix: LocationFix): Boolean {
        if (state.nextIndex != 0 || state.arrivedAtCurrent || state.completed ||
            fix.timeMillis - previous.timeMillis > MAX_FIX_AGE_MILLIS
        ) return false
        val origin = currentStop() ?: return false
        if (!origin.isOrigin || !hasCoordinates(origin)) return false
        val originLatitude = origin.latitude ?: return false
        val originLongitude = origin.longitude ?: return false
        val next = route.drop(1).firstOrNull { !it.cancelled } ?: return false
        if (!hasCoordinates(next)) return false
        val nextLatitude = next.latitude ?: return false
        val nextLongitude = next.longitude ?: return false
        val fromOrigin = distanceMeters(fix.latitude, fix.longitude, originLatitude, originLongitude)
        val priorFromOrigin = distanceMeters(previous.latitude, previous.longitude, originLatitude, originLongitude)
        val toNext = distanceMeters(fix.latitude, fix.longitude, nextLatitude, nextLongitude)
        val priorToNext = distanceMeters(previous.latitude, previous.longitude, nextLatitude, nextLongitude)
        val betweenStops = distanceMeters(originLatitude, originLongitude, nextLatitude, nextLongitude)
        val supportedMovement = max(DEPARTURE_INCREASE_METERS, fix.accuracyMeters + previous.accuracyMeters)
        val trendChange = max(5.0, fix.accuracyMeters * 0.1)
        val departureMovement = max(fromOrigin - priorFromOrigin,
            fromOrigin - (minimumDistance ?: priorFromOrigin))
        if ((fromOrigin > DEPARTURE_RADIUS_METERS + fix.accuracyMeters ||
                toNext + 2 * fix.accuracyMeters < fromOrigin) &&
            departureMovement >= supportedMovement && fromOrigin - priorFromOrigin >= trendChange &&
            priorToNext - toNext >= trendChange &&
            toNext < betweenStops && fromOrigin + toNext <= betweenStops + MIDWAY_CORRIDOR_MARGIN_METERS
        ) {
            establishGpsCursor()
            advance()
            return true
        }
        return false
    }

    private fun updateArrivalObservations(fix: LocationFix, insideArrival: Boolean) {
        if (!insideArrival) {
            insideArrivalSinceMillis = null
            insideArrivalFixCount = 0
            dwellAnchor = null
            return
        }
        insideArrivalFixCount++
        val anchor = dwellAnchor
        val stable = anchor != null && distanceMeters(anchor.latitude, anchor.longitude,
            fix.latitude, fix.longitude) <= max(20.0, min(30.0, min(anchor.accuracyMeters, fix.accuracyMeters)))
        if (!stable) {
            dwellAnchor = fix
            insideArrivalSinceMillis = fix.timeMillis
        }
    }

    private fun recoverPassedStopAfterGap(previous: LocationFix, fix: LocationFix): Boolean {
        val stop = currentStop() ?: return false
        if (gapPassCandidateKey != stop.key || stop.isDestination ||
            fix.timeMillis - previous.timeMillis > MAX_FIX_AGE_MILLIS
        ) return false
        val next = route.drop(state.nextIndex + 1).firstOrNull { !it.cancelled } ?: return false
        if (!hasCoordinates(stop) || !hasCoordinates(next)) return false
        val latitude = stop.latitude ?: return false
        val longitude = stop.longitude ?: return false
        val nextLatitude = next.latitude ?: return false
        val nextLongitude = next.longitude ?: return false
        val fromStop = distanceMeters(fix.latitude, fix.longitude, latitude, longitude)
        val priorFromStop = distanceMeters(previous.latitude, previous.longitude, latitude, longitude)
        val toNext = distanceMeters(fix.latitude, fix.longitude, nextLatitude, nextLongitude)
        val priorToNext = distanceMeters(previous.latitude, previous.longitude, nextLatitude, nextLongitude)
        val betweenStops = distanceMeters(latitude, longitude, nextLatitude, nextLongitude)
        val movement = max(DEPARTURE_INCREASE_METERS, fix.accuracyMeters + previous.accuracyMeters)
        val trendChange = max(5.0, fix.accuracyMeters * 0.1)
        val departureMovement = max(fromStop - priorFromStop,
            fromStop - (minimumDistance ?: priorFromStop))
        if ((fromStop > DEPARTURE_RADIUS_METERS + fix.accuracyMeters ||
                toNext + 2 * fix.accuracyMeters < fromStop) &&
            departureMovement >= movement && fromStop - priorFromStop >= trendChange &&
            priorToNext - toNext >= trendChange &&
            toNext < betweenStops && fromStop + toNext <= betweenStops + MIDWAY_CORRIDOR_MARGIN_METERS
        ) {
            establishGpsCursor()
            advance()
            return true
        }
        return false
    }

    /** Ordered-neighbour evidence handles overlapping bus-stop departure zones. */
    private fun movingTowardSuccessor(
        stop: TrackingStop,
        previous: LocationFix,
        fix: LocationFix,
        fromStop: Double,
        riseFromMinimum: Double
    ): Boolean {
        if (fix.timeMillis - previous.timeMillis !in 1..MAX_FIX_AGE_MILLIS) return false
        val next = route.drop(state.nextIndex + 1).firstOrNull { !it.cancelled } ?: return false
        if (!hasCoordinates(stop) || !hasCoordinates(next)) return false
        val nextLatitude = next.latitude ?: return false
        val nextLongitude = next.longitude ?: return false
        val toNext = distanceMeters(fix.latitude, fix.longitude, nextLatitude, nextLongitude)
        val priorToNext = distanceMeters(previous.latitude, previous.longitude, nextLatitude, nextLongitude)
        val supportedMovement = max(DEPARTURE_INCREASE_METERS,
            (fix.accuracyMeters + previous.accuracyMeters) / 2)
        // A decreasing distance alone can be GPS jitter or a nearby parallel
        // platform. Require supported movement and an unambiguous next stop.
        val approachingNext = priorToNext - toNext >= max(5.0, fix.accuracyMeters * 0.1)
        // Small, frequent fixes can accumulate the departure evidence from the
        // closest observed position. A sparse pair can provide it directly.
        return approachingNext && max(priorToNext - toNext, riseFromMinimum) >= supportedMovement &&
            toNext + 2 * fix.accuracyMeters < fromStop
    }

    private fun currentStop(): TrackingStop? = route.getOrNull(state.nextIndex)

    private fun establishGpsCursor() {
        state = state.copy(gpsEstablished = true)
        initializationAttempted = true
    }

    private fun skipCancelled() {
        while (!state.completed && currentStop()?.cancelled == true) advance()
    }

    private fun advance() {
        val index = (state.nextIndex + 1).coerceAtMost(route.size)
        state = state.copy(nextIndex = index, nextStopKey = route.getOrNull(index)?.key, arrivedAtCurrent = false)
        resetObservation()
    }

    private fun resetObservation() {
        observedKey = null
        previousDistance = null
        minimumDistance = null
        approachConfirmed = false
        gapPassCandidateKey = null
        insideArrivalSinceMillis = null
        insideArrivalFixCount = 0
        dwellAnchor = null
    }

    private fun announce(stop: TrackingStop): TrackingStop {
        state = state.copy(announcedKeys = state.announcedKeys + stop.key)
        releasedAnnouncements -= stop.key
        return stop
    }

    private fun sourceForCurrent(nowMillis: Long): TrackingSource =
        if (!recoveryPending && state.gpsEstablished && currentStop()?.let(::hasCoordinates) == true && hasReliableLocation(nowMillis)) {
            TrackingSource.GPS
        } else TrackingSource.TIMETABLE

    private fun finishedUpdate(source: TrackingSource): TrackingUpdate =
        TrackingUpdate(currentStop(), source, destinationReached = true)

    private fun updateSpeed(fix: LocationFix, previous: LocationFix?) {
        val measured = fix.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 }
            ?: previous?.takeIf { fix.timeMillis > it.timeMillis &&
                fix.timeMillis - it.timeMillis <= MAX_FIX_AGE_MILLIS
            }?.let {
                distanceMeters(it.latitude, it.longitude, fix.latitude, fix.longitude) /
                    ((fix.timeMillis - it.timeMillis) / 1000.0)
            }
        if (measured != null) {
            smoothedSpeed = smoothedSpeed?.let { it * 0.65 + measured * 0.35 } ?: measured
        }
    }

    private fun announcementRadius(): Double = if (configuredRadius > 0) configuredRadius.toDouble()
        else ((smoothedSpeed ?: 0.0) * 45.0).coerceIn(300.0, 2000.0)

    private fun isReliable(fix: LocationFix, nowMillis: Long): Boolean =
        validCoordinates(fix.latitude, fix.longitude) && fix.accuracyMeters.isFinite() &&
            fix.accuracyMeters in 0.0..MAX_ACCURACY_METERS &&
            fix.timeMillis >= nowMillis - MAX_FIX_AGE_MILLIS &&
            fix.timeMillis <= nowMillis + MAX_FUTURE_FIX_MILLIS

    private fun hasCoordinates(stop: TrackingStop): Boolean = stop.latitude != null &&
        stop.longitude != null && validCoordinates(stop.latitude, stop.longitude)

    private fun normalizeRadius(radius: Int): Int = if (radius in setOf(300, 500, 1000, 2000)) radius else 0

    companion object {
        const val MAX_FIX_AGE_MILLIS = 30_000L
        const val MAX_ACCURACY_METERS = 100.0
        const val ARRIVAL_RADIUS_METERS = 120.0
        const val DESTINATION_ARRIVAL_RADIUS_METERS = 300.0
        private const val MAX_FUTURE_FIX_MILLIS = 5_000L
        private const val DEPARTURE_RADIUS_METERS = 220.0
        private const val DEPARTURE_INCREASE_METERS = 35.0
        private const val FAST_PASS_RADIUS_METERS = 300.0
        private const val FAST_PASS_INCREASE_METERS = 80.0
        private const val TIMETABLE_ANNOUNCEMENT_MILLIS = 180_000L
        private const val MIDWAY_RADIUS_METERS = 150.0
        private const val MIDWAY_TIME_WINDOW_MILLIS = 600_000L
        private const val MIDWAY_CORRIDOR_MARGIN_METERS = 300.0
        private const val RECOVERY_MAX_ACCURACY_METERS = 75.0
        private const val RECOVERY_MAX_SPEED_METERS_PER_SECOND = 100.0
        private const val RECOVERY_MIN_FIXES = 3
        private const val RECOVERY_MIN_DURATION_MILLIS = 6_000L
        private const val DESTINATION_MAX_SPEED_METERS_PER_SECOND = 3.0
        private const val DESTINATION_DWELL_MILLIS = 10_000L
        private const val ORIGIN_WAIT_MAX_SPEED_METERS_PER_SECOND = 1.5
        private const val ORIGIN_WAIT_MIN_MILLIS = 3_000L
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_METERS * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
