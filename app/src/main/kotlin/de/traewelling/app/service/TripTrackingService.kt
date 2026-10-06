package de.traewelling.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import de.traewelling.app.MainActivity
import de.traewelling.app.R
import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.sev.SevJourneyEnricher
import de.traewelling.app.data.sev.SevStopResolver
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Only route metadata and visit progress are stored; device locations stay in memory. */
private data class CachedTripTrackingState(
    val version: Int = 1,
    val statusId: Int,
    val checkin: CheckinInfo,
    val stopovers: List<StopStation>,
    val progress: TrackingProgress,
    val changes: TripChangeMonitorState? = null,
    val liveUpdateDismissed: Boolean = false,
    val fullStopovers: List<StopStation>? = null,
    val sevMaps: Map<String, SevMap>? = null
)

class TripTrackingService : Service(), TextToSpeech.OnInitListener {
    // The mutex also covers suspension points between cursor mutation and publication/persistence.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val trackingMutex = Mutex()
    private val gson = Gson()
    private lateinit var prefs: PreferencesManager
    private lateinit var repo: TraewellingRepository
    private lateinit var locationClient: FusedLocationProviderClient

    private var trackingJob: Job? = null
    private var tickJob: Job? = null
    private var sevJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var trackingWakeLock: TrackingWakeLockLease? = null
    private var currentStatusId: Int? = null
    private var lastStartId: Int = 0
    private var commandVersion = 0L
    private var pendingRequestedStatusId: Int? = null
    private var generation = 0L
    private var stopping = false
    private var gpsRequestedByActivity = false
    private var gpsPreferenceEnabled = true
    private var radiusMeters = 0
    private var callback: LocationCallback? = null
    private var locationIntervalMillis = 0L
    private var latestLocation: Location? = null
    private var cachedCheckin: CheckinInfo? = null
    private var cachedStops: List<StopStation> = emptyList()
    private var cachedFullStops: List<StopStation> = emptyList()
    private var cachedSevMaps: Map<String, SevMap> = emptyMap()
    private var cachedSevStops: Map<String, SevStopInfo> = emptyMap()
    private var engine: StationTrackingEngine? = null
    private var lastSavedJson: String? = null
    private var pendingAnnouncement: Pair<TrackingStop, TrackingSource>? = null
    private var completionJob: Job? = null
    private var completionUtteranceId: String? = null
    private var completionStatusId: Int? = null

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private val speechDeliveries = SpeechDeliveryQueue()
    private val tripChanges = TripChangeMonitor()
    private val gpsJourneyTimes = GpsJourneyTimeEstimator()
    private var liveProgressEnabled = true
    private var lockScreenDetailsEnabled = true
    private var liveUpdateDismissed = false
    private var lastProgressModel: TripProgressModel? = null
    private var lastNotificationTitle = "Routely"
    private var lastNotificationText = "Lade Reisedaten…"
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            serviceScope.launch { tts?.stop() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        repo = TraewellingRepository(applicationContext, prefs)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        serviceScope.launch {
            prefs.liveProgressEnabled.distinctUntilChanged().collect { enabled ->
                trackingMutex.withLock {
                    liveProgressEnabled = enabled
                    if (currentStatusId != null) updateNotification(lastNotificationTitle, lastNotificationText)
                }
            }
        }
        serviceScope.launch {
            prefs.lockScreenDetailsEnabled.distinctUntilChanged().collect { enabled ->
                trackingMutex.withLock {
                    lockScreenDetailsEnabled = enabled
                    if (!enabled) clearChangeNotifications(this@TripTrackingService)
                    if (currentStatusId != null) updateNotification(lastNotificationTitle, lastNotificationText)
                }
            }
        }
        serviceScope.launch {
            prefs.tripChangeAlertsEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) clearChangeNotifications(this@TripTrackingService)
            }
        }
        serviceScope.launch {
            val selectedEngine = prefs.getTtsEngine()
            tts = if (selectedEngine == null) TextToSpeech(this@TripTrackingService, this@TripTrackingService)
                else TextToSpeech(this@TripTrackingService, this@TripTrackingService, selectedEngine)
        }
        serviceScope.launch {
            prefs.gpsTrackingEnabled.distinctUntilChanged().collect { enabled ->
                trackingMutex.withLock {
                    gpsPreferenceEnabled = enabled
                    // Re-enabling needs an explicit, permission-checked start from the visible Activity.
                    if (!enabled) {
                        gpsRequestedByActivity = false
                        engine?.setGpsEnabled(false)
                        disableLocationUpdates()
                        currentStatusId?.let { statusId ->
                            if (!promoteToForeground(false)) {
                                finishService(statusId)
                                return@withLock
                            }
                            engine?.onTimetable(System.currentTimeMillis())?.let {
                                applyUpdate(it, statusId, generation)
                            }
                        }
                    }
                }
            }
        }
        serviceScope.launch {
            prefs.announcementRadiusMeters.distinctUntilChanged().collect { radius ->
                trackingMutex.withLock {
                    radiusMeters = radius
                    engine?.setRadiusMeters(radius)
                }
            }
        }
        serviceScope.launch {
            prefs.activeStatusId.distinctUntilChanged().collect {
                trackingMutex.withLock {
                    // A queued emission may predate a trip switch that held this mutex.
                    val activeId = prefs.activeStatusId.first()
                    val trackedId = currentStatusId
                    if (trackedId != null && trackedId != activeId && pendingRequestedStatusId != activeId) {
                        finishService(trackedId)
                    } else if (trackedId != null && activeId == null) {
                        finishService(trackedId)
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        serviceScope.launch {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handleSpeechFinished(utteranceId, successful = true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleSpeechFinished(utteranceId, successful = false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleSpeechFinished(utteranceId, successful = false)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handleSpeechFinished(utteranceId, successful = false)
                }
            })
            trackingMutex.withLock {
                configureSpeech()
                tts?.setAudioAttributes(speechAudioAttributes())
                isTtsInitialized = true
                pendingAnnouncement?.let { (stop, source) ->
                    pendingAnnouncement = null
                    if (engine?.getProgress()?.nextStopKey != stop.key) return@let
                    val utterance = speakStop(stop, source)
                    if (utterance != null) {
                        engine?.acknowledgeAnnouncement(stop.key)
                        currentStatusId?.let { saveProgress(it) }
                    }
                    if (completionStatusId != null) {
                        completionUtteranceId = utterance?.takeIf(speechDeliveries::contains)
                        if (completionUtteranceId == null) completionStatusId?.let { stopTracking(it) }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_DISMISS_LIVE_UPDATE) {
            val requestedId = intent.getIntExtra(EXTRA_STATUS_ID, -1)
            if (currentStatusId == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            serviceScope.launch {
                trackingMutex.withLock {
                    if (currentStatusId == requestedId) {
                        liveUpdateDismissed = true
                        saveProgress(requestedId)
                        updateNotification(lastNotificationTitle, lastNotificationText)
                    }
                }
            }
            return START_STICKY
        }
        val thisCommand = ++commandVersion
        val requestedId = intent?.getIntExtra(EXTRA_STATUS_ID, -1)?.takeIf { it > 0 }
        pendingRequestedStatusId = requestedId
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                val expectedId = requestedId ?: currentStatusId ?: prefs.activeStatusId.first()
                trackingMutex.withLock {
                    if (expectedId != null) stopTracking(expectedId) else finishService(null)
                }
            }
            return START_NOT_STICKY
        }

        // A sticky restart has no visible-Activity authorization to create a location FGS.
        gpsRequestedByActivity = intent?.getBooleanExtra(EXTRA_ENABLE_GPS, false) == true && hasPreciseLocation()
        if (!promoteToForeground(gpsRequestedByActivity, requestedId ?: currentStatusId)) {
            finishService(null)
            return START_NOT_STICKY
        }
        serviceScope.launch {
            try {
                val statusId = requestedId ?: prefs.activeStatusId.first()
                trackingMutex.withLock {
                    val activeId = prefs.activeStatusId.first()
                    if (thisCommand != commandVersion) return@withLock
                    if (statusId == null || activeId != statusId) {
                        pendingRequestedStatusId = null
                        if (currentStatusId == null) finishService(null)
                        return@withLock
                    }
                    engine?.setGpsEnabled(gpsPreferenceEnabled)
                    if (!gpsRequestedByActivity) {
                        engine?.invalidateLocation()
                        disableLocationUpdates()
                    }
                    startTracking(statusId)
                    if (thisCommand == commandVersion) pendingRequestedStatusId = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                trackingMutex.withLock {
                    // A failed old start must not tear down a replacement trip.
                    if (thisCommand == commandVersion) finishService(null)
                }
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(useGps: Boolean, statusIdForNotification: Int? = currentStatusId): Boolean {
        val notification = if (lastProgressModel != null && statusIdForNotification == currentStatusId) {
            createNotification(lastNotificationTitle, lastNotificationText)
        } else createNotification("Lade Reisedaten…", if (useGps) "GPS-Tracking wird gestartet" else "Fahrplanmodus",
            model = null, statusIdForNotification = statusIdForNotification)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (useGps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (_: SecurityException) {
            gpsRequestedByActivity = false
            disableLocationUpdates()
            // Permission revoked or the app is no longer eligible for location access.
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else startForeground(NOTIFICATION_ID, notification)
                true
            } catch (_: RuntimeException) { false }
        } catch (_: RuntimeException) {
            return false
        }
    }

    private suspend fun startTracking(statusId: Int) {
        if (currentStatusId != statusId) {
            generation++
            currentStatusId = statusId
            mutableTrackingLiveState.value = null
            stopping = false
            trackingJob?.cancel()
            tickJob?.cancel()
            sevJob?.cancel()
            releaseTrackingWakeLock()
            completionJob?.cancel()
            completionStatusId = null
            completionUtteranceId = null
            disableLocationUpdates()
            cachedCheckin = null
            cachedStops = emptyList()
            cachedFullStops = emptyList()
            cachedSevMaps = emptyMap()
            cachedSevStops = emptyMap()
            engine = null
            gpsJourneyTimes.reset()
            latestLocation = null
            lastSavedJson = null
            lastProgressModel = null
            lastNotificationTitle = "Routely"
            lastNotificationText = "Lade Reisedaten…"
            liveUpdateDismissed = false
            tripChanges.reset(statusId)
            publishWidgetWaiting("Lade Reisedaten…")
            pendingAnnouncement = null
            tts?.stop()
            speechDeliveries.clear()
            abandonAudioFocus()
            // Foreground promotion already succeeded and the active status was checked.
            ensureTrackingWakeLock()
            restoreCachedTrip(statusId)
            updateNotification(lastNotificationTitle, lastNotificationText)
        }
        ensureTrackingWakeLock()
        // Resuming the Activity during the final utterance must not restart GPS/polling.
        if (completionStatusId != null) return
        engine?.setGpsEnabled(gpsPreferenceEnabled)
        if (gpsRequestedByActivity && gpsPreferenceEnabled && hasPreciseLocation() && callback == null) {
            requestLocationUpdates(FAR_INTERVAL_MILLIS)
        }
        if (trackingJob?.isActive != true) {
            val expectedGeneration = generation
            trackingJob = serviceScope.launch {
                while (isActive && generation == expectedGeneration && currentStatusId == statusId) {
                    try {
                        refreshTrip(statusId, expectedGeneration)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the last successful route available while offline.
                    }
                    delay(API_POLL_INTERVAL_MILLIS)
                }
            }
        }
        if (tickJob?.isActive != true) {
            val expectedGeneration = generation
            tickJob = serviceScope.launch {
                while (isActive && generation == expectedGeneration && currentStatusId == statusId) {
                    trackingMutex.withLock {
                        if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null) return@withLock
                        if (!hasPreciseLocation() && callback != null) {
                            gpsRequestedByActivity = false
                            engine?.invalidateLocation()
                            disableLocationUpdates()
                            if (!promoteToForeground(false)) {
                                finishService(statusId)
                                return@withLock
                            }
                        }
                        revalidateSevStops(System.currentTimeMillis())
                        engine?.onTimetable(System.currentTimeMillis())?.let { applyUpdate(it, statusId, expectedGeneration) }
                    }
                    delay(TICK_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun ensureTrackingWakeLock() {
        if (currentStatusId == null || stopping || wakeLockRenewalJob?.isActive == true) return
        val lease = trackingWakeLock ?: runCatching {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Routely:TripTracking")
                .apply { setReferenceCounted(false) }
            TrackingWakeLockLease(object : TrackingWakeLockHandle {
                override fun acquire(timeoutMillis: Long) {
                    try {
                        wakeLock.acquire(timeoutMillis)
                    } catch (e: RuntimeException) {
                        Log.w(LOG_TAG, "Unable to acquire active trip CPU lock", e)
                        throw e
                    }
                }
                override fun release() {
                    // A platform timeout may already have released this lease.
                    try {
                        if (wakeLock.isHeld) wakeLock.release()
                    } catch (e: RuntimeException) {
                        Log.w(LOG_TAG, "Unable to release active trip CPU lock", e)
                        throw e
                    }
                }
            })
        }.onFailure {
            Log.w(LOG_TAG, "Unable to create active trip CPU lock", it)
        }.getOrNull()?.also { trackingWakeLock = it } ?: return
        val ownerGeneration = generation
        lease.start(ownerGeneration)
        wakeLockRenewalJob = serviceScope.launch {
            while (isActive) {
                delay(TrackingWakeLockLease.RENEW_INTERVAL_MILLIS)
                if (generation != ownerGeneration || currentStatusId == null || stopping) break
                // This keeps the CPU available; it never wakes or holds the display.
                lease.renew(ownerGeneration)
            }
        }
    }

    private fun releaseTrackingWakeLock() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        trackingWakeLock?.stop()
    }

    private suspend fun restoreCachedTrip(statusId: Int) {
        val cache = runCatching {
            gson.fromJson(prefs.getTrackingState(), CachedTripTrackingState::class.java)
        }.getOrNull() ?: return
        val valid = runCatching {
            cache.version == 1 && cache.statusId == statusId && cache.stopovers.isNotEmpty() &&
                cache.checkin.trip != null && cache.progress.nextIndex in 0..cache.stopovers.size &&
                cache.progress.announcedKeys.none { it.isBlank() }
        }.getOrDefault(false)
        if (!valid || currentStatusId != statusId) return
        cachedCheckin = cache.checkin
        cachedStops = cache.stopovers
        cachedFullStops = cache.fullStopovers ?: cache.stopovers
        cachedSevMaps = cache.sevMaps ?: emptyMap()
        revalidateSevStops(System.currentTimeMillis())
        // A restored SEV point can have expired or moved while the process was dead.
        // No location evidence survives a restart, so require a fresh physical arrival.
        val restoredProgress = if (cachedSevMaps.isNotEmpty()) cache.progress.copy(arrivedAtCurrent = false)
            else cache.progress
        engine = StationTrackingEngine(toTrackingStops(cachedStops, cache.checkin), restoredProgress, radiusMeters)
        runCatching { tripChanges.reset(statusId, cache.changes) }.onFailure { tripChanges.reset(statusId) }
        liveUpdateDismissed = cache.liveUpdateDismissed
        lastSavedJson = gson.toJson(cache)
    }

    private suspend fun refreshTrip(statusId: Int, expectedGeneration: Long) {
        val status = repo.getStatusDetail(statusId).getOrNull() ?: return
        val checkin = status.checkin ?: return
        val tripId = checkin.trip ?: return
        val stops = repo.getStopovers(tripId).getOrNull() ?: return
        val rawRoute = checkedInRoute(stops, checkin, applyManualTimes = false)
        val route = checkedInRoute(stops, checkin)
        if (route.isEmpty()) return
        trackingMutex.withLock {
            if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null) return@withLock
            val previousProgress = engine?.getProgress()
            val currentKeyIndex = previousProgress?.nextStopKey?.let { key ->
                route.withIndex().firstOrNull { (index, stop) -> stopKey(stop, index) == key }?.index
            }
            val nextIndex = currentKeyIndex ?: previousProgress?.nextIndex ?: 0
            val changes = tripChanges.observe(TripChangeSnapshot.fromApi(
                statusId, SystemClock.elapsedRealtime(), rawRoute, checkin, nextIndex
            ))
            if (cachedCheckin?.manualArrival != checkin.manualArrival ||
                cachedCheckin?.manualDeparture != checkin.manualDeparture
            ) gpsJourneyTimes.invalidateLocation()
            cachedCheckin = checkin
            cachedStops = route
            cachedFullStops = stops
            revalidateSevStops(System.currentTimeMillis())
            val trackingStops = toTrackingStops(route, checkin)
            val existingEngine = engine
            if (existingEngine == null) engine = StationTrackingEngine(trackingStops, radiusMeters = radiusMeters)
            else existingEngine.updateRoute(trackingStops)
            val currentEngine = engine ?: return@withLock
            val relevantChanges = if (SevStopResolver.isReplacementBus(checkin))
                changes.filterNot { it.kind == TripChangeKind.PLATFORM } else changes
            deliverTripChanges(relevantChanges, statusId, expectedGeneration)
            if (!isCurrentTracking(statusId, expectedGeneration)) return@withLock
            currentEngine.setGpsEnabled(gpsPreferenceEnabled)
            val update = latestLocation?.takeIf { isFreshLocation(it) }?.let {
                currentEngine.onLocation(toFix(it), System.currentTimeMillis())
            } ?: currentEngine.onTimetable(System.currentTimeMillis())
            applyUpdate(update, statusId, expectedGeneration)
            scheduleSevEnrichment(statusId, expectedGeneration, checkin, stops)
        }
    }

    private fun scheduleSevEnrichment(
        statusId: Int, expectedGeneration: Long, checkin: CheckinInfo, fullRoute: List<StopStation>
    ) {
        if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null) return
        if (!SevStopResolver.isReplacementBus(checkin)) {
            sevJob?.cancel()
            cachedSevMaps = emptyMap()
            return
        }
        if (sevJob?.isActive == true) return
        sevJob = serviceScope.launch {
            try {
                val maps = SevJourneyEnricher.loadMaps(checkin, fullRoute)
                trackingMutex.withLock {
                    if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null ||
                        cachedCheckin != checkin || cachedFullStops != fullRoute
                    ) return@withLock
                    // Failed requests retain usable offline snapshots until the resolver's expiry.
                    cachedSevMaps = (cachedSevMaps + maps).filterKeys { slug ->
                        cachedStops.any { it.station?.let(SevStopResolver::stationSlug) == slug }
                    }
                    revalidateSevStops(System.currentTimeMillis())
                    val currentEngine = engine ?: return@withLock
                    // Do not reuse the old fix as new evidence after changing a physical stop.
                    applyUpdate(currentEngine.onTimetable(System.currentTimeMillis()), statusId, expectedGeneration)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional website metadata must never stop normal tracking.
            }
        }
    }

    private fun revalidateSevStops(nowMillis: Long) {
        val checkin = cachedCheckin ?: return
        val resolved = runCatching {
            SevStopResolver.resolve(checkin, cachedFullStops, cachedSevMaps, nowMillis)
        }.getOrDefault(emptyMap())
        if (resolved == cachedSevStops) return
        val coordinatesChanged = (resolved.keys + cachedSevStops.keys).any { key ->
            resolved[key]?.latitude != cachedSevStops[key]?.latitude ||
                resolved[key]?.longitude != cachedSevStops[key]?.longitude
        }
        cachedSevStops = resolved
        engine?.updateRoute(toTrackingStops(cachedStops, checkin))
        if (coordinatesChanged) gpsJourneyTimes.invalidateLocation()
    }

    private fun checkedInRoute(stops: List<StopStation>, checkin: CheckinInfo, applyManualTimes: Boolean = true): List<StopStation> {
        val originIndex = stops.indexOfFirst { it.matchesStopover(checkin.origin) }
        val destinationIndex = stops.indexOfFirst { it.matchesStopover(checkin.destination) }
        // Never track unrelated parts of the trip if the check-in boundaries cannot be resolved.
        if (originIndex < 0 || destinationIndex < originIndex) return emptyList()
        val route = stops.subList(originIndex, destinationIndex + 1)
        if (!applyManualTimes) return route
        return route.map { stop ->
            stop.copy(
                departureReal = if (stop.matchesStopover(checkin.origin))
                    checkin.manualDeparture ?: stop.departureReal else stop.departureReal,
                arrivalReal = if (stop.matchesStopover(checkin.destination))
                    checkin.manualArrival ?: stop.arrivalReal else stop.arrivalReal
            )
        }
    }

    private fun stopKey(stop: StopStation, index: Int): String = stop.uuid
        ?: "${stop.stationId ?: "unknown"}:${stop.arrivalPlanned}:${stop.departurePlanned}:$index"

    private fun toTrackingStops(stops: List<StopStation>, checkin: CheckinInfo): List<TrackingStop> =
        stops.mapIndexed { index, stop ->
            val sev = cachedSevStops[SevStopResolver.visitKey(stop)]?.takeIf { it.hasCoordinates }
            TrackingStop(
                key = stopKey(stop, index),
                stationId = stop.stationId,
                name = stop.stationName ?: "Unbekannte Station",
                latitude = sev?.latitude ?: stop.station?.latitude,
                longitude = sev?.longitude ?: stop.station?.longitude,
                plannedArrivalMillis = epochMillis(stop.arrivalPlanned),
                effectiveArrivalMillis = epochMillis(stop.arrivalReal) ?: epochMillis(stop.arrivalPlanned),
                effectiveDepartureMillis = epochMillis(stop.departureReal) ?: epochMillis(stop.departurePlanned),
                cancelled = stop.cancelled == true,
                isOrigin = stop.matchesStopover(checkin.origin),
                isDestination = stop.matchesStopover(checkin.destination),
                plannedDepartureMillis = epochMillis(stop.departurePlanned)
            )
        }

    private fun epochMillis(time: String?): Long? = time?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun hasPreciseLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isFreshLocation(location: Location): Boolean {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        return ageMillis in 0..StationTrackingEngine.MAX_FIX_AGE_MILLIS && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy in 0f..StationTrackingEngine.MAX_ACCURACY_METERS.toFloat()
    }

    private fun toFix(location: Location): LocationFix = LocationFix(
        location.latitude,
        location.longitude,
        location.accuracy.toDouble(),
        location.time,
        location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0f }?.toDouble()
    )

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMillis: Long) {
        if (!gpsRequestedByActivity || !gpsPreferenceEnabled || !hasPreciseLocation() || stopping || currentStatusId == null) {
            disableLocationUpdates()
            return
        }
        if (callback != null && locationIntervalMillis == intervalMillis) return
        disableLocationUpdates(clearLocation = false)
        locationIntervalMillis = intervalMillis
        val callbackGeneration = generation
        val callbackStatusId = currentStatusId ?: return
        val nextCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val emittingCallback = this
                val locations = result.locations.sortedBy { it.elapsedRealtimeNanos }
                serviceScope.launch {
                    trackingMutex.withLock {
                        if (callback !== emittingCallback || !isCurrentTracking(callbackStatusId, callbackGeneration) ||
                            completionStatusId != null || !gpsRequestedByActivity || !gpsPreferenceEnabled
                        ) return@withLock
                        if (!hasPreciseLocation()) {
                            gpsRequestedByActivity = false
                            engine?.invalidateLocation()
                            disableLocationUpdates()
                            if (!promoteToForeground(false)) finishService(callbackStatusId)
                            return@withLock
                        }
                        var nextInterval = locationIntervalMillis
                        for (location in locations) {
                            if (!isCurrentTracking(callbackStatusId, callbackGeneration) || completionStatusId != null) break
                            if (!isFreshLocation(location)) continue
                            latestLocation = location
                            revalidateSevStops(System.currentTimeMillis())
                            val currentEngine = engine ?: continue
                            val update = currentEngine.onLocation(toFix(location), System.currentTimeMillis())
                            applyUpdate(update, callbackStatusId, callbackGeneration)
                            val stop = update.stop
                            val distance = if (stop?.latitude != null && stop.longitude != null) {
                                val target = Location("station").apply { latitude = stop.latitude; longitude = stop.longitude }
                                location.distanceTo(target)
                            } else Float.POSITIVE_INFINITY
                            nextInterval = if (distance <= 3_000f) NEAR_INTERVAL_MILLIS else FAR_INTERVAL_MILLIS
                        }
                        // Changing frequency must not discard the remaining fixes in this batch.
                        if (isCurrentTracking(callbackStatusId, callbackGeneration) && completionStatusId == null &&
                            locationIntervalMillis != nextInterval && nextInterval > 0L
                        ) requestLocationUpdates(nextInterval)
                    }
                }
            }
        }
        callback = nextCallback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(if (intervalMillis == NEAR_INTERVAL_MILLIS) 2_000L else 5_000L)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            locationClient.requestLocationUpdates(request, nextCallback, Looper.getMainLooper())
                .addOnFailureListener {
                    if (callback === nextCallback) disableLocationUpdates()
                }
        } catch (_: SecurityException) {
            disableLocationUpdates()
        } catch (_: RuntimeException) {
            disableLocationUpdates()
        }
    }

    private fun disableLocationUpdates(clearLocation: Boolean = true) {
        val oldCallback = callback
        callback = null
        locationIntervalMillis = 0L
        if (clearLocation) {
            latestLocation = null
            gpsJourneyTimes.invalidateLocation()
        }
        if (oldCallback != null) {
            runCatching { locationClient.removeLocationUpdates(oldCallback) }
        }
    }

    private suspend fun applyUpdate(update: TrackingUpdate, statusId: Int, expectedGeneration: Long) {
        if (!isCurrentTracking(statusId, expectedGeneration)) return
        if (completionStatusId != null) return
        val checkin = cachedCheckin ?: return
        val nextStop = update.stop
        val nextName = nextStop?.name ?: checkin.destination?.stationName ?: "Unbekannt"
        val destinationName = checkin.destination?.stationName ?: ""
        val lineName = checkin.lineName ?: "Zug"
        val rawStop = nextStop?.let { target ->
            cachedStops.withIndex().firstOrNull { (index, raw) -> stopKey(raw, index) == target.key }?.value
        }
        val progress = engine?.getProgress() ?: return
        val nowMillis = System.currentTimeMillis()
        val gpsTimes = gpsJourneyTimes.update(
            route = toTrackingStops(cachedStops, checkin),
            progress = progress,
            source = update.source,
            fix = latestLocation?.takeIf {
                gpsRequestedByActivity && gpsPreferenceEnabled && isFreshLocation(it)
            }?.let(::toFix),
            nowMillis = nowMillis
        )
        val liveState = TrackingLiveState(
            statusId = statusId,
            nextStopKey = progress.nextStopKey,
            nextIndex = progress.nextIndex,
            stop = rawStop,
            arrivedAtCurrent = progress.arrivedAtCurrent,
            completed = progress.completed,
            source = update.source,
            gpsTimes = gpsTimes,
            sevStops = cachedSevStops
        )
        mutableTrackingLiveState.value = liveState
        lastProgressModel = TripProgressModel.from(cachedStops, liveState, destinationName,
            manualDestinationArrival = checkin.manualArrival, nowMillis = nowMillis)
        val platform = if (SevStopResolver.isReplacementBus(checkin)) null else
            rawStop?.arrivalPlatformReal ?: rawStop?.arrivalPlatformPlanned ?: rawStop?.platform
        val platformText = platform?.takeIf { it.isNotBlank() }?.let { " • Gl. $it" } ?: ""
        val manualArrival = checkin.manualArrival.takeIf { rawStop?.matchesStopover(checkin.destination) == true }
        val manualDeparture = checkin.manualDeparture.takeIf { rawStop?.matchesStopover(checkin.origin) == true }
        val arrival = JourneyTimeResolver.arrival(rawStop, gpsTimes, nowMillis, manualArrival)
        val departure = JourneyTimeResolver.departure(rawStop, gpsTimes, nowMillis, manualDeparture)
        val nextTime = if (nextStop?.isOrigin == true) departure ?: arrival else arrival ?: departure
        val destinationTime = nextTime?.millis
        val effectiveSource = if (nextStop == null) TrackingSource.TIMETABLE else update.source
        val needsManualEnd = nextStop == null || (effectiveSource == TrackingSource.TIMETABLE && nextStop.isDestination &&
            (destinationTime == null || destinationTime <= nowMillis))
        val sourceLabel = if (effectiveSource == TrackingSource.GPS) "GPS"
            else if (needsManualEnd) "Fahrplan · ungefähr · Fahrt manuell beenden"
            else "Fahrplan · ungefähr"
        val time = nextTime?.let {
            Instant.ofEpochMilli(it.millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } ?: ""
        val labelledTime = nextTime?.let { "$time (${it.sourceLabel})" } ?: ""
        val stopLabel = if (effectiveSource == TrackingSource.GPS && progress.arrivedAtCurrent) "Aktueller Halt" else "Nächster Halt"
        updateNotification("$lineName nach $destinationName", "$stopLabel: $nextName $labelledTime$platformText • $sourceLabel")
        sendBroadcast(Intent(this, de.traewelling.app.widget.TripWidgetProvider::class.java).apply {
            action = "de.traewelling.app.ACTION_UPDATE_WIDGET"
            putExtra("lineName", lineName)
            putExtra("nextStop", nextName)
            putExtra("destination", destinationName)
            putExtra("time", time)
            putExtra("timeSource", nextTime?.sourceLabel ?: sourceLabel)
            putExtra("platform", platform)
            nextTime?.delayMinutes?.let {
                putExtra("delay", it.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            }
        })
        var queuedUtterance: String? = null
        update.announcement?.let { announcedStop ->
            val speechEnabled = prefs.getTtsEnabled()
            if (!isCurrentTracking(statusId, expectedGeneration)) return
            if (speechEnabled && isTtsInitialized) queuedUtterance = speakStop(announcedStop, update.source)
            if (queuedUtterance == null) {
                engine?.releaseAnnouncement(announcedStop.key)
                if (speechEnabled && !isTtsInitialized) pendingAnnouncement = announcedStop to update.source
            }
        }
        saveProgress(statusId)
        if (!isCurrentTracking(statusId, expectedGeneration)) return
        if (update.destinationReached) {
            val existingDestinationUtterance = update.stop?.takeIf { it.isDestination }
                ?.let { speechDeliveries.find(statusId, expectedGeneration, it.key)?.utteranceId }
            val activeQueuedUtterance = queuedUtterance?.takeIf(speechDeliveries::contains)
            if (activeQueuedUtterance != null || existingDestinationUtterance != null || pendingAnnouncement != null) {
                awaitFinalSpeech(statusId, activeQueuedUtterance ?: existingDestinationUtterance)
            } else stopTracking(statusId)
        }
    }

    private fun isCurrentTracking(statusId: Int, expectedGeneration: Long): Boolean =
        generation == expectedGeneration && currentStatusId == statusId && !stopping

    private suspend fun saveProgress(statusId: Int) {
        val checkin = cachedCheckin ?: return
        val currentEngine = engine ?: return
        val json = gson.toJson(CachedTripTrackingState(statusId = statusId, checkin = checkin, stopovers = cachedStops,
            progress = currentEngine.getProgress(), changes = tripChanges.getState(), liveUpdateDismissed = liveUpdateDismissed,
            fullStopovers = cachedFullStops, sevMaps = cachedSevMaps))
        if (json != lastSavedJson) {
            prefs.saveTrackingState(statusId, json)
            if (currentStatusId == statusId) lastSavedJson = json
        }
    }

    private suspend fun configureSpeech() {
        val language = prefs.getTtsLanguage()?.let(Locale::forLanguageTag) ?: Locale.GERMAN
        tts?.setLanguage(language)
        prefs.getTtsVoice()?.let { voiceName ->
            tts?.voices?.firstOrNull { it.name == voiceName }?.let { tts?.voice = it }
        }
    }

    private suspend fun speakStop(stop: TrackingStop, source: TrackingSource): String? {
        val statusId = currentStatusId ?: return null
        val expectedGeneration = generation
        speechDeliveries.find(statusId, expectedGeneration, stop.key)?.let { return it.utteranceId }
        configureSpeech()
        if (!prefs.getTtsEnabled() || !isCurrentTracking(statusId, expectedGeneration)) return null
        val checkin = cachedCheckin ?: return null
        val platformStop = cachedStops.withIndex().firstOrNull { (index, raw) -> stopKey(raw, index) == stop.key }?.value
        val platform = if (SevStopResolver.isReplacementBus(checkin)) null else if (stop.isOrigin) {
            platformStop?.departurePlatformReal ?: platformStop?.departurePlatformPlanned ?: platformStop?.platform
        } else platformStop?.arrivalPlatformReal ?: platformStop?.arrivalPlatformPlanned ?: platformStop?.platform
        val platformText = platform?.takeIf { it.isNotBlank() }?.let { " auf Gleis $it" } ?: ""
        val approximate = if (source == TrackingSource.TIMETABLE) "Voraussichtlich " else ""
        val departureTime = stop.effectiveDepartureMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        val announcement = when {
            stop.isOrigin -> {
                val line = checkin.lineName?.takeIf { it.isNotBlank() }?.let { " mit der Linie $it" }.orEmpty()
                val departure = if (source == TrackingSource.TIMETABLE) "Voraussichtliche Abfahrt" else "Abfahrt"
                "Deine Fahrt$line startet in Kürze in ${stop.name}. $departure${departureTime?.let { " um $it" }.orEmpty()}$platformText. Bitte mach dich zum Einsteigen bereit."
            }
            stop.isDestination -> "${approximate}Du erreichst in Kürze deine Ausstiegshaltestelle ${stop.name}$platformText."
            else -> "${approximate}Nächste Haltestelle in Kürze: ${stop.name}$platformText."
        }
        // Preference/voice reads and TTS initialization can outlive the original
        // trigger. Never replay boarding advice once departure or movement passed it.
        if (stop.isOrigin && engine?.isOriginAnnouncementRelevant(stop.key, source, System.currentTimeMillis()) != true) return null
        // Retry while boarding advice is still timely instead of putting it
        // behind an ongoing change/stop utterance that could finish after departure.
        if (stop.isOrigin && !speechDeliveries.isEmpty) return null
        if (!requestAudioFocus()) return null
        val delivery = speechDeliveries.begin(statusId, expectedGeneration, stop.key)
        return if (tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, delivery.utteranceId) == TextToSpeech.SUCCESS) {
            delivery.utteranceId
        } else {
            speechDeliveries.finish(delivery.utteranceId)
            if (speechDeliveries.isEmpty) abandonAudioFocus()
            null
        }
    }

    private suspend fun deliverTripChanges(changes: List<TripChangeEvent>, statusId: Int, expectedGeneration: Long) {
        if (changes.isEmpty() || !prefs.getTripChangeAlertsEnabled() || !isCurrentTracking(statusId, expectedGeneration)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val title = if (changes.size == 1) changes.first().title else "${changes.size} Änderungen auf deiner Fahrt"
        val message = changes.joinToString("\n") { it.message }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "de.traewelling.app.OPEN_STATUS.$statusId"
            putExtra("open_status_id", statusId)
        }
        val openPendingIntent = PendingIntent.getActivity(this, statusId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (permissionGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val publicVersion = NotificationCompat.Builder(this, CHANGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.traewelling_logo).setContentTitle("Routely")
                .setContentText("Neue Änderungen auf deiner Fahrt").build()
            val notification = NotificationCompat.Builder(this, CHANGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.traewelling_logo).setContentTitle(title).setContentText(changes.first().message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openPendingIntent).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(if (lockScreenDetailsEnabled) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            runCatching { manager.notify("trip_changes:$statusId", CHANGES_NOTIFICATION_ID, notification) }
        }
        if (!isTtsInitialized || !prefs.getTtsEnabled() || !prefs.getTripChangeSpeechEnabled() ||
            !isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null
        ) return
        configureSpeech()
        if (!isCurrentTracking(statusId, expectedGeneration) || !requestAudioFocus()) return
        val eventKey = changes.joinToString("|") { it.key }
        val delivery = speechDeliveries.begin(statusId, expectedGeneration, eventKey, SpeechDeliveryKind.TRIP_CHANGE)
        if (tts?.speak(message, TextToSpeech.QUEUE_ADD, null, delivery.utteranceId) != TextToSpeech.SUCCESS) {
            speechDeliveries.finish(delivery.utteranceId)
            if (speechDeliveries.isEmpty) abandonAudioFocus()
        }
    }

    private fun handleSpeechFinished(utteranceId: String?, successful: Boolean) {
        if (utteranceId == null) return
        serviceScope.launch {
            trackingMutex.withLock {
                val delivery = speechDeliveries.finish(utteranceId) ?: return@withLock
                // QUEUE_ADD entries share the focus until the last entry has finished.
                if (speechDeliveries.isEmpty) abandonAudioFocus()
                if (!isCurrentTracking(delivery.statusId, delivery.generation)) return@withLock
                if (!successful && delivery.kind == SpeechDeliveryKind.STOP && engine?.getProgress()?.nextStopKey == delivery.stopKey) {
                    engine?.releaseAnnouncement(delivery.stopKey)
                    saveProgress(delivery.statusId)
                }
                completeAfterSpeech(utteranceId)
            }
        }
    }

    private fun awaitFinalSpeech(statusId: Int, utteranceId: String?) {
        completionStatusId = statusId
        completionUtteranceId = utteranceId
        trackingJob?.cancel()
        tickJob?.cancel()
        sevJob?.cancel()
        disableLocationUpdates()
        completionJob?.cancel()
        completionJob = serviceScope.launch {
            delay(FINAL_SPEECH_TIMEOUT_MILLIS)
            trackingMutex.withLock {
                if (completionStatusId == statusId && currentStatusId == statusId) stopTracking(statusId)
            }
        }
    }

    private suspend fun completeAfterSpeech(utteranceId: String?) {
        val statusId = completionStatusId ?: return
        if (utteranceId != null && utteranceId == completionUtteranceId && currentStatusId == statusId) {
            stopTracking(statusId)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes())
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build().also { audioFocusRequest = it }
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun abandonAudioFocus() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun updateNotification(title: String, content: String) {
        lastNotificationTitle = title
        lastNotificationText = content
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(title, content))
        }
    }

    private fun createNotification(title: String, content: String, model: TripProgressModel? = lastProgressModel,
                                   statusIdForNotification: Int? = currentStatusId): Notification {
        val statusId = statusIdForNotification ?: -1
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "de.traewelling.app.OPEN_STATUS.$statusId"
            if (statusId > 0) putExtra("open_status_id", statusId)
        }
        val openPendingIntent = PendingIntent.getActivity(this, statusId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_STATUS_ID, statusId)
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deleteIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_DISMISS_LIVE_UPDATE
            putExtra(EXTRA_STATUS_ID, statusId)
        }
        val deletePendingIntent = PendingIntent.getService(this, 2, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return TripProgressNotificationBuilder.build(
            context = this, channelId = CHANNEL_ID, title = title, text = content, model = model,
            showLockScreenDetails = lockScreenDetailsEnabled,
            requestLiveUpdate = liveProgressEnabled && !liveUpdateDismissed,
            stopPendingIntent = stopPendingIntent, openPendingIntent = openPendingIntent,
            deletePendingIntent = deletePendingIntent
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live-Reiseinformationen", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Nächster Halt, Standortmodus und Zeiten der aktiven Fahrt" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            val changesChannel = NotificationChannel(CHANGES_CHANNEL_ID, "Änderungen auf deiner Fahrt", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Gleiswechsel, ausfallende Halte und deutliche Änderungen der Verspätung" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(changesChannel)
        }
    }

    private suspend fun stopTracking(expectedStatusId: Int) {
        if (currentStatusId != null && currentStatusId != expectedStatusId) return
        stopping = true
        disableLocationUpdates()
        // Keep the current bounded CPU lease through the atomic ID+progress clear.
        // Renewal sees stopping=true, so a stalled commit cannot extend the lease.
        try {
            prefs.clearActiveTracking(expectedStatusId)
        } finally {
            // Storage failure must not leave an idle foreground service running.
            finishService(expectedStatusId)
        }
    }

    private fun finishService(expectedStatusId: Int?) {
        if (expectedStatusId != null && currentStatusId != null && currentStatusId != expectedStatusId) return
        stopping = true
        releaseTrackingWakeLock()
        generation++
        trackingJob?.cancel()
        tickJob?.cancel()
        sevJob?.cancel()
        completionJob?.cancel()
        completionStatusId = null
        completionUtteranceId = null
        pendingRequestedStatusId = null
        disableLocationUpdates()
        pendingAnnouncement = null
        tts?.stop()
        speechDeliveries.clear()
        abandonAudioFocus()
        currentStatusId = null
        lastProgressModel = null
        mutableTrackingLiveState.value = null
        publishWidgetWaiting()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        releaseTrackingWakeLock()
        disableLocationUpdates()
        publishWidgetWaiting()
        abandonAudioFocus()
        serviceJob.cancel()
        tts?.stop()
        speechDeliveries.clear()
        mutableTrackingLiveState.value = null
        tts?.shutdown()
        super.onDestroy()
    }

    private fun publishWidgetWaiting(message: String = "Warte auf Check-in…") {
        sendBroadcast(Intent(this, de.traewelling.app.widget.TripWidgetProvider::class.java).apply {
            action = "de.traewelling.app.ACTION_UPDATE_WIDGET"
            putExtra("lineName", "Routely")
            putExtra("nextStop", message)
            putExtra("destination", "")
            putExtra("time", "")
            putExtra("timeSource", "")
            putExtra("platform", "")
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Settings also call this when the service is no longer alive. */
        fun clearChangeNotifications(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.activeNotifications.filter { it.notification.channelId == CHANGES_CHANNEL_ID }
                        .forEach { manager.cancel(it.tag, it.id) }
                }
            }
        }

        private val mutableTrackingLiveState = MutableStateFlow<TrackingLiveState?>(null)
        val trackingLiveState: StateFlow<TrackingLiveState?> = mutableTrackingLiveState.asStateFlow()
        private const val LOG_TAG = "TripTrackingService"
        private const val CHANNEL_ID = "TripTrackingChannel"
        private const val NOTIFICATION_ID = 1001
        private const val CHANGES_CHANNEL_ID = "TripChangesChannel"
        private const val CHANGES_NOTIFICATION_ID = 1002
        private const val API_POLL_INTERVAL_MILLIS = 60_000L
        private const val TICK_INTERVAL_MILLIS = 10_000L
        private const val NEAR_INTERVAL_MILLIS = 3_000L
        private const val FAR_INTERVAL_MILLIS = 12_000L
        private const val FINAL_SPEECH_TIMEOUT_MILLIS = 15_000L
        const val EXTRA_STATUS_ID = "extra_status_id"
        const val EXTRA_ENABLE_GPS = "extra_enable_gps"
        const val ACTION_STOP = "action_stop"
        const val ACTION_DISMISS_LIVE_UPDATE = "DISMISS_LIVE_UPDATE"
    }
}
