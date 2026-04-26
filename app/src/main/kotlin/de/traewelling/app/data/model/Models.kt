package de.traewelling.app.data.model

import com.google.gson.annotations.SerializedName

// ─── Auth ────────────────────────────────────────────────────────────────────

data class OAuthTokenResponse(
    @SerializedName("access_token")  val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("token_type")    val tokenType: String?,
    @SerializedName("expires_in")    val expiresIn: Int?
)

// ─── User ─────────────────────────────────────────────────────────────────────
// Actual API fields from GET /api/v1/auth/user and GET /api/v1/user/{username}

data class UserResponse(
    val data: User?
)

data class User(
    val id: Int?,
    val uuid: String?,
    val username: String,
    @SerializedName("displayName")   val displayName: String?,
    @SerializedName("profilePicture") val profilePicture: String?,   // NOT "avatar"
    val bio: String?,
    @SerializedName("privateProfile") val privateProfile: Boolean?,
    /** Total distance in metres (from profile) */
    @SerializedName("totalDistance") val totalDistance: Long?,
    /** Total duration in minutes (from profile) */
    @SerializedName("totalDuration") val totalDuration: Int?,
    val points: Int?,
    // Social — only present on other-user profile responses
    val following: Boolean?,
    @SerializedName("followPending") val followPending: Boolean?,
    @SerializedName("followedBy") val followedBy: Boolean?,
    val muted: Boolean?,
    val blocked: Boolean?,
    @SerializedName("userInvisibleToMe") val userInvisibleToMe: Boolean?,
    @SerializedName("pointsEnabled") val pointsEnabled: Boolean?,
    @SerializedName("mastodonUrl") val mastodonUrl: String?
)

// ─── Status list ─────────────────────────────────────────────────────────────
// Actual API fields from GET /api/v1/dashboard and GET /api/v1/statuses

data class StatusListResponse(
    val data: List<Status>?,
    val links: PaginationLinks?,
    val meta: PaginationMeta?
)

data class SingleStatusResponse(
    val data: Status?
)

data class Status(
    val id: Int,
    val body: String?,
    @SerializedName("createdAt") val createdAt: String?,     // camelCase in API!
    @SerializedName("likes")     val likes: Int?,            // NOT "likes_count"
    val liked: Boolean?,
    @SerializedName("isLikable") val isLikable: Boolean?,
    val visibility: Int?,
    val business: Int?,
    val user: StatusUser?,
    val checkin: CheckinInfo?,                                // NOT "train"
    val event: StatusEvent?,
    val tags: List<StatusTag>?
)

data class StatusUser(
    val id: Int?,
    val uuid: String?,
    val username: String?,
    @SerializedName("displayName")    val displayName: String?,
    @SerializedName("profilePicture") val profilePicture: String?  // NOT "avatar"
)

data class StatusEvent(
    val id: Int?,
    val name: String?,
    val slug: String?
)

data class StatusTag(
    val key: String?,
    val value: String?,
    val visibility: Int?
)

/** The checkin sub-object inside a Status. Maps to JSON key "checkin". */
data class CheckinInfo(
    @SerializedName("hafasId")  val hafasId: String?,
    val category: String?,
    val mode: String?,
    val lineName: String?,
    @SerializedName("distance") val distanceMeters: Int?,
    val points: Int?,
    val duration: Int?,          // minutes
    val origin: StopStation?,
    val destination: StopStation?,
    val operator: StopOperator?,
    val trip: Int?,
    val number: String?,
    @SerializedName("routeColor") val routeColor: String?,
    @SerializedName("routeTextColor") val routeTextColor: String?,
    @SerializedName("journeyNumber") val journeyNumber: Int?,
    @SerializedName("manualDeparture") val manualDeparture: String?,
    @SerializedName("manualArrival") val manualArrival: String?
)

data class StopOperator(
    val id: Int?,
    val name: String?,
    val uuid: String?
)

// ─── Flat stop / station object ───────────────────────────────────────────────
// Used in: checkin.origin/destination, trip stopovers, and autocomplete

