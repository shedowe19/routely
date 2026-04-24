package de.traewelling.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import de.traewelling.app.MainActivity
import de.traewelling.app.R

class TripWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "de.traewelling.app.ACTION_UPDATE_WIDGET") {
            val lineName = intent.getStringExtra("lineName") ?: ""
            val nextStop = intent.getStringExtra("nextStop") ?: ""
            val destination = intent.getStringExtra("destination") ?: ""
            val time = intent.getStringExtra("time") ?: ""
            val platform = intent.getStringExtra("platform") ?: ""
            val delay = intent.getIntExtra("delay", -1)

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TripWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, lineName, nextStop, destination, time, platform, delay)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Init state for all widgets
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.trip_widget)
            views.setTextViewText(R.id.widget_line, "Traewelling")
            views.setTextViewText(R.id.widget_next_stop, "Warte auf Check-in...")
            views.setViewVisibility(R.id.widget_delay, View.GONE)
            views.setViewVisibility(R.id.widget_platform, View.GONE)
            views.setViewVisibility(R.id.widget_time, View.GONE)

            val pendingIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        lineName: String,
        nextStop: String,
        destination: String,
        time: String,
        platform: String,
        delay: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.trip_widget)

        views.setTextViewText(R.id.widget_line, lineName)

        if (nextStop.isNotBlank()) {
            views.setTextViewText(R.id.widget_next_stop, nextStop)
        } else {
            views.setTextViewText(R.id.widget_next_stop, "Nach: $destination")
        }

        if (time.isNotBlank()) {
            views.setViewVisibility(R.id.widget_time, View.VISIBLE)
            views.setTextViewText(R.id.widget_time, time)
        } else {
            views.setViewVisibility(R.id.widget_time, View.GONE)
        }

        if (platform.isNotBlank()) {
            views.setViewVisibility(R.id.widget_platform, View.VISIBLE)
            views.setTextViewText(R.id.widget_platform, "Gl. $platform")
        } else {
            views.setViewVisibility(R.id.widget_platform, View.GONE)
        }

        if (delay > 0) {
            views.setViewVisibility(R.id.widget_delay, View.VISIBLE)
            views.setTextViewText(R.id.widget_delay, "+$delay")
        } else {
            views.setViewVisibility(R.id.widget_delay, View.GONE)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
