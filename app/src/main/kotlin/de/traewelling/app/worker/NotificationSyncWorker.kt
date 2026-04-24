package de.traewelling.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.traewelling.app.data.api.RetrofitClient
import de.traewelling.app.util.NotificationHelper
import de.traewelling.app.util.PreferencesManager

class NotificationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val token = prefs.getAccessToken() ?: return Result.success()
        val baseUrl = prefs.getServerUrl()
        
        val api = RetrofitClient.createApiService(baseUrl, token)
        val helper = NotificationHelper(applicationContext)

        try {
            val response = api.getNotifications(page = 1)
            if (response.isSuccessful) {
                val notifications = response.body()?.data ?: emptyList()
                val unreadNotifications = notifications.filter { n: de.traewelling.app.data.model.Notification -> n.readAt == null }
                
                if (unreadNotifications.isNotEmpty()) {
                    val latest = unreadNotifications.first()
                    val lastNotifiedId = prefs.getLastNotificationId()
                    
                    if (latest.id != lastNotifiedId) {
                        helper.showGeneralNotification(
                            title = latest.lead ?: "Neue Mitteilung",
                            message = latest.notice ?: "Du hast eine neue Benachrichtigung auf Träwelling"
                        )
                        prefs.saveLastNotificationId(latest.id)
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