data class TrainStation(
    val id: Int?,
    val ibnr: Long?,
    val name: String?,
    @SerializedName("rilIdentifier") val rilIdentifier: String?,
    val latitude: Double?,
    val longitude: Double?
)

/** Flat stop with schedule times — used in checkin origin/destination AND as trip stopover */
data class StopStation(
    val id: Int?,
    val name: String?,
    @SerializedName("rilIdentifier")    val rilIdentifier: String?,
    @SerializedName("evaIdentifier")    val evaIdentifier: String?,
    val arrival: String?,
    @SerializedName("arrivalPlanned")   val arrivalPlanned: String?,
    @SerializedName("arrivalReal")      val arrivalReal: String?,
    val departure: String?,
    @SerializedName("departurePlanned") val departurePlanned: String?,
    @SerializedName("departureReal")    val departureReal: String?,
    val platform: String?,
    @SerializedName("arrivalPlatformPlanned")   val arrivalPlatformPlanned: String?,
    @SerializedName("arrivalPlatformReal")      val arrivalPlatformReal: String?,
    @SerializedName("departurePlatformPlanned") val departurePlatformPlanned: String?,
    @SerializedName("departurePlatformReal")    val departurePlatformReal: String?,
    val cancelled: Boolean?,
    @SerializedName("isArrivalDelayed")   val isArrivalDelayed: Boolean?,
    @SerializedName("isDepartureDelayed") val isDepartureDelayed: Boolean?
)

// ─── Station Search ───────────────────────────────────────────────────────────

data class StationSearchResponse(
    val data: List<TrainStation>?
)

data class SingleStationResponse(
    val data: TrainStation?
)

// ─── Departures (GET /api/v1/station/{id}/departures) ────────────────────────

data class DepartureResponse(
    val data: List<DepartureTrip>?
)

data class DepartureTrip(
    val tripId: String,
    val line: HafasLine?,
    val direction: String?,
    @SerializedName("plannedWhen") val plannedWhen: String?,
    @SerializedName("when")        val realWhen: String?,
    val delay: Int?,               // minutes
    val platform: String?,
    @SerializedName("plannedPlatform") val plannedPlatform: String?,
    val cancelled: Boolean?
)

data class HafasLine(
    val name: String?,
    @SerializedName("fahrtNr") val fahrtNr: String?,
    val product: String?,          // "nationalExpress", "regional", "suburban", "bus", etc.
    val mode: String?,
    val operator: HafasOperator?
)

data class HafasOperator(
    val name: String?
)

// ─── Trip Details (GET /api/v1/trains/trip) ───────────────────────────────────
// Stopovers are flat StopStation objects (no nested "stop" object!)

data class TripResponse(
    val data: TripDetails?
)

data class TripDetails(
    val id: Int?,
    val lineName: String?,
    val category: String?,
    /** Each item is a flat StopStation — id and name are at top level */
    val stopovers: List<StopStation>?
)

// ─── Check-in Request ─────────────────────────────────────────────────────────

data class CheckInRequest(
    @SerializedName("tripId")      val tripId: String,
    @SerializedName("lineName")    val lineName: String,
    @SerializedName("start")       val startStationId: Int,
    @SerializedName("destination") val destinationStationId: Int,
    @SerializedName("departure")   val departure: String,
    @SerializedName("arrival")     val arrival: String,
    @SerializedName("body")        val body: String? = null,
    @SerializedName("visibility")  val visibility: Int = 0
)

data class CheckInResponse(
    val data: CheckInResult?
)

data class UpdateStatusRequest(
    @SerializedName("body")            val body: String? = null,
    @SerializedName("visibility")      val visibility: Int? = null,
    @SerializedName("business")        val business: Int? = null,
    @SerializedName("destinationId")   val destination: Int? = null,
    @SerializedName("manualDeparture") val departure: String? = null,
    @SerializedName("manualArrival")   val arrival: String? = null
)

data class CheckInResult(
    val status: Status?,
    val points: CheckInPoints?
)

data class CheckInPoints(
    val points: Int?,
    val calculation: PointsCalculation?
)

data class PointsCalculation(
    val base: Int?,
    val bonus: Int?,
    val distance: Int?,
    val factor: Double?
)

