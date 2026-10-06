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
import android.location.LocationManager
import android.net.Uri
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
import de.traewelling.app.util.AuthSession
import de.traewelling.app.util.TrackingConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
    private var locationClock = TrackingLocationClock()
    private var observationRevision = 0L
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null
    private var pollingJob: Job? = null
    private var tickJob: Job? = null
    private var sessionId = 0L
    private var stopped = false
    private var ownedAuthSession: AuthSession? = null
    private var observedAuthSession: AuthSession? = null
    private var pendingRequestedRevision: String? = null
    private val startRequests = SessionStartRequests()
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
            prefs.trackingConfiguration.collect { emitted ->
                val config = prefs.trackingConfiguration.first()
                if (config != emitted) return@collect
                observedAuthSession = config.session
                if (!recognitionAllowed(config)) finish()
                else if (ownedAuthSession?.let { it != config.session } == true) {
                    finish(preserveForegroundForPendingStart = startRequests.hasPending(config.session.revision))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedRevision = intent?.getStringExtra(EXTRA_AUTH_SESSION_REVISION)
        if (intent?.action == ACTION_STOP) {
            val expectedSession = ownedAuthSession
            val expectedRecognitionSession = sessionId
            scope.launch {
                val config = prefs.trackingConfiguration.first()
                if ((sessionId != expectedRecognitionSession && (expectedSession != null || requestedRevision == null)) ||
                    requestedRevision != config.session.revision ||
                    (expectedSession != null && expectedSession != config.session)) return@launch
                startRequests.clear()
                val stoppingSession = sessionId
                try { prefs.setRideRecognitionEnabled(false, expectedSession ?: config.session) }
                finally { if (sessionId == stoppingSession) finish() }
            }
            return START_NOT_STICKY
        }
        if (!scope.isActive) return START_NOT_STICKY
        val startRequest = startRequests.begin(requestedRevision)
        pendingRequestedRevision = requestedRevision
        // Enter foreground immediately; actual scanning starts only after all guards passed.
        try {
            val notification = notification("Warte auf präzisen Standort …")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) { finish(); return START_NOT_STICKY }
        scope.launch {
            try {
                val config = prefs.trackingConfiguration.first()
                if (!recognitionAllowed(config) || !locationServicesEnabled()) { finish(); return@launch }
                if (!startRequests.accept(startRequest, config.session.revision)) {
                    startRequests.finish(startRequest)
                    if (ownedAuthSession == config.session && !stopped) updateMatches()
                    else if (!startRequests.hasPending(config.session.revision)) finish()
                    return@launch
                }
                // An Activity may issue two starts before the initial preference read finishes.
                if (pollingJob != null && ownedAuthSession == config.session) {
                    pendingRequestedRevision = null
                    updateMatches()
                    return@launch
                }
                // A visible restart can replace an older credential generation in the
                // same instance without allowing its callbacks/cache to cross over.
                finish(preserveForegroundForPendingStart = true)
                stopped = false
                ownedAuthSession = config.session
                observedAuthSession = config.session
                pendingRequestedRevision = null
                hasSearched = false
                searchError = null
                searching = false
                sessionId = sequence.incrementAndGet()
                publish(RideRecognitionPhase.WAITING_FOR_LOCATION, "Warte auf einen frischen, präzisen Standort.")
                if (!requestLocations()) { finish(); return@launch }
                val currentSession = sessionId
                pollingJob = scope.launch {
                    while (isActive && current(currentSession)) {
                        if (!isAllowed() || !hasPreciseLocation() || !locationServicesEnabled()) {
                            if (current(currentSession)) finish()
                            break
                        }
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
                        if (!hasPreciseLocation() || !locationServicesEnabled() || !isAllowed()) {
                            if (current(currentSession)) finish()
                            break
                        }
                        updateMatches()
                    }
                }
            } finally { startRequests.finish(startRequest) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recognitionAllowed(config: TrackingConfiguration): Boolean = config.recognitionEnabled &&
        config.session.accessToken != null && config.activeStatusId == null

    private suspend fun isAllowed(): Boolean {
        val config = prefs.trackingConfiguration.first()
        observedAuthSession = config.session
        return !stopped && recognitionAllowed(config) && ownedAuthSession == config.session
    }

    private fun current(expected: Long): Boolean = !stopped && sessionId == expected &&
        ownedAuthSession != null && ownedAuthSession == observedAuthSession
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
                    if (!current(callbackSession) || !isAllowed() || !current(callbackSession)) return@launch
                    result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { location ->
                        val now = System.currentTimeMillis()
                        val observation = locationClock.observe(
                            location.latitude, location.longitude,
                            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                            location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(), now,
                            location.hasSpeed(), location.speed.toDouble()
                        )
                        if (observation.clockChanged) {
                            ++observationRevision
                            engine.clear()
                            tripCache.clear()
                            hasSearched = false
                            searchError = null
                            searching = false
                        }
                        if (observation.isNew) observation.fix?.let { engine.onLocation(it, now) }
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

    private suspend fun discover(fix: LocationFix, expected: Long) {
        val revision = observationRevision
        fun currentSearch(): Boolean = current(expected) && observationRevision == revision
        if (!currentSearch() || !isAllowed() || !currentSearch()) return
        searching = true
        if (_state.value.candidates.isEmpty()) publish(RideRecognitionPhase.SEARCHING, "Suche zeitlich passende Fahrten in der Nähe …")
        try {
            val cancelledTrips = mutableSetOf<String>()
            val found = withTimeout(45_000L) {
                val now = System.currentTimeMillis()
                val stations = repo.getNearbyStations(fix.latitude, fix.longitude).getOrThrow()
                    .filter { it.id != null }.distinctBy { it.id }.take(MAX_NEARBY_STATIONS)
                val departureRequests = RideDiscoveryRequests()
                val departures = stations.flatMap { station ->
                    currentCoroutineContext().ensureActive()
                    departureRequests.valueOrNull(repo.getStationDepartures(
                        station.id!!, Instant.ofEpochMilli(now - 5 * 60_000L).toString()
                    )).orEmpty().map { station to it }
                }.also { departureRequests.throwIfAllFailed() }.onEach { (_, departure) ->
                    if (departure.cancelled == true) cancelledTrips += departure.tripId
                }.filter { (_, departure) ->
                    val time = RideRecognitionEngine.parseTime(departure.realWhen ?: departure.plannedWhen)
                    departure.cancelled != true && time != null &&
                        time in (now - 8 * 60_000L)..(now + 2 * 60_000L)
                }.distinctBy { (station, dep) -> "${dep.tripId}|${dep.line?.name}|${dep.station?.id ?: station.id}|${dep.plannedWhen}" }
                    .sortedBy { (_, dep) -> kotlin.math.abs(now - (RideRecognitionEngine.parseTime(dep.realWhen ?: dep.plannedWhen) ?: now)) }
                    .take(MAX_DEPARTURES)
                currentCoroutineContext().ensureActive()
                if (!currentSearch() || !isAllowed() || !currentSearch()) return@withTimeout emptyList<RecognizableRide>()
                // Successful departure responses remain authoritative even when
                // subsequent trip-detail lookups fail or time out.
                engine.removeTrips(cancelledTrips)
                val rides = mutableListOf<RecognizableRide>()
                val detailRequests = RideDiscoveryRequests()
                tripCache.entries.removeAll { now - it.value.first > RideRecognitionEngine.ROUTE_TTL_MILLIS }
                for ((station, departure) in departures) {
                    currentCoroutineContext().ensureActive()
                    if (!currentSearch() || !isAllowed() || !currentSearch()) return@withTimeout emptyList<RecognizableRide>()
                    val key = "${departure.tripId}|${departure.line?.name.orEmpty()}"
                    val cached = tripCache[key]
                    val details = detailRequests.valueOrNull(cached?.let { Result.success(it.second) }
                        ?: repo.getTrip(departure.tripId, departure.line?.name.orEmpty())) ?: continue
                    if (!currentSearch() || !isAllowed() || !currentSearch()) return@withTimeout emptyList<RecognizableRide>()
                    if (cached == null) {
                        tripCache[key] = System.currentTimeMillis() to details
                        while (tripCache.size > RideRecognitionEngine.MAX_ROUTES) tripCache.remove(tripCache.keys.first())
                    }
                    val originIndex = RideRecognitionEngine.resolveOriginIndex(details.stopovers.orEmpty(), departure, station)
                    if (originIndex >= 0) rides += RecognizableRide(departure, details, originIndex, cached?.first ?: System.currentTimeMillis())
                }
                detailRequests.throwIfAllFailed()
                rides
            }
            // A new login, route, disabled setting or destroyed service invalidates in-flight reads.
            if (!currentSearch() || !isAllowed() || !currentSearch()) return
            hasSearched = true
            searching = false
            searchError = null
            engine.updateRides(found, System.currentTimeMillis())
            updateMatches()
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            if (currentSearch() && isAllowed() && currentSearch()) {
                searching = false
                searchError = "Die Fahrtsuche dauerte zu lange. Neuer Versuch in 90 Sekunden."
                publish(RideRecognitionPhase.ERROR, searchError!!)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (currentSearch() && isAllowed() && currentSearch()) {
                searching = false
                searchError = "Keine Stations- oder Fahrtdaten erhalten. Neuer Versuch in 90 Sekunden."
                publish(RideRecognitionPhase.ERROR, searchError!!)
            }
        }
    }

    private fun updateMatches() {
        if (!current(sessionId)) return
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
        if (!current(sessionId)) return
        _state.value = RideRecognitionState(phase, message, candidates, sessionId, ownedAuthSession?.revision)
        val text = if (candidates.isNotEmpty()) {
            if (candidates.size == 1) "Mögliche Fahrt: ${candidates.first().ride.departure.line?.name ?: "Linie unbekannt"}. Zum Prüfen öffnen."
            else "${candidates.size} mögliche Fahrten. Zum Auswählen öffnen."
        } else message
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(message: String): android.app.Notification {
        val revision = ownedAuthSession?.revision ?: pendingRequestedRevision
        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_RECOGNITION, revision != null)
            .putExtra(EXTRA_AUTH_SESSION_REVISION, revision)
            .setData(Uri.parse("routely://recognition/$revision/open"))
            .setAction("de.traewelling.app.OPEN_RIDE_RECOGNITION")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 711, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = stopIntent(this).putExtra(EXTRA_AUTH_SESSION_REVISION, revision)
            .setData(Uri.parse("routely://recognition/$revision/stop"))
        val stop = PendingIntent.getService(this, 712, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(de.traewelling.app.R.drawable.traewelling_logo)
            .setContentTitle("Routely · Fahrterkennung")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(content).addAction(0, "Suche stoppen", stop)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    private fun finish(preserveForegroundForPendingStart: Boolean = false) {
        val endingSessionId = sessionId
        stopped = !preserveForegroundForPendingStart
        sessionId = sequence.incrementAndGet()
        ++observationRevision
        locationClock = TrackingLocationClock()
        engine.clear()
        tripCache.clear()
        callback?.let { runCatching { locationClient.removeLocationUpdates(it) } }
        callback = null
        pollingJob?.cancel()
        tickJob?.cancel()
        pollingJob = null
        tickJob = null
        ownedAuthSession = null
        if (_state.value.sessionId == endingSessionId) _state.value = RideRecognitionState()
        if (!preserveForegroundForPendingStart) {
            pendingRequestedRevision = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        startRequests.clear()
        finish()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ride_recognition"
        private const val NOTIFICATION_ID = 710
        private const val ACTION_STOP = "de.traewelling.app.STOP_RIDE_RECOGNITION"
        const val EXTRA_OPEN_RECOGNITION = "open_ride_recognition"
        const val EXTRA_AUTH_SESSION_REVISION = "extra_auth_session_revision"
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
