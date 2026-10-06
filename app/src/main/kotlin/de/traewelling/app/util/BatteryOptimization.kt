package de.traewelling.app.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Android owns this permission; a successful settings launch does not imply an exemption. */
object BatteryOptimization {
    private const val TAG = "BatteryOptimization"

    fun isExempt(context: Context): Boolean? {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return null
        return try {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Battery optimization status unavailable", exception)
            null
        }
    }

    /** Only call from an explicit user action while the app is visible. */
    fun requestExemption(context: Context): Boolean = openFirstAvailable(
        context,
        listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(context)
        )
    )

    fun openAppSettings(context: Context): Boolean = openFirstAvailable(
        context,
        listOf(appDetailsIntent(context), Intent(Settings.ACTION_SETTINGS))
    )

    private fun appDetailsIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    private fun openFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "Battery settings action unavailable: ${intent.action}", exception)
            } catch (exception: SecurityException) {
                Log.w(TAG, "Battery settings action denied: ${intent.action}", exception)
            }
        }
        return false
    }
}
