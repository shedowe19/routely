package de.traewelling.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.util.NotificationHelper
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.data.model.*
import java.time.Duration
import java.time.ZonedDateTime

class TripMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val activeCheckinId = prefs.getActiveCheckinId()?.toIntOrNull() ?: return Result.success()
        val lastNotifiedTripId = prefs.getLastNotifiedTripId()
        
        // If we already notified for this check-in, we are done
        if (activeCheckinId.toString() == lastNotifiedTripId) return Result.success()

        val token = prefs.getAccessToken() ?: return Result.success()
        val baseUrl = prefs.getServerUrl()
        val api = RetrofitClient.createApiService(baseUrl, token)
        val helper = NotificationHelper(applicationContext)

        try {
            val response = api.getStatus(activeCheckinId)
            if (response.isSuccessful) {
                val status = response.body()?.data
                val checkin = status?.checkin
                val targetStop: de.traewelling.app.data.model.StopStation = checkin?.destination ?: return Result.success()
                
                val arrivalTimeStr = targetStop.arrivalReal ?: targetStop.arrivalPlanned
                if (arrivalTimeStr != null) {
                    val arrivalTime = ZonedDateTime.parse(arrivalTimeStr)
                    val now = ZonedDateTime.now()
                    
                    val durationToArrival = Duration.between(now, arrivalTime)
                    val minutesToArrival = durationToArrival.toMinutes()
                    
                    // Notify if arrival is in less than 10 minutes but more than -5 minutes (not yet arrived too long ago)
                    if (minutesToArrival in -5..10) {
                        val platform = targetStop.arrivalPlatformReal ?: targetStop.arrivalPlatformPlanned ?: targetStop.platform
                        val platformInfo = if (!platform.isNullOrBlank()) " auf Gleis $platform" else ""
                        
                        helper.showTripNotification(
                            title = "Ankunft in Kürze",
                            message = "Du erreichst bald ${targetStop.name}$platformInfo. Gute Reise!"
                        )
                        
                        // Mark as notified for this trip
                        prefs.saveLastNotifiedTripId(activeCheckinId.toString())
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
