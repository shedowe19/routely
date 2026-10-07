package de.traewelling.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal val Context.legacyPreferences by preferencesDataStore(name = "traewelling_prefs")

/** Native lifecycle host: location fixes never go through the visible UI engine. */
object TrackingHost {
    val main = Handler(Looper.getMainLooper())
    var service: TrackingService? = null
    var activity: MainActivity? = null
    private val endpoints = mutableSetOf<TrackingEndpoint>()
    private var configuration: Map<String, Any?>? = null
    private var snapshot: Map<String, Any?>? = null
    private var pendingOpen: Map<String, Any?>? = null
    private var lastStopped: Map<String, Any?>? = null
    private var pendingStart: Pair<Map<String, Any?>, MethodChannel.Result>? = null
    private var highestGeneration = -1L
    private var blockedThroughGeneration = -1L
    private val alertKeys = linkedSetOf<String>()
    private lateinit var context: Context
    private val bootClock = "android-${UUID.randomUUID()}"
    private val preferences get() = context.getSharedPreferences("routely_tracking_v1", Context.MODE_PRIVATE)

    fun initialize(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        lastStopped = preferences.getString("lastStopped", null)?.let { runCatching { jsonMap(JSONObject(it)) }.getOrNull() }
        highestGeneration = preferences.getLong("generation", -1L)
        blockedThroughGeneration = preferences.getLong("blockedGeneration", -1L)
        preferences.getString("changeKeys", null)?.let { raw -> runCatching {
            val saved = JSONObject(raw)
            if (saved.optLong("generation", -1L) == highestGeneration) {
                val keys = saved.optJSONArray("keys") ?: JSONArray()
                for (i in 0 until keys.length()) if (alertKeys.size < 64) alertKeys.add(keys.getString(i))
            }
        } }
        configuration = preferences.getString("configuration", null)?.let {
            runCatching { jsonMap(JSONObject(it)) }.getOrNull()
        }
    }
    fun attach(messenger: BinaryMessenger, background: Boolean): TrackingEndpoint =
        TrackingEndpoint(messenger, background).also { endpoints.add(it) }
    fun detach(endpoint: TrackingEndpoint) { endpoints.remove(endpoint); endpoint.detach() }
    fun current(): Map<String, Any?> = configuration?.toMutableMap()?.apply {
        put("running", service?.running == true)
        put("gpsAvailable", service?.gpsAvailable == true)
        put("supported", true)
        put("capabilities", mapOf("backgroundLocation" to true, "widget" to true,
            "liveActivity" to false, "batteryExemption" to true))
    } ?: mapOf("running" to false, "supported" to true, "generation" to highestGeneration,
        "capabilities" to mapOf("backgroundLocation" to true, "widget" to true,
            "liveActivity" to false, "batteryExemption" to true)).let { base -> if (lastStopped == null) base else base + mapOf("lastStopped" to lastStopped) }
    fun latest(): Map<String, Any?> = snapshot ?: mapOf("running" to false)
    private fun generation(value: Map<String, Any?>) = (value["generation"] as? Number)?.toLong() ?: -1L
    fun prepareStart(identity: Map<String, Any?>, result: MethodChannel.Result) {
        pendingStart?.second?.success(mapOf("accepted" to false, "running" to false, "message" to "Startauftrag wurde durch eine neuere Fahrt ersetzt."))
        pendingStart = identity to result
        main.postDelayed({
            val current = pendingStart
            if (current != null && generation(current.first) == generation(identity)) {
                pendingStart = null
                current.second.success(mapOf("accepted" to false, "running" to false, "message" to "Fahrtbegleitung konnte nicht rechtzeitig gestartet werden."))
                if (matches(identity)) stop(identity + mapOf("reason" to "start_failed"))
            }
        }, 15_000)
    }
    fun finishStart(identity: Map<String, Any?>, successful: Boolean) {
        val current = pendingStart ?: return
        if (current.first["sessionRevision"] != identity["sessionRevision"] ||
            current.first["statusId"] != identity["statusId"] || generation(current.first) != generation(identity)) return
        pendingStart = null
        current.second.success(mapOf("accepted" to successful, "running" to successful,
            "gpsAvailable" to (successful && service?.gpsAvailable == true)))
    }
    fun matches(value: Map<String, Any?>): Boolean = TrackingContract.matches(configuration, value)
    fun runnable(value: Map<String, Any?>): Boolean = matches(value) && generation(value) > blockedThroughGeneration
    fun retireStarts() {
        blockedThroughGeneration = highestGeneration
        preferences.edit().putLong("blockedGeneration", blockedThroughGeneration).commit()
        pendingStart?.first?.let { finishStart(it, false) }
    }
    fun install(value: Map<String, Any?>): Boolean {
        if (!TrackingContract.acceptsStart(configuration, highestGeneration, value) || generation(value) <= blockedThroughGeneration) return false
        val gen = generation(value)
        val next = value.filterKeys { it in setOf("sessionRevision", "statusId", "generation", "route", "settings", "runtime", "mode") }.toMutableMap()
        (next["runtime"] as? Map<*, *>)?.let { next["runtime"] = it.filterKeys { key -> key in setOf("version", "journey", "progress", "changes", "sevMaps", "retainedChanges") } }
        if (!preferences.edit().putLong("generation", gen)
            .putString("configuration", JSONObject(next).toString()).commit()) return false
        if (gen != highestGeneration) {
            service?.prepareForNewTrip()
            clearChangeAlerts()
            if (!TrackingContract.sameAlertOwner(configuration, next)) alertKeys.clear()
            preferences.edit().putString("changeKeys", JSONObject(mapOf("generation" to gen, "keys" to alertKeys.toList())).toString()).apply()
        }
        highestGeneration = gen
        lastStopped = null; preferences.edit().remove("lastStopped").apply()
        configuration = next
        snapshot = next.filterKeys { it in setOf("sessionRevision", "statusId", "generation") } + mapOf("line" to "Routely", "nextStop" to if (next["mode"] == "recognition") "Fahrtsuche startet …" else "Fahrt wird geladen …")
        TrackingWidget.update(context, snapshot!!)
        emit("configuration", current())
        emit("snapshot", snapshot!!, background = false)
        return true
    }
    fun publish(value: Map<String, Any?>): Boolean {
        if (!matches(value) || service?.running != true || containsSecret(value)) return false
        val runtime = value["runtime"] as? Map<*, *>
        if (runtime != null) {
            val safe = runtime.filterKeys { it in setOf("version", "journey", "progress", "changes", "sevMaps", "retainedChanges") }
            configuration = configuration!! + mapOf("runtime" to safe)
            preferences.edit().putString("configuration", JSONObject(configuration!!).toString()).apply()
        }
        snapshot = value.filterKeys { it != "runtime" }
        service?.updateNotification(snapshot!!)
        TrackingWidget.update(context, snapshot!!)
        publishChangeAlerts(value)
        emit("snapshot", snapshot!!, background = false)
        if (value["completed"] == true) main.post { if (matches(value)) stop(value) }
        return true
    }
    fun stop(value: Map<String, Any?>, clear: Boolean = true): Boolean {
        if (!matches(value)) return false
        finishStart(value, false)
        if (clear) {
            lastStopped = value.filterKeys { it in setOf("sessionRevision", "statusId", "generation") } + mapOf("reason" to (value["reason"] as? String ?: value["completionReason"] as? String ?: if (value["completed"] == true) "completed" else "manual"))
            preferences.edit().putString("lastStopped", JSONObject(lastStopped!!).toString()).commit()
        }
        service?.finish()
        if (clear) {
            configuration = null
            preferences.edit().remove("configuration").commit()
        }
        clearDisplay()
        emit("configuration", current())
        return true
    }
    fun stopped() { clearDisplay(); emit("configuration", current()) }
    private fun clearDisplay() {
        clearChangeAlerts()
        snapshot = null
        TrackingWidget.update(context, emptyMap())
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(4401)
        emit("snapshot", mapOf("running" to false), background = false)
    }
    private fun clearChangeAlerts() {
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.activeNotifications.filter { it.tag?.startsWith("routely.change.") == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }
    private fun publishChangeAlerts(value: Map<String, Any?>) {
        if (!matches(value) || !flag(settings(), "tripChangeAlertsEnabled", "trip_change_alerts_enabled", true)) return
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel("routely_trip_changes", "Reiseänderungen", NotificationManager.IMPORTANCE_DEFAULT))
        if (notifications.getNotificationChannel("routely_trip_changes")?.importance == NotificationManager.IMPORTANCE_NONE) return
        val alerts = value["changeAlerts"] as? List<*> ?: return
        val details = flag(settings(), "lockScreenDetailsEnabled", "lock_screen_details_enabled", true)
        for (raw in alerts.take(64)) {
            val alert = raw as? Map<*, *> ?: continue
            val key = (alert["key"] as? String)?.take(512)?.takeIf(String::isNotBlank) ?: continue
            val message = (alert["message"] as? String)?.take(1000)?.takeIf(String::isNotBlank) ?: continue
            if (key in alertKeys || !matches(value)) continue
            val intent = Intent(context, MainActivity::class.java).setAction("routely.open").apply {
                putExtra("sessionRevision", value["sessionRevision"] as String)
                putExtra("statusId", (value["statusId"] as Number).toInt())
                putExtra("generation", generation(value))
                data = Uri.parse("routely://tracking/${generation(value)}/${value["statusId"]}")
            }
            val pending = PendingIntent.getActivity(context, (generation(value).toString()+key).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(context, "routely_trip_changes").setSmallIcon(R.drawable.ic_tracking_notification).setContentTitle("Routely").setContentText("Es gibt eine Reiseänderung.").build()
            val notification = Notification.Builder(context, "routely_trip_changes").setSmallIcon(R.drawable.ic_tracking_notification)
                .setContentTitle((alert["title"] as? String)?.take(128) ?: "Reiseänderung").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(pending).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(if (details) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
            if (runCatching { notifications.notify("routely.change.${generation(value)}.${key.hashCode()}", 4403, notification) }.isSuccess) {
                alertKeys.add(key)
                while (alertKeys.size > 64) { val oldest = alertKeys.first(); alertKeys.remove(oldest); notifications.cancel("routely.change.${generation(value)}.${oldest.hashCode()}", 4403) }
                preferences.edit().putString("changeKeys", JSONObject(mapOf("generation" to highestGeneration, "keys" to alertKeys.toList())).toString()).apply()
            }
        }
    }
    fun eventError(code: String, message: String) = emit("error", (configuration?.filterKeys { it in setOf("sessionRevision", "statusId", "generation") } ?: emptyMap()) + mapOf("code" to code, "message" to message))
    fun emit(type: String, value: Map<String, Any?>, background: Boolean? = null) {
        val event = value + mapOf("type" to type)
        endpoints.toList().filter { background == null || it.background == background }.forEach { it.emit(event) }
    }
    fun openStatus(value: Map<String, Any?>) {
        if (matches(value)) {
            pendingOpen = value
            emit("openStatus", value, background = false)
            if (endpoints.any { !it.background && it.listening }) pendingOpen = null
        }
    }
    fun takePendingOpen(): Map<String, Any?>? = pendingOpen?.also { pendingOpen = null }?.takeIf(::matches)
    fun fix(location: Location): Map<String, Any?> = mapOf(
        "latitude" to location.latitude, "longitude" to location.longitude,
        "accuracy" to location.accuracy.toDouble(),
        "speed" to if (location.hasSpeed()) location.speed.toDouble() else null,
        "heading" to if (location.hasBearing()) location.bearing.toDouble() else null,
        "timeMillis" to location.time, "elapsedRealtimeNanos" to location.elapsedRealtimeNanos,
        "clockId" to bootClock, "nowMillis" to System.currentTimeMillis(),
        "nowMonotonicNanos" to android.os.SystemClock.elapsedRealtimeNanos())
    fun location(location: Location, identity: Map<String, Any?>) {
        if (!matches(identity) || service?.running != true) return
        emit("fix", fix(location) + identity.filterKeys { it in setOf("sessionRevision", "statusId", "generation") }, background = true)
    }
    fun settings(value: Map<String, Any?> = current()): Map<String, Any?> =
        (value["settings"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
    fun flag(settings: Map<String, Any?>, camel: String, snake: String, default: Boolean): Boolean =
        (settings[camel] ?: settings[snake]) as? Boolean ?: default
    private fun containsSecret(value: Any?): Boolean = TrackingContract.containsSecret(value)
    internal fun jsonMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
}

class TrackingEndpoint(messenger: BinaryMessenger, val background: Boolean) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val ownerIdentity = if (background) TrackingHost.current().filterKeys { it in setOf("sessionRevision", "statusId", "generation") } else null
    private val methods = MethodChannel(messenger, "routely/tracking")
    private val events = EventChannel(messenger, "routely/tracking_events")
    private var sink: EventChannel.EventSink? = null
    val listening get() = sink != null
    init { methods.setMethodCallHandler(this); events.setStreamHandler(this) }
    fun detach() { methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null }
    fun emit(value: Map<String, Any?>) {
        if (background && value["type"] in setOf("configuration", "fix", "locationAvailability") &&
            (value["sessionRevision"] != ownerIdentity?.get("sessionRevision") ||
             value["statusId"] != ownerIdentity?.get("statusId") ||
             value["generation"] != ownerIdentity?.get("generation"))) return
        sink?.success(value)
    }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        events.success(TrackingHost.current() + mapOf("type" to "configuration"))
        if (!background && TrackingHost.latest()["running"] != false) events.success(TrackingHost.latest() + mapOf("type" to "snapshot"))
        if (!background) TrackingHost.takePendingOpen()?.let { events.success(it + mapOf("type" to "openStatus")) }
    }
    override fun onCancel(arguments: Any?) { sink = null }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = (call.arguments as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        val activity = TrackingHost.activity
        when (call.method) {
            "getConfiguration" -> result.success(TrackingHost.current())
            "getSnapshot" -> result.success(TrackingHost.latest())
            "startTracking", "startRecognition" -> {
                if (background || activity == null || !activity.isVisible) {
                    result.error("visible_start_required", "Öffne Routely, um die Fahrtbegleitung zu starten.", null); return
                }
                if (!TrackingHost.install(args)) { result.error("stale_identity", "Veraltete oder ungültige Fahrtkonfiguration.", null); return }
                val intent = Intent(activity, TrackingService::class.java).apply {
                    action = "start"
                    putExtra("sessionRevision", args["sessionRevision"] as String)
                    putExtra("statusId", (args["statusId"] as Number).toInt())
                    putExtra("generation", (args["generation"] as Number).toLong())
                }
                TrackingHost.prepareStart(args, result)
                try { activity.startForegroundService(intent) }
                catch (_: Exception) { TrackingHost.finishStart(args, false); TrackingHost.service?.finish(); TrackingHost.eventError("service_start", "Fahrtbegleitung konnte nicht gestartet werden.") }
            }
            "stopTracking" -> result.success(mapOf("accepted" to (!background && TrackingHost.stop(args))))
            "publish" -> result.success(mapOf("accepted" to (background && ownerIdentity?.all { (key, value) -> args[key] == value } == true && TrackingHost.publish(args))))
            "batteryStatus" -> {
                val app = activity ?: TrackingHost.service
                val power = app?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                result.success(mapOf("supported" to true, "exempt" to (power?.isIgnoringBatteryOptimizations(app?.packageName) ?: false)))
            }
            "requestPermissions" -> if (background || activity == null) result.error("visible_start_required", "Öffne die App für Berechtigungen.", null) else activity.requestTrackingPermissions(result)
            "requestLocation" -> if (background || activity == null) result.error("visible_start_required", "Öffne die App für die Standortsuche.", null) else activity.requestOneLocation(result)
            "requestBatteryExemption" -> {
                if (background || activity == null) { result.error("visible_start_required", "Öffne die App für Akkueinstellungen.", null); return }
                val opened = runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))) }.isSuccess
                result.success(mapOf("opened" to opened))
            }
            "openNotificationSettings" -> {
                if (background || activity == null) { result.error("visible_start_required", "Öffne die App für Einstellungen.", null); return }
                val opened = runCatching { activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) }.isSuccess
                result.success(mapOf("opened" to opened))
            }
            "legacyImport" -> if (background || activity == null) result.error("unavailable", "Import nur in der sichtbaren App.", null) else activity.legacyImport(result)
            "commitLegacyImport" -> if (background || activity == null) result.error("unavailable", "Import nur in der sichtbaren App.", null) else activity.commitLegacyImport(args["importId"] as? String, result)
            else -> result.notImplemented()
        }
    }
}

