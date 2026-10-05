package de.traewelling.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import de.traewelling.app.R

/** System templates only; Android/OEM settings decide whether an eligible trip is promoted. */
object TripProgressNotificationBuilder {
    // Public Notification extras documented in API 36.1. Using their documented
    // values allows compileSdk 36 without reflection or a Kotlin 2 dependency migration.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun build(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        model: TripProgressModel?,
        showLockScreenDetails: Boolean,
        requestLiveUpdate: Boolean,
        stopPendingIntent: PendingIntent,
        openPendingIntent: PendingIntent,
        deletePendingIntent: PendingIntent? = null
    ): Notification {
        val expandedText = model?.expandedText?.let { "$text\n$it" } ?: text
        val publicVersion = if (showLockScreenDetails) null else NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.traewelling_logo)
            .setContentTitle("Routely begleitet deine Fahrt")
            .setContentText("App öffnen für Reisedetails")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        // Redacted notifications must also avoid leaking their route through a
        // promoted chip or a manufacturer-specific live-update lock-screen surface.
        val promote = requestLiveUpdate && showLockScreenDetails && model?.shouldPromote == true
        return if (Build.VERSION.SDK_INT >= 36 && model != null && requestLiveUpdate) {
            Api36.build(
                context, channelId, title, text, model,
                showLockScreenDetails, publicVersion, promote,
                stopPendingIntent, openPendingIntent, deletePendingIntent
            )
        } else {
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.traewelling_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Fahrt öffnen", openPendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(if (showLockScreenDetails) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .apply {
                    if (requestLiveUpdate && model != null) {
                        setProgress(model.progressMax, model.progress ?: 0, model.progress == null)
                        setSubText(model.remainingText)
                    }
                }.build()
        }
    }

    @RequiresApi(36)
    private object Api36 {
        fun build(
            context: Context,
            channelId: String,
            title: String,
            text: String,
            model: TripProgressModel,
            showDetails: Boolean,
            publicVersion: Notification?,
            promote: Boolean,
            stopIntent: PendingIntent,
            openIntent: PendingIntent,
            deleteIntent: PendingIntent?
        ): Notification {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(model.progress ?: 0)
                .setProgressIndeterminate(model.progress == null)
                .setProgressSegments(List(model.totalStops.coerceAtLeast(1)) { index ->
                    Notification.ProgressStyle.Segment(TripProgressModel.UNITS_PER_STOP)
                        .setId(index + 1)
                        .setColor(Color.rgb(0, 137, 123))
                })
                .setProgressPoints((1..model.totalStops).map { index ->
                    Notification.ProgressStyle.Point(index * TripProgressModel.UNITS_PER_STOP)
                        .setId(index)
                        .setColor(Color.rgb(0, 137, 123))
                })
            return Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.traewelling_logo)
                .setContentTitle(title)
                .setContentText("$text · ${model.remainingText}")
                .setStyle(style)
                .setSubText(listOfNotNull(model.arrivalText, model.sourceLabel).joinToString(" · "))
                .setContentIntent(openIntent)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopIntent).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view, "Fahrt öffnen", openIntent).build())
                .setDeleteIntent(deleteIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(if (showDetails) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, promote) })
                .build()
        }
    }
}
