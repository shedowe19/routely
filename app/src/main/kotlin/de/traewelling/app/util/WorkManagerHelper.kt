package de.traewelling.app.util

import android.content.Context
import androidx.work.*
import de.traewelling.app.worker.NotificationSyncWorker
import de.traewelling.app.worker.TripMonitorWorker
import java.util.concurrent.TimeUnit

object WorkManagerHelper {

    private const val NOTIFICATION_SYNC_TAG = "notification_sync"
    private const val TRIP_MONITOR_TAG      = "trip_monitor"

    fun scheduleBackgroundSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Notification Sync every 30 minutes
        val syncRequest = PeriodicWorkRequestBuilder<NotificationSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(NOTIFICATION_SYNC_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NOTIFICATION_SYNC_TAG,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )
        
        // Trip Monitor every 5 minutes (needs to be more frequent)
        // Note: PeriodicWork minimal interval is 15 minutes. 
        // For more frequent checks, we would need a Foreground Service or nested OneTimeWork.
        // Let's use 15 minutes as a fallback for now, or OneTimeWork for the current trip.
    }

    fun startTripMonitoring(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // For Trip Monitoring, we use a more frequent OneTimeWork that reschedules itself
        // or a periodic work of 15 min. Let's try 15 min for now to avoid battery drain.
        val monitorRequest = PeriodicWorkRequestBuilder<TripMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(TRIP_MONITOR_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TRIP_MONITOR_TAG,
            ExistingPeriodicWorkPolicy.REPLACE, // Replace when new trip starts
            monitorRequest
        )
    }
    
    fun stopTripMonitoring(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TRIP_MONITOR_TAG)
    }
}