class TrackingService : Service(), LocationListener {
    var running = false; private set
    var gpsActive = false; private set
    val gpsAvailable get() = gpsActive && hasLocationPermission() && locationEnabled()
    private var engine: FlutterEngine? = null
    private var endpoint: TrackingEndpoint? = null
    private var locationManager: LocationManager? = null
    private var identity = emptyMap<String, Any?>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var leaseGeneration = -1L
    private var startedAtNanos = Long.MAX_VALUE
    private val permissionWatch = object : Runnable {
        override fun run() {
            if (!running) return
            if (TrackingHost.current()["mode"] == "recognition" &&
                (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !locationEnabled())) {
                TrackingHost.eventError("recognition_permission", "Fahrtsuche beendet: Präziser Standort oder Ortungsdienste sind nicht verfügbar.")
                TrackingHost.stop(identity); return
            }
            TrackingHost.main.postDelayed(this, 5000)
        }
    }
    private val renewal = object : Runnable {
        override fun run() {
            if (!running || !TrackingHost.matches(identity) || leaseGeneration != (identity["generation"] as? Number)?.toLong()) return
            runCatching { wakeLock?.acquire(120_000) }
            TrackingHost.main.postDelayed(this, 60_000)
        }
    }
    override fun onCreate() {
        super.onCreate(); TrackingHost.initialize(this); TrackingHost.service = this
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("routely_trip", "Fahrtbegleitung", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            if (TrackingHost.matches(intentIdentity(intent))) TrackingHost.stop(intentIdentity(intent) + mapOf("reason" to "notification"))
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        // A system restart cannot regain while-in-use location permission or a
        // visible start. The route is retained for a subsequent visible resume.
        if (intent == null || !TrackingHost.runnable(intentIdentity(intent))) { if (!running) stopSelf(startId); return START_NOT_STICKY }
        val next = TrackingHost.current()
        val nextIdentity = intentIdentity(intent)
        if (running && identity == nextIdentity) { TrackingHost.emit("configuration", next); TrackingHost.finishStart(identity, true); return START_NOT_STICKY }
        releaseResources()
        identity = nextIdentity
        val settings = TrackingHost.settings(next)
        if (next["mode"] == "recognition" && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !locationEnabled())) {
            TrackingHost.finishStart(identity, false); TrackingHost.stop(identity); return START_NOT_STICKY
        }
        val gps = (next["mode"] == "recognition" || TrackingHost.flag(settings, "gpsEnabled", "gps_tracking_enabled", true)) && hasLocationPermission() && locationEnabled()
        try {
            val notification = notification(mapOf("nextStop" to "Fahrt wird geladen …"))
            if (Build.VERSION.SDK_INT >= 29) startForeground(4401, notification,
                if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(4401, notification)
            running = true
            startedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Routely:TripTracking").apply { setReferenceCounted(false) }
            leaseGeneration = (identity["generation"] as Number).toLong()
            renewal.run()
            engine = FlutterEngine(this, null, false).also { e ->
                GeneratedPluginRegistrant.registerWith(e)
                endpoint = TrackingHost.attach(e.dartExecutor.binaryMessenger, background = true)
                e.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint(io.flutter.FlutterInjector.instance().flutterLoader().findAppBundlePath(), "trackingMain"))
            }
            if (gps) startLocation()
            if (next["mode"] == "recognition" && !gpsActive) {
                TrackingHost.finishStart(identity, false)
                TrackingHost.stop(identity)
                return START_NOT_STICKY
            }
            permissionWatch.run()
            TrackingHost.finishStart(identity, true)
            TrackingHost.emit("configuration", TrackingHost.current())
        } catch (_: Exception) { TrackingHost.finishStart(identity, false); TrackingHost.eventError("tracking_start", "Standortbegleitung konnte nicht gestartet werden."); finish() }
        return START_NOT_STICKY
    }
    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun locationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    private fun startLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = manager
        var subscribed = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 3000L, 5f, this, Looper.getMainLooper())
                    subscribed = true
                }
            } catch (_: SecurityException) {
                val precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val approximate = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val recognition = TrackingHost.current()["mode"] == "recognition"
                // Preserve any permitted provider, including coarse GPS on
                // newer Android. Recognition cannot downgrade to COARSE.
                if (TrackingContract.permitsLocation(precise, approximate, recognition)) continue
                // Revocation may race with startForeground and the permission
                // preflight. Remove partial subscriptions before falling back.
                runCatching { manager.removeUpdates(this) }
                gpsActive = false
                TrackingHost.eventError("location_permission", "Standortfreigabe ist nicht mehr verfügbar.")
                return
            } catch (_: IllegalArgumentException) {
                // A provider may disappear after the enabled-provider check.
            }
        }
        gpsActive = subscribed
        if (!subscribed) TrackingHost.eventError("location_unavailable", "Keine Standortquelle verfügbar.")
    }
    override fun onLocationChanged(location: Location) { if (location.elapsedRealtimeNanos >= startedAtNanos) { TrackingHost.emit("locationAvailability", identity + mapOf("available" to true), background = true); TrackingHost.location(location, identity) } }
    override fun onProviderDisabled(provider: String) { TrackingHost.emit("locationAvailability", identity + mapOf("available" to locationEnabled()), background = true) }
    override fun onProviderEnabled(provider: String) { TrackingHost.emit("locationAvailability", identity + mapOf("available" to true), background = true) }
    @Deprecated("Android location compatibility") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    fun prepareForNewTrip() { releaseResources(); stopForeground(STOP_FOREGROUND_REMOVE) }
    fun updateNotification(snapshot: Map<String, Any?>) {
        if (running && TrackingHost.matches(snapshot) && TrackingContract.matches(identity, snapshot)) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(4401, notification(snapshot))
    }
    private fun notification(value: Map<String, Any?>): Notification {
        val settings = TrackingHost.settings()
        val details = TrackingHost.flag(settings, "lockScreenDetailsEnabled", "lock_screen_details_enabled", true)
        val live = TrackingHost.flag(settings, "liveProgressEnabled", "live_progress_enabled", true)
        val title = if (details) value["line"]?.toString()?.takeIf(String::isNotBlank) ?: "Routely" else "Routely"
        val model = value["tripProgress"] as? Map<*, *>
        val text = if (details) listOf(value["nextStop"], value["timeLabel"], value["timeSource"], value["platform"]?.let { "Gleis $it" }, model?.get("remainingText")).filterNotNull().joinToString(" · ") else "Fahrtbegleitung ist aktiv"
        val open = identityIntent(Intent(this, MainActivity::class.java).setAction("routely.open"))
        val stop = identityIntent(Intent(this, TrackingService::class.java).setAction("stop"))
        val request = ((identity["generation"] as? Number)?.toLong() ?: 0).hashCode()
        val openPending = PendingIntent.getActivity(this, request, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getService(this, request, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "routely_trip").setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openPending).setDeleteIntent(stopPending).addAction(Notification.Action.Builder(null, "Beenden", stopPending).build())
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(Notification.Action.Builder(null, "Fahrt öffnen", openPending).build())
        if (details && model != null) builder.setStyle(Notification.BigTextStyle().bigText("$text\n${model["expandedText"] ?: ""}"))
        if (details && live) {
            val progressMax = (model?.get("progressMax") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1000
            val progressValue = (model?.get("progress") as? Number)?.toInt()
            val totalStops = (model?.get("totalStops") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            builder.setSubText(listOfNotNull(model?.get("arrivalText"), model?.get("sourceLabel")).joinToString(" · "))
            if (Build.VERSION.SDK_INT >= 36 && model != null) {
                val style = Notification.ProgressStyle().setStyledByProgress(true)
                    .setProgress(progressValue ?: 0).setProgressIndeterminate(progressValue == null)
                    .setProgressSegments(List(totalStops.coerceAtLeast(1)) { index ->
                        Notification.ProgressStyle.Segment(100).setId(index + 1).setColor(android.graphics.Color.rgb(0, 137, 123))
                    })
                    .setProgressPoints((1..totalStops).map { index ->
                        Notification.ProgressStyle.Point(index * 100).setId(index).setColor(android.graphics.Color.rgb(0, 137, 123))
                    })
                builder.setStyle(style)
                // Public API36.1 extra, matching the original Android host.
                // Calling setRequestPromotedOngoing would require compileSdk36.1.
                builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", model["shouldPromote"] == true) })
            } else builder.setProgress(progressMax, progressValue ?: 0, progressValue == null)

        }
        val public = Notification.Builder(this, "routely_trip").setSmallIcon(R.drawable.ic_tracking_notification).setContentTitle("Routely").setContentText("Fahrtbegleitung ist aktiv").build()
        return builder.setVisibility(if (details) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
    private fun identityIntent(intent: Intent): Intent = intent.apply {
        putExtra("sessionRevision", identity["sessionRevision"] as? String)
        putExtra("statusId", (identity["statusId"] as? Number)?.toInt() ?: -1)
        putExtra("generation", (identity["generation"] as? Number)?.toLong() ?: -1L)
        data = Uri.parse("routely://tracking/${identity["generation"]}/${identity["statusId"]}")
    }
    private fun releaseResources() {
        running = false
        gpsActive = false
        startedAtNanos = Long.MAX_VALUE
        TrackingHost.main.removeCallbacks(renewal)
        TrackingHost.main.removeCallbacks(permissionWatch)
        runCatching { locationManager?.removeUpdates(this) }; locationManager = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null
        endpoint?.let(TrackingHost::detach); endpoint = null
        engine?.destroy(); engine = null
    }
    fun finish() {
        releaseResources(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); TrackingHost.stopped()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { TrackingHost.retireStarts(); finish() }
    override fun onDestroy() {
        releaseResources()
        if (TrackingHost.service === this) { TrackingHost.service = null; TrackingHost.stopped() }
        super.onDestroy()
    }
}

internal fun intentIdentity(intent: Intent): Map<String, Any?> = mapOf(
    "sessionRevision" to intent.getStringExtra("sessionRevision"),
    "statusId" to intent.getIntExtra("statusId", -1),
    "generation" to intent.getLongExtra("generation", -1L))

class TrackingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        TrackingHost.initialize(context); update(context, TrackingHost.latest(), ids)
    }
    companion object {
        fun update(context: Context, snapshot: Map<String, Any?>, requested: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = requested ?: manager.getAppWidgetIds(android.content.ComponentName(context, TrackingWidget::class.java))
            val views = RemoteViews(context.packageName, R.layout.tracking_widget)
            val active = snapshot["nextStop"] != null
            val details = TrackingHost.flag(TrackingHost.settings(), "lockScreenDetailsEnabled", "lock_screen_details_enabled", true)
            views.setTextViewText(R.id.widget_line, if (details && active) snapshot["line"]?.toString() ?: "Routely" else "Routely")
            views.setTextViewText(R.id.widget_stop, if (!active) "Warte auf deinen Check-in" else if (details) snapshot["nextStop"]?.toString() else "Fahrtbegleitung ist aktiv")
            views.setTextViewText(R.id.widget_updated, if (active) "Stand ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date())}" else "")
            views.setTextViewText(R.id.widget_time, if (details && active) listOf(snapshot["timeLabel"], snapshot["timeSource"], snapshot["platform"]?.let { "Gleis $it" }).filterNotNull().joinToString(" · ") else "")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "routely.open"
                snapshot["sessionRevision"]?.let { putExtra("sessionRevision", it.toString()) }
                putExtra("statusId", (snapshot["statusId"] as? Number)?.toInt() ?: -1)
                putExtra("generation", (snapshot["generation"] as? Number)?.toLong() ?: -1L)
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 4402, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
