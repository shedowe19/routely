package de.traewelling.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.traewelling.app.util.NotificationHelper
import java.util.concurrent.TimeUnit

class TraewellingApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        
        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Channel for General Notifications (Likes, Follows, etc.)
            val generalChannel = NotificationChannel(
                NotificationHelper.CHANNEL_GENERAL,
                "Benachrichtigungen",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Mitteilungen über Likes, Kommentare und Follower"
            }
            
            // Channel for Trip Alerts (Arrivals, Delays)
            val tripChannel = NotificationChannel(
                NotificationHelper.CHANNEL_TRIP,
                "Reise-Infos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wichtige Hinweise zur Ankunft und zum Gleis"
            }
            
            notificationManager.createNotificationChannel(generalChannel)
            notificationManager.createNotificationChannel(tripChannel)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
