package de.traewelling.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.traewelling.app.MainActivity
import de.traewelling.app.data.model.TripDetails
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Explicitly started, visible location FGS. Only nearby station lookups leave the device. */
class RideRecognitionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: PreferencesManager
    private lateinit var repo: TraewellingRepository
    private val engine = RideRecognitionEngine()
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null
    private var pollingJob: Job? = null
    private var tickJob: Job? = null
    private var sessionId = 0L
    private var stopped = false
    private var sessionToken: String? = null
    private var sessionServer: String? = null
    private var hasSearched = false
    private var searchError: String? = null
    private var searching = false
    private val tripCache = linkedMapOf<String, Pair<Long, TripDetails>>()

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        repo = TraewellingRepository(applicationContext, prefs)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Fahrterkennung", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Sichtbare GPS-Suche nach möglichen Fahrten" })
        scope.launch {
            combine(prefs.rideRecognitionEnabled, prefs.accessToken, prefs.activeStatusId, prefs.serverUrl) {
                    enabled, token, active, server ->
                enabled && token != null && active == null &&
                    (sessionToken == null || sessionToken == token) &&
                    (sessionServer == null || sessionServer == server)
            }.collect { allowed -> if (!allowed) finish() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { prefs.setRideRecognitionEnabled(false); finish() }
            return START_NOT_STICKY
        }
        if (stopped) return START_NOT_STICKY
        // Enter foreground immediately; actual scanning starts only after all guards passed.
        try {
            val notification = notification("Warte auf präzisen Standort …")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) { finish(); return START_NOT_STICKY }
        if (pollingJob != null) return START_NOT_STICKY
        scope.launch {
            if (!isAllowed() || !locationServicesEnabled()) { finish(); return@launch }
            // An Activity may issue two starts before the initial preference read finishes.
            if (pollingJob != null || stopped) return@launch
            sessionToken = prefs.getAccessToken()
            sessionServer = prefs.getServerUrl()
            sessionId = sequence.incrementAndGet()
            publish(RideRecognitionPhase.WAITING_FOR_LOCATION, "Warte auf einen frischen, präzisen Standort.")
            if (!requestLocations()) { finish(); return@launch }
            val currentSession = sessionId
            pollingJob = scope.launch {
                while (isActive && current(currentSession)) {
                    if (!isAllowed() || !hasPreciseLocation() || !locationServicesEnabled()) { finish(); break }
                    val fix = engine.reliableLatestFix(System.currentTimeMillis())
                    if (fix != null) {
                        discover(fix, currentSession)
                        delay(POLL_INTERVAL_MILLIS)
                    } else delay(5_000L)
                }
            }
            tickJob = scope.launch {
                while (isActive && current(currentSession)) {
                    delay(10_000)
                    if (!hasPreciseLocation() || !locationServicesEnabled() || !isAllowed()) { finish(); break }
                    updateMatches()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun isAllowed(): Boolean = !stopped && prefs.getRideRecognitionEnabled() &&
        prefs.getAccessToken() != null && prefs.activeStatusId.first() == null

    private fun current(expected: Long): Boolean = !stopped && sessionId == expected
    private fun hasPreciseLocation(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean = runCatching {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun requestLocations(): Boolean {
        if (!hasPreciseLocation()) return false
        val callbackSession = sessionId
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                scope.launch {
                    if (!current(callbackSession)) return@launch
                    val now = System.currentTimeMillis()
                    result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { location ->
                        engine.onLocation(location.toFix(now), now)
                    }
                    updateMatches()
                }
            }
        }
        callback = listener
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6_000L)
            .setMinUpdateIntervalMillis(3_000L).setMaxUpdateDelayMillis(6_000L).build()
        return try {
            locationClient.requestLocationUpdates(request, listener, Looper.getMainLooper())
                .addOnFailureListener {
                    scope.launch { if (current(callbackSession)) finish() }
                }
            true
        } catch (_: SecurityException) { false }
    }

    private fun Location.toFix(now: Long): LocationFix {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        val timestamp = if (elapsedRealtimeNanos > 0 && ageNanos >= 0) now - ageNanos / 1_000_000 else 0L
        return LocationFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else Double.POSITIVE_INFINITY,
            timestamp, if (hasSpeed()) speed.toDouble() else null)
    }

    private suspend fun discover(fix: LocationFix, expected: Long) {
        if (!current(expected) || !isAllowed()) return
        searching = true
        if (_state.value.candidates.isEmpty()) publish(RideRecognitionPhase.SEARCHING, "Suche zeitlich passende Fahrten in der Nähe …")
        try {
            val cancelledTrips = mutableSetOf<String>()
            val found = withTimeout(45_000L) {
                val now = System.currentTimeMillis()
                val stations = repo.getNearbyStations(fix.latitude, fix.longitude).getOrThrow()
                    .filter { it.id != null }.distinctBy { it.id }.take(MAX_NEARBY_STATIONS)
                val departures = stations.flatMap { station ->
                    currentCoroutineContext().ensureActive()
                    repo.getStationDepartures(station.id!!, Instant.ofEpochMilli(now - 5 * 60_000L).toString())
                        .getOrElse { emptyList() }.map { station to it }
                }.onEach { (_, departure) ->
                    if (departure.cancelled == true) cancelledTrips += departure.tripId
                }.filter { (_, departure) ->
                    val time = RideRecognitionEngine.parseTime(departure.realWhen ?: departure.plannedWhen)
                    departure.cancelled != true && time != null &&
                        time in (now - 8 * 60_000L)..(now + 2 * 60_000L)
                }.distinctBy { (station, dep) -> "${dep.tripId}|${dep.line?.name}|${dep.station?.id ?: station.id}|${dep.plannedWhen}" }
                    .sortedBy { (_, dep) -> kotlin.math.abs(now - (RideRecognitionEngine.parseTime(dep.realWhen ?: dep.plannedWhen) ?: now)) }
                    .take(MAX_DEPARTURES)
                val rides = mutableListOf<RecognizableRide>()
                tripCache.entries.removeAll { now - it.value.first > RideRecognitionEngine.ROUTE_TTL_MILLIS }
                for ((station, departure) in departures) {
                    currentCoroutineContext().ensureActive()
                    if (!current(expected) || !isAllowed()) return@withTimeout emptyList<RecognizableRide>()
                    val key = "${departure.tripId}|${departure.line?.name.orEmpty()}"
                    val cached = tripCache[key]
                    val details = cached?.second ?: repo.getTrip(departure.tripId, departure.line?.name.orEmpty())
                        .getOrNull() ?: continue
                    if (cached == null) {
                        tripCache[key] = System.currentTimeMillis() to details
                        while (tripCache.size > RideRecognitionEngine.MAX_ROUTES) tripCache.remove(tripCache.keys.first())
                    }
                    val originIndex = RideRecognitionEngine.resolveOriginIndex(details.stopovers.orEmpty(), departure, station)
                    if (originIndex >= 0) rides += RecognizableRide(departure, details, originIndex, cached?.first ?: System.currentTimeMillis())
                }
                rides
            }
            // A new login, route, disabled setting or destroyed service invalidates in-flight reads.
            if (!current(expected) || !isAllowed() || sessionToken != prefs.getAccessToken() || sessionServer != prefs.getServerUrl()) return
            hasSearched = true
            searching = false
            searchError = null
            engine.removeTrips(cancelledTrips)
            engine.updateRides(found, System.currentTimeMillis())
            updateMatches()
        } catch (error: CancellationException) {
            if (!scope.isActive) throw error
            if (current(expected)) {
                searching = false
                searchError = "Die Fahrtsuche dauerte zu lange. Neuer Versuch in 90 Sekunden."
                publish(RideRecognitionPhase.ERROR, searchError!!)
            }
        } catch (_: Exception) {
            if (current(expected)) {
                searching = false
                searchError = "Keine Stations- oder Fahrtdaten erhalten. Neuer Versuch in 90 Sekunden."
                publish(RideRecognitionPhase.ERROR, searchError!!)
            }
        }
    }

    private fun updateMatches() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val candidates = engine.matches(now).map { it.copy(sessionId = sessionId) }
        when {
            engine.reliableLatestFix(now) == null -> publish(RideRecognitionPhase.WAITING_FOR_LOCATION,
                "Kein frischer präziser Standort. Bestehende Vorschläge sind ausgeblendet.")
            candidates.isNotEmpty() -> publish(RideRecognitionPhase.MATCHES,
                if (candidates.size == 1) "Eine Fahrt passt zu deiner Bewegung. Bitte prüfe Linie und Richtung."
                else "Mehrere Fahrten passen. GPS kann diese Linien nicht sicher unterscheiden.", candidates)
            searching -> publish(RideRecognitionPhase.SEARCHING, "Suche zeitlich passende Fahrten in der Nähe …")
            searchError != null -> publish(RideRecognitionPhase.ERROR, searchError!!)
            else -> publish(RideRecognitionPhase.OBSERVING,
                if (hasSearched) "Beobachte deine Bewegung. Warten an einer Station gilt noch nicht als Fahrt."
                else "Standort vorhanden. Die nächste Fahrtsuche startet in Kürze.")
        }
    }

    private fun publish(phase: RideRecognitionPhase, message: String, candidates: List<RecognizedRide> = emptyList()) {
        if (stopped) return
        _state.value = RideRecognitionState(phase, message, candidates, sessionId)
        val text = if (candidates.isNotEmpty()) {
            if (candidates.size == 1) "Mögliche Fahrt: ${candidates.first().ride.departure.line?.name ?: "Linie unbekannt"}. Zum Prüfen öffnen."
            else "${candidates.size} mögliche Fahrten. Zum Auswählen öffnen."
        } else message
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(message: String): android.app.Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_RECOGNITION, true)
            .setAction("de.traewelling.app.OPEN_RIDE_RECOGNITION")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 711, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 712, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(de.traewelling.app.R.drawable.traewelling_logo)
            .setContentTitle("Routely · Fahrterkennung")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(content).addAction(0, "Suche stoppen", stop)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        sessionId = sequence.incrementAndGet()
        engine.clear()
        tripCache.clear()
        callback?.let { runCatching { locationClient.removeLocationUpdates(it) } }
        callback = null
        pollingJob?.cancel()
        tickJob?.cancel()
        _state.value = RideRecognitionState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ride_recognition"
        private const val NOTIFICATION_ID = 710
        private const val ACTION_STOP = "de.traewelling.app.STOP_RIDE_RECOGNITION"
        const val EXTRA_OPEN_RECOGNITION = "open_ride_recognition"
        const val POLL_INTERVAL_MILLIS = 90_000L
        const val MAX_NEARBY_STATIONS = 2
        const val MAX_DEPARTURES = 6
        private val sequence = AtomicLong(0)
        private val _state = MutableStateFlow(RideRecognitionState())
        val state: StateFlow<RideRecognitionState> = _state.asStateFlow()
        fun startIntent(context: Context): Intent = Intent(context, RideRecognitionService::class.java)
        fun stopIntent(context: Context): Intent = Intent(context, RideRecognitionService::class.java).setAction(ACTION_STOP)
    }
}
