package de.traewelling.app.data.api

import de.traewelling.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface TraewellingApiService {

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @GET("api/v1/auth/user")
    suspend fun getAuthUser(): Response<UserResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    // ─── Feed ─────────────────────────────────────────────────────────────────

    @GET("api/v1/dashboard")
    suspend fun getDashboard(@Query("page") page: Int = 1): Response<StatusListResponse>

    @GET("api/v1/statuses")
    suspend fun getGlobalFeed(@Query("page") page: Int = 1): Response<StatusListResponse>

    // ─── Status (singular path!) ──────────────────────────────────────────────
    // API uses /status/{id} (singular) for single-status operations!

    @GET("api/v1/status/{id}")
    suspend fun getStatus(@Path("id") id: Int): Response<SingleStatusResponse>

    @DELETE("api/v1/status/{id}")
    suspend fun deleteStatus(@Path("id") id: Int): Response<Unit>
    
    @PUT("api/v1/status/{id}")
    suspend fun updateStatus(
        @Path("id") id: Int,
        @Body request: UpdateStatusRequest
    ): Response<SingleStatusResponse>

    @POST("api/v1/status/{id}/like")
    suspend fun likeStatus(@Path("id") id: Int): Response<Unit>

    @DELETE("api/v1/status/{id}/like")
    suspend fun unlikeStatus(@Path("id") id: Int): Response<Unit>

    // ─── Station search ───────────────────────────────────────────────────────

    @GET("api/v1/trains/station/autocomplete/{query}")
    suspend fun searchStations(
        @Path("query") query: String
    ): Response<StationSearchResponse>

    @GET("api/v1/trains/station/nearby")
    suspend fun getNearbyStations(
        @Query("latitude")  lat: Double,
        @Query("longitude") lon: Double
    ): Response<StationSearchResponse>

    // ─── Departures — uses numeric station ID, NOT the station name! ──────────

    @GET("api/v1/station/{id}/departures")
    suspend fun getStationDepartures(
        @Path("id")          stationId: Int,
        @Query("when")       whenTime: String? = null,
        @Query("travelType") travelType: String? = null
    ): Response<DepartureResponse>

    // ─── Full trip with stopovers (to pick a destination) ─────────────────────

    @GET("api/v1/trains/trip")
    suspend fun getTrip(
        @Query("hafasTripId") hafasTripId: String,
        @Query("lineName")    lineName: String
    ): Response<TripResponse>

    // ─── Check-in ─────────────────────────────────────────────────────────────

    @POST("api/v1/trains/checkin")
    suspend fun checkIn(@Body request: CheckInRequest): Response<CheckInResponse>

    // ─── Statistics ───────────────────────────────────────────────────────────

    @GET("api/v1/statistics")
    suspend fun getStatistics(): Response<StatisticsResponse>

    // ─── User Profile ─────────────────────────────────────────────────────────

    @GET("api/v1/user/{username}")
    suspend fun getUserProfile(@Path("username") username: String): Response<UserResponse>

    @GET("api/v1/user/{username}/statuses")
    suspend fun getUserStatuses(
        @Path("username") username: String,
        @Query("page")    page: Int = 1
    ): Response<StatusListResponse>

    // ─── Stopovers (for trip detail view) ─────────────────────────────────────

    @GET("api/v1/stopovers/{tripId}")
    suspend fun getStopovers(@Path("tripId") tripId: Int): Response<StopoversResponse>

    // ─── Follow / Unfollow ────────────────────────────────────────────────────

    @POST("api/v1/user/{id}/follow")
    suspend fun followUser(@Path("id") userId: Int): Response<Unit>

    @DELETE("api/v1/user/{id}/follow")
    suspend fun unfollowUser(@Path("id") userId: Int): Response<Unit>

    // ─── Notifications ────────────────────────────────────────────────────────

    @GET("api/v1/notifications")
    suspend fun getNotifications(@Query("page") page: Int = 1): Response<NotificationListResponse>

    @GET("api/v1/notifications/unread/count")
    suspend fun getUnreadNotificationCount(): Response<UnreadCountResponse>

    @PUT("api/v1/notifications/read/{id}")
    suspend fun markNotificationRead(@Path("id") id: String): Response<Unit>

    @PUT("api/v1/notifications/read/all")
    suspend fun markAllNotificationsRead(): Response<Unit>
}

// ─── OAuth Token Exchange ─────────────────────────────────────────────────────

interface OAuthApiService {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun exchangeToken(
        @Field("grant_type")    grantType: String = "authorization_code",
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String? = null,
        @Field("redirect_uri")  redirectUri: String,
        @Field("code")          code: String,
        @Field("code_verifier") codeVerifier: String? = null
    ): Response<de.traewelling.app.data.model.OAuthTokenResponse>

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun refreshToken(
        @Field("grant_type")    grantType: String = "refresh_token",
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String? = null,
        @Field("refresh_token") refreshToken: String
    ): Response<de.traewelling.app.data.model.OAuthTokenResponse>
}
