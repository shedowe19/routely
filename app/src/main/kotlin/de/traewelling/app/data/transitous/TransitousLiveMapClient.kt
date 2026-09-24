package de.traewelling.app.data.transitous

import de.traewelling.app.BuildConfig
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

internal interface TransitousMapApi {
    @GET("api/v6/map/trips")
    suspend fun trips(
        @Query("zoom") zoom: Double,
        @Query("min") min: String,
        @Query("max") max: String,
        @Query("startTime") startTime: String,
        @Query("endTime") endTime: String,
        @Query("precision") precision: Int,
        @Query("language") language: String,
    ): Response<List<TransitousTripSegmentDto>>
}

/**
 * Single-shot, bounded request. The caller controls when to refresh; call only
 * for a visible map and avoid frequent polling on the community-run service.
 */
class TransitousLiveMapClient(httpClient: OkHttpClient = defaultHttpClient()) {
    private val api: TransitousMapApi = Retrofit.Builder()
        .baseUrl("https://api.transitous.org/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TransitousMapApi::class.java)

    suspend fun fetchMarkers(
        bounds: TransitousBounds,
        zoom: Double,
        at: Instant = Instant.now(),
        maxMarkers: Int = 300,
    ): Result<List<TransitousVehicleMarker>> {
        if (!bounds.isValid()) {
            return Result.failure(IllegalArgumentException("Invalid visible map bounds"))
        }
        if (!zoom.isFinite() || zoom !in 0.0..22.0) {
            return Result.failure(IllegalArgumentException("Invalid map zoom level"))
        }
        if (maxMarkers !in 1..500) {
            return Result.failure(IllegalArgumentException("maxMarkers must be between 1 and 500"))
        }

        val limitedBounds = bounds.limited()
        val precision = when {
            zoom >= 11.0 -> 5
            zoom >= 8.0 -> 4
            zoom >= 5.0 -> 3
            else -> 2
        }
        return try {
            val response = api.trips(
                zoom = zoom,
                min = "${limitedBounds.south},${limitedBounds.west}",
                max = "${limitedBounds.north},${limitedBounds.east}",
                startTime = at.minusSeconds(90).toString(),
                endTime = at.plusSeconds(90).toString(),
                precision = precision,
                language = "de",
            )
            if (!response.isSuccessful) {
                Result.failure(TransitousApiException(response.code()))
            } else {
                val body = response.body()
                if (body == null) {
                    Result.failure(IllegalStateException("Transitous returned no map data"))
                } else {
                    Result.success(
                        TransitousVehicleEstimator.estimate(body, at, bounds, precision, maxMarkers)
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        // Transitous requires app/version/contact in User-Agent.
                        .header(
                            "User-Agent",
                            "Routely/${BuildConfig.VERSION_NAME} (Android; +https://github.com/shedowe19/routely)",
                        )
                        .header("Accept", "application/json")
                        .build()
                )
            }
            .build()
    }
}
