package de.traewelling.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : FlutterActivity() {
    var isVisible = false; private set
    private var endpoint: TrackingEndpoint? = null
    private var permissionResult: MethodChannel.Result? = null
    private var locationResult: MethodChannel.Result? = null
    private var oneLocationListener: LocationListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var legacyNonce: String? = null
    private var legacySource: Map<String, Any?>? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        TrackingHost.initialize(this)
        endpoint = TrackingHost.attach(flutterEngine.dartExecutor.binaryMessenger, background = false)
    }
    override fun onResume() { super.onResume(); isVisible = true; TrackingHost.activity = this; dispatchIntent(intent) }
    override fun onPause() { isVisible = false; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); dispatchIntent(intent) }
    private fun dispatchIntent(intent: Intent?) {
        if (intent?.action == "routely.open") {
            TrackingHost.openStatus(intentIdentity(intent)); intent.action = null
        }
    }
    override fun onDestroy() {
        if (TrackingHost.activity === this) TrackingHost.activity = null
        endpoint?.let(TrackingHost::detach); endpoint = null
        cancelOneLocation()
        scope.cancel()
        super.onDestroy()
    }
    fun requestTrackingPermissions(result: MethodChannel.Result) {
        if (!isVisible) { result.error("visible_start_required", "Öffne die App für Berechtigungen.", null); return }
        if (permissionResult != null) { result.error("permission_busy", "Eine Berechtigungsanfrage läuft bereits.", null); return }
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION); needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) { result.success(permissionSnapshot()); return }
        permissionResult = result
        requestPermissions(needed.toTypedArray(), 4403)
    }
    private fun permissionSnapshot(): Map<String, Any?> {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return mapOf("supported" to true, "location" to (fine || coarse), "precise" to fine,
            "background" to (fine || coarse), "locationServicesEnabled" to enabled,
            "notifications" to (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4403) { permissionResult?.success(permissionSnapshot()); permissionResult = null }
    }
    fun requestOneLocation(result: MethodChannel.Result) {
        if (!isVisible || permissionSnapshot()["location"] != true) { result.error("location_permission", "Bitte Standortfreigabe erteilen.", null); return }
        if (locationResult != null) { result.error("location_busy", "Standortsuche läuft bereits.", null); return }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter(manager::isProviderEnabled)
        if (providers.isEmpty()) { result.error("location_disabled", "Bitte Ortungsdienste aktivieren.", null); return }
        locationResult = result
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val callback = locationResult ?: return
                if (!TrackingContract.isFreshLocation(location.elapsedRealtimeNanos, android.os.SystemClock.elapsedRealtimeNanos())) return
                cancelOneLocation(); callback.success(TrackingHost.fix(location))
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Android location compatibility") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        oneLocationListener = listener
        providers.forEach { runCatching { manager.requestLocationUpdates(it, 0L, 0f, listener, mainLooper) } }
        TrackingHost.main.postDelayed({
            if (oneLocationListener === listener) {
                val callback = locationResult
                cancelOneLocation(); callback?.error("location_timeout", "Kein aktueller Standort verfügbar.", null)
            }
        }, 15_000)
    }
    private fun cancelOneLocation() {
        oneLocationListener?.let { runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
        oneLocationListener = null; locationResult = null
    }
    fun legacyImport(result: MethodChannel.Result) {
        val marker = getSharedPreferences("routely_tracking_v1", Context.MODE_PRIVATE)
        if (marker.getBoolean("legacyImported", false) || legacyNonce != null) { result.success(emptyMap<String, Any?>()); return }
        scope.launch {
            try {
                val prefs = applicationContext.legacyPreferences.data.first().asMap().entries.associate { it.key.name to it.value }
                if (prefs.isEmpty()) { result.success(emptyMap<String, Any?>()); return@launch }
                legacySource = prefs
                val nonce = UUID.randomUUID().toString(); legacyNonce = nonce
                val settings = prefs.filterKeys { it in setOf("app_theme", "tts_enabled", "tts_engine", "tts_language", "tts_voice", "gps_tracking_enabled", "announcement_radius_meters", "ride_recognition_enabled", "trip_change_alerts_enabled", "trip_change_speech_enabled", "live_progress_enabled", "lock_screen_details_enabled") }
                val session = mapOf("serverUrl" to prefs["server_url"], "accessToken" to prefs["access_token"],
                    "refreshToken" to prefs["refresh_token"], "clientId" to prefs["client_id"], "clientSecret" to prefs["client_secret"],
                    "username" to prefs["username"], "revision" to (prefs["auth_session_revision"] ?: "legacy"))
                result.success(mapOf("importId" to nonce, "settings" to settings, "session" to session,
                    "activeStatusId" to prefs["active_status_id"]?.toString()?.toIntOrNull(), "trackingState" to prefs["trip_tracking_state"]))
            } catch (_: Exception) { result.error("legacy_import", "Vorhandene Android-Daten konnten nicht gelesen werden.", null) }
        }
    }
    fun commitLegacyImport(nonce: String?, result: MethodChannel.Result) {
        if (nonce == null || nonce != legacyNonce || legacySource == null) { result.error("stale_import", "Ungültiger Importauftrag.", null); return }
        scope.launch {
            try {
                var accepted = false
                applicationContext.legacyPreferences.edit { prefs ->
                    val current = prefs.asMap().entries.associate { it.key.name to it.value }
                    if (current["access_token"] == legacySource!!["access_token"] && current["auth_session_revision"] == legacySource!!["auth_session_revision"]) {
                        prefs.asMap().keys.filter { it.name in setOf("access_token", "refresh_token", "client_id", "client_secret", "username", "active_status_id", "trip_tracking_state", "auth_session_revision", "ride_recognition_enabled") }.forEach { prefs.remove(it) }
                        accepted = true
                    }
                }
                if (accepted) getSharedPreferences("routely_tracking_v1", Context.MODE_PRIVATE).edit().putBoolean("legacyImported", true).commit()
                legacySource = null; legacyNonce = null
                result.success(mapOf("accepted" to accepted))
            } catch (_: Exception) { result.error("legacy_import_commit", "Der Datenimport konnte nicht abgeschlossen werden.", null) }
        }
    }
}