// ─── Statistics (GET /api/v1/statistics) ─────────────────────────────────────
// API returns category/operator/time breakdown — NOT simple totals

data class StatisticsResponse(
    val data: StatisticsData?
)

data class StatisticsData(
    val categories: List<StatEntry>?,
    val operators: List<StatEntry>?,
    val time: List<StatDay>?,
    val purpose: List<StatEntry>?
)

data class StatEntry(
    val name: String?,
    val count: Int?,
    val duration: Int?     // minutes
)

data class StatDay(
    val date: String?,
    val count: Int?,
    val duration: Int?
)

// ─── Stopovers (GET /api/v1/stopovers/{tripId}) ──────────────────────────────
// API returns { "data": { "tripId": [StopStation, ...] } } — a Map, not a List

data class StopoversResponse(
    val data: Map<String, List<StopStation>>?
) {
    /** Convenience: flatten all stopovers regardless of trip key. */
    fun allStopovers(): List<StopStation> =
        data?.values?.flatten() ?: emptyList()
}

/**
 * Deduplicates a list of stops. The API sometimes returns duplicate consecutive stops
 * (e.g. "Nettetal Kaldenkirchen Bf" and "Kaldenkirchen" with the same times).
 * This function merges consecutive stops with matching planned times, preferring the
 * entry that has platform information or a shorter name.
 */
fun List<StopStation>.deduplicate(): List<StopStation> {
    if (isEmpty()) return this

    val result = mutableListOf<StopStation>()
    for (stop in this) {
        val last = result.lastOrNull()

        // Only merge if both stops have some planned times and they match exactly
        val hasTimes = stop.arrivalPlanned != null || stop.departurePlanned != null
        val arrivalMatch = last?.arrivalPlanned == stop.arrivalPlanned
        val departureMatch = last?.departurePlanned == stop.departurePlanned

        if (last != null && hasTimes && arrivalMatch && departureMatch) {
            val lastHasPlatform = last.platform != null || last.arrivalPlatformPlanned != null || last.departurePlatformPlanned != null
            val stopHasPlatform = stop.platform != null || stop.arrivalPlatformPlanned != null || stop.departurePlatformPlanned != null

            if (!lastHasPlatform && stopHasPlatform) {
                // Replace with the one that has a platform
                result[result.size - 1] = stop
            } else if (lastHasPlatform == stopHasPlatform) {
                // If both or neither have platform, prefer the shorter name
                val lastNameLen = last.name?.length ?: Int.MAX_VALUE
                val stopNameLen = stop.name?.length ?: Int.MAX_VALUE
                if (stopNameLen < lastNameLen) {
                    result[result.size - 1] = stop
                }
            }
        } else {
            result.add(stop)
        }
    }
    return result
}

// ─── Notifications ────────────────────────────────────────────────────────────

data class NotificationListResponse(
    val data: List<Notification>?,
    val links: PaginationLinks?,
    val meta: PaginationMeta?
)

data class Notification(
    val id: String,                                           // UUID string
    val type: String?,                                        // e.g. "StatusLiked", "UserJoinedConnection"
    val lead: String?,                                        // plain-text title
    @SerializedName("leadFormatted") val leadFormatted: String?,  // HTML title
    val notice: String?,                                      // plain-text detail
    @SerializedName("noticeFormatted") val noticeFormatted: String?,
    val link: String?,                                        // e.g. "https://traewelling.de/status/123"
    @SerializedName("readAt") val readAt: String?,            // null if unread
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("createdAtForHumans") val createdAtForHumans: String?
)

data class UnreadCountResponse(
    val data: Int?
)

// ─── Pagination ───────────────────────────────────────────────────────────────

data class PaginationLinks(
    val first: String?,
    val last: String?,
    val prev: String?,
    val next: String?
)

data class PaginationMeta(
    @SerializedName("current_page") val currentPage: Int?,
    @SerializedName("last_page")    val lastPage: Int?,
    @SerializedName("total")        val total: Int?,
    @SerializedName("per_page")     val perPage: Int?
)
