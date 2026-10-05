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
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
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
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val progress: TrackingProgress
)

class TripTrackingService : Service(), TextToSpeech.OnInitListener {
    // Main confinement makes API refreshes, location callbacks and cursor changes atomic.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val gson = Gson()
    private lateinit var prefs: PreferencesManager
    private lateinit var repo: TraewellingRepository
    private lateinit var locationClient: FusedLocationProviderClient

    private var trackingJob: Job? = null
    private var tickJob: Job? = null
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
    private var engine: StationTrackingEngine? = null
    private var lastSavedJson: String? = null
    private var pendingAnnouncement: Pair<TrackingStop, TrackingSource>? = null
    private var completionJob: Job? = null
    private var completionUtteranceId: String? = null
    private var completionStatusId: Int? = null

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private val activeUtterances = mutableSetOf<String>()
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            tts?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        repo = TraewellingRepository(applicationContext, prefs)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        serviceScope.launch {
            gpsPreferenceEnabled = prefs.getGpsTrackingEnabled()
            radiusMeters = prefs.getAnnouncementRadiusMeters()
            val selectedEngine = prefs.getTtsEngine()
            tts = if (selectedEngine == null) TextToSpeech(this@TripTrackingService, this@TripTrackingService)
                else TextToSpeech(this@TripTrackingService, this@TripTrackingService, selectedEngine)
        }
        serviceScope.launch {
            prefs.gpsTrackingEnabled.distinctUntilChanged().collect { enabled ->
                gpsPreferenceEnabled = enabled
                // Re-enabling needs an explicit, permission-checked start from the visible Activity.
                if (!enabled) {
                    gpsRequestedByActivity = false
                    engine?.setGpsEnabled(false)
                    disableLocationUpdates()
                    if (currentStatusId != null) promoteToForeground(false)
                }
            }
        }
        serviceScope.launch {
            prefs.announcementRadiusMeters.distinctUntilChanged().collect { radius ->
                radiusMeters = radius
                engine?.setRadiusMeters(radius)
            }
        }
        serviceScope.launch {
            prefs.activeStatusId.distinctUntilChanged().collect { activeId ->
                val trackedId = currentStatusId
                if (trackedId != null && trackedId != activeId && pendingRequestedStatusId != activeId) {
                    finishService(trackedId)
                } else if (trackedId != null && activeId == null) {
                    finishService(trackedId)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        serviceScope.launch {
            configureSpeech()
            isTtsInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    serviceScope.launch {
                        utteranceId?.let { activeUtterances.remove(it) }
                        abandonAudioFocus()
                        completeAfterSpeech(utteranceId)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    serviceScope.launch {
                        utteranceId?.let { activeUtterances.remove(it) }
                        abandonAudioFocus()
                        completeAfterSpeech(utteranceId)
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    serviceScope.launch {
                        utteranceId?.let { activeUtterances.remove(it) }
                        abandonAudioFocus()
                        completeAfterSpeech(utteranceId)
                    }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    serviceScope.launch {
                        utteranceId?.let { activeUtterances.remove(it) }
                        abandonAudioFocus()
                        completeAfterSpeech(utteranceId)
                    }
                }
            })
            pendingAnnouncement?.let { (stop, source) ->
                pendingAnnouncement = null
                if (engine?.getProgress()?.nextStopKey != stop.key) return@let
                val utterance = speakStop(stop, source)
                if (utterance != null) {
                    engine?.acknowledgeAnnouncement(stop.key)
                    currentStatusId?.let { saveProgress(it) }
                }
                if (completionStatusId != null) {
                    completionUtteranceId = utterance
                    if (utterance == null) completionStatusId?.let { stopTracking(it) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val thisCommand = ++commandVersion
        val requestedId = intent?.getIntExtra(EXTRA_STATUS_ID, -1)?.takeIf { it > 0 }
        pendingRequestedStatusId = requestedId
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                val expectedId = currentStatusId ?: prefs.activeStatusId.first()
                if (expectedId != null) stopTracking(expectedId) else finishService(null)
            }
            return START_NOT_STICKY
        }

        // A sticky restart has no visible-Activity authorization to create a location FGS.
        gpsRequestedByActivity = intent?.getBooleanExtra(EXTRA_ENABLE_GPS, false) == true && hasPreciseLocation()
        if (!promoteToForeground(gpsRequestedByActivity)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        engine?.setGpsEnabled(gpsPreferenceEnabled)
        if (!gpsRequestedByActivity) {
            engine?.invalidateLocation()
            disableLocationUpdates()
        }
        serviceScope.launch {
            val statusId = requestedId ?: prefs.activeStatusId.first()
            val activeId = prefs.activeStatusId.first()
            if (thisCommand != commandVersion) return@launch
            if (statusId == null || activeId != statusId) {
                pendingRequestedStatusId = null
                if (currentStatusId == null) finishService(null)
                return@launch
            }
            startTracking(statusId)
            if (thisCommand == commandVersion) pendingRequestedStatusId = null
        }
        return START_STICKY
    }

    private fun promoteToForeground(useGps: Boolean): Boolean {
        val notification = createNotification("Lade Reisedaten…", if (useGps) "GPS-Tracking wird gestartet" else "Fahrplanmodus")
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
            stopping = false
            trackingJob?.cancel()
            tickJob?.cancel()
            completionJob?.cancel()
            completionStatusId = null
            completionUtteranceId = null
            disableLocationUpdates()
            cachedCheckin = null
            cachedStops = emptyList()
            engine = null
            latestLocation = null
            lastSavedJson = null
            pendingAnnouncement = null
            tts?.stop()
            activeUtterances.clear()
            abandonAudioFocus()
            restoreCachedTrip(statusId)
        }
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
                    if (!hasPreciseLocation() && callback != null) {
                        gpsRequestedByActivity = false
                        engine?.invalidateLocation()
                        disableLocationUpdates()
                        promoteToForeground(false)
                    }
                    engine?.onTimetable(System.currentTimeMillis())?.let { applyUpdate(it, statusId, expectedGeneration) }
                    delay(TICK_INTERVAL_MILLIS)
                }
            }
        }
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
        engine = StationTrackingEngine(toTrackingStops(cachedStops, cache.checkin), cache.progress, radiusMeters)
        lastSavedJson = gson.toJson(cache)
    }

    private suspend fun refreshTrip(statusId: Int, expectedGeneration: Long) {
        val status = repo.getStatusDetail(statusId).getOrNull() ?: return
        val checkin = status.checkin ?: return
        val tripId = checkin.trip ?: return
        val stops = repo.getStopovers(tripId).getOrNull() ?: return
        if (generation != expectedGeneration || currentStatusId != statusId || stopping) return
        val route = checkedInRoute(stops, checkin)
        if (route.isEmpty()) return
        cachedCheckin = checkin
        cachedStops = route
        val trackingStops = toTrackingStops(route, checkin)
        val existingEngine = engine
        if (existingEngine == null) engine = StationTrackingEngine(trackingStops, radiusMeters = radiusMeters)
        else existingEngine.updateRoute(trackingStops)
        val currentEngine = engine ?: return
        currentEngine.setGpsEnabled(gpsPreferenceEnabled)
        val update = latestLocation?.takeIf { isFreshLocation(it) }?.let { currentEngine.onLocation(toFix(it), System.currentTimeMillis()) }
            ?: currentEngine.onTimetable(System.currentTimeMillis())
        applyUpdate(update, statusId, expectedGeneration)
    }

    private fun checkedInRoute(stops: List<StopStation>, checkin: CheckinInfo): List<StopStation> {
        val originIndex = stops.indexOfFirst { it.matchesStopover(checkin.origin) }
        val destinationIndex = stops.indexOfFirst { it.matchesStopover(checkin.destination) }
        // Never track unrelated parts of the trip if the check-in boundaries cannot be resolved.
        if (originIndex < 0 || destinationIndex < originIndex) return emptyList()
        return stops.subList(originIndex, destinationIndex + 1).map { stop ->
            when {
                stop.matchesStopover(checkin.origin) -> stop.copy(
                    departureReal = checkin.manualDeparture ?: stop.departureReal
                )
                stop.matchesStopover(checkin.destination) -> stop.copy(
                    arrivalReal = checkin.manualArrival ?: stop.arrivalReal
                )
                else -> stop
            }
        }
    }

    private fun stopKey(stop: StopStation, index: Int): String = stop.uuid
        ?: "${stop.stationId ?: "unknown"}:${stop.arrivalPlanned}:${stop.departurePlanned}:$index"

    private fun toTrackingStops(stops: List<StopStation>, checkin: CheckinInfo): List<TrackingStop> =
        stops.mapIndexed { index, stop ->
            TrackingStop(
                key = stopKey(stop, index),
                stationId = stop.stationId,
                name = stop.stationName ?: "Unbekannte Station",
                latitude = stop.station?.latitude,
                longitude = stop.station?.longitude,
                plannedArrivalMillis = epochMillis(stop.arrivalPlanned),
                effectiveArrivalMillis = epochMillis(stop.effectiveArrival),
                effectiveDepartureMillis = epochMillis(stop.effectiveDeparture),
                cancelled = stop.cancelled == true,
                isOrigin = stop.matchesStopover(checkin.origin),
                isDestination = stop.matchesStopover(checkin.destination)
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
        disableLocationUpdates()
        locationIntervalMillis = intervalMillis
        val callbackGeneration = generation
        val callbackStatusId = currentStatusId ?: return
        val nextCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (callback !== this || generation != callbackGeneration || currentStatusId != callbackStatusId || stopping) return
                if (!hasPreciseLocation()) {
                    gpsRequestedByActivity = false
                    engine?.invalidateLocation()
                    disableLocationUpdates()
                    promoteToForeground(false)
                    return
                }
                for (location in result.locations) {
                    if (!isFreshLocation(location)) continue
                    latestLocation = location
                    val currentEngine = engine ?: continue
                    val update = currentEngine.onLocation(toFix(location), System.currentTimeMillis())
                    serviceScope.launch { applyUpdate(update, callbackStatusId, callbackGeneration) }
                    val stop = update.stop
                    val distance = if (stop?.latitude != null && stop.longitude != null) {
                        val target = Location("station").apply { latitude = stop.latitude; longitude = stop.longitude }
                        location.distanceTo(target)
                    } else Float.POSITIVE_INFINITY
                    val nextInterval = if (distance <= 3_000f) NEAR_INTERVAL_MILLIS else FAR_INTERVAL_MILLIS
                    if (locationIntervalMillis != nextInterval) {
                        requestLocationUpdates(nextInterval)
                        break
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

    private fun disableLocationUpdates() {
        val oldCallback = callback
        callback = null
        locationIntervalMillis = 0L
        latestLocation = null
        if (oldCallback != null) {
            runCatching { locationClient.removeLocationUpdates(oldCallback) }
        }
    }

    private suspend fun applyUpdate(update: TrackingUpdate, statusId: Int, expectedGeneration: Long) {
        if (generation != expectedGeneration || currentStatusId != statusId || stopping) return
        if (completionStatusId != null) return
        val checkin = cachedCheckin ?: return
        val nextStop = update.stop
        val nextName = nextStop?.name ?: checkin.destination?.stationName ?: "Unbekannt"
        val destinationName = checkin.destination?.stationName ?: ""
        val lineName = checkin.lineName ?: "Zug"
        val rawStop = nextStop?.let { target ->
            cachedStops.withIndex().firstOrNull { (index, raw) -> stopKey(raw, index) == target.key }?.value
        }
        val platform = rawStop?.arrivalPlatformReal ?: rawStop?.arrivalPlatformPlanned ?: rawStop?.platform
        val platformText = platform?.takeIf { it.isNotBlank() }?.let { " • Gl. $it" } ?: ""
        val destinationTime = nextStop?.effectiveArrivalMillis ?: nextStop?.effectiveDepartureMillis
        val effectiveSource = if (nextStop == null) TrackingSource.TIMETABLE else update.source
        val needsManualEnd = nextStop == null || (effectiveSource == TrackingSource.TIMETABLE && nextStop.isDestination &&
            (destinationTime == null || destinationTime <= System.currentTimeMillis()))
        val sourceLabel = if (effectiveSource == TrackingSource.GPS) "GPS"
            else if (needsManualEnd) "Fahrplan · ungefähr · Fahrt manuell beenden"
            else "Fahrplan · ungefähr"
        val time = (nextStop?.effectiveArrivalMillis ?: nextStop?.effectiveDepartureMillis)?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } ?: ""
        updateNotification("$lineName nach $destinationName", "Nächster Halt: $nextName $time$platformText • $sourceLabel")
        sendBroadcast(Intent(this, de.traewelling.app.widget.TripWidgetProvider::class.java).apply {
            action = "de.traewelling.app.ACTION_UPDATE_WIDGET"
            putExtra("lineName", lineName)
            putExtra("nextStop", "$nextName · $sourceLabel")
            putExtra("destination", destinationName)
            putExtra("time", time)
            putExtra("platform", platform)
            putExtra("delay", calculateDelay(rawStop) ?: -1)
        })
        var queuedUtterance: String? = null
        update.announcement?.let { announcedStop ->
            val speechEnabled = prefs.getTtsEnabled()
            if (speechEnabled && isTtsInitialized) queuedUtterance = speakStop(announcedStop, update.source)
            if (queuedUtterance == null) {
                engine?.releaseAnnouncement(announcedStop.key)
                if (speechEnabled && !isTtsInitialized) pendingAnnouncement = announcedStop to update.source
            }
        }
        saveProgress(statusId)
        if (generation != expectedGeneration || currentStatusId != statusId || stopping) return
        if (update.destinationReached) {
            val existingDestinationUtterance = update.stop?.takeIf { it.isDestination }
                ?.let { "stop:${it.key}" }?.takeIf { it in activeUtterances }
            if (queuedUtterance != null || existingDestinationUtterance != null || pendingAnnouncement != null) {
                awaitFinalSpeech(statusId, queuedUtterance ?: existingDestinationUtterance)
            } else stopTracking(statusId)
        }
    }

    private suspend fun saveProgress(statusId: Int) {
        val checkin = cachedCheckin ?: return
        val currentEngine = engine ?: return
        val json = gson.toJson(CachedTripTrackingState(statusId = statusId, checkin = checkin, stopovers = cachedStops, progress = currentEngine.getProgress()))
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
        val utteranceId = "stop:${stop.key}"
        if (utteranceId in activeUtterances) return utteranceId
        configureSpeech()
        if (currentStatusId != statusId || stopping || !prefs.getTtsEnabled()) return null
        val checkin = cachedCheckin ?: return null
        val platformStop = cachedStops.withIndex().firstOrNull { (index, raw) -> stopKey(raw, index) == stop.key }?.value
        val platform = platformStop?.arrivalPlatformReal ?: platformStop?.arrivalPlatformPlanned ?: platformStop?.platform
        val platformText = platform?.takeIf { it.isNotBlank() }?.let { " auf Gleis $it" } ?: ""
        val approximate = if (source == TrackingSource.TIMETABLE) "Voraussichtlich " else ""
        val announcement = when {
            stop.isOrigin -> "${approximate}Der ${checkin.lineName ?: "Zug"} erreicht in Kürze deine Anfangshaltestelle ${stop.name}. Bitte einsteigen."
            stop.isDestination -> "${approximate}Du erreichst in Kürze deine Ausstiegshaltestelle ${stop.name}$platformText."
            else -> "${approximate}Nächste Haltestelle in Kürze: ${stop.name}$platformText."
        }
        if (!requestAudioFocus()) return null
        return if (tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS) {
            activeUtterances += utteranceId
            utteranceId
        } else {
            abandonAudioFocus()
            null
        }
    }

    private fun awaitFinalSpeech(statusId: Int, utteranceId: String?) {
        completionStatusId = statusId
        completionUtteranceId = utteranceId
        trackingJob?.cancel()
        tickJob?.cancel()
        disableLocationUpdates()
        completionJob?.cancel()
        completionJob = serviceScope.launch {
            delay(FINAL_SPEECH_TIMEOUT_MILLIS)
            if (completionStatusId == statusId && currentStatusId == statusId) stopTracking(statusId)
        }
    }

    private suspend fun completeAfterSpeech(utteranceId: String?) {
        val statusId = completionStatusId ?: return
        if (utteranceId != null && utteranceId == completionUtteranceId && currentStatusId == statusId) {
            stopTracking(statusId)
        }
    }

    private fun calculateDelay(stop: StopStation?): Int? {
        val planned = epochMillis(stop?.arrivalPlanned) ?: return null
        val real = epochMillis(stop?.arrivalReal) ?: return null
        return ((real - planned) / 60_000L).toInt().takeIf { it > 0 }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build().also { audioFocusRequest = it }
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun createNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TripTrackingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content).setSmallIcon(R.drawable.traewelling_logo)
            .setContentIntent(openPendingIntent).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopPendingIntent)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live-Reiseinformationen", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Nächster Halt, Standortmodus und Zeiten der aktiven Fahrt" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private suspend fun stopTracking(expectedStatusId: Int) {
        if (currentStatusId != null && currentStatusId != expectedStatusId) return
        stopping = true
        disableLocationUpdates()
        // Commit the atomic ID+progress clear before stopping/cancelling our own scope.
        prefs.clearActiveTracking(expectedStatusId)
        finishService(expectedStatusId)
    }

    private fun finishService(expectedStatusId: Int?) {
        if (expectedStatusId != null && currentStatusId != null && currentStatusId != expectedStatusId) return
        stopping = true
        generation++
        trackingJob?.cancel()
        tickJob?.cancel()
        completionJob?.cancel()
        completionStatusId = null
        completionUtteranceId = null
        pendingRequestedStatusId = null
        disableLocationUpdates()
        pendingAnnouncement = null
        tts?.stop()
        activeUtterances.clear()
        abandonAudioFocus()
        currentStatusId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        disableLocationUpdates()
        abandonAudioFocus()
        serviceJob.cancel()
        tts?.stop()
        activeUtterances.clear()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "TripTrackingChannel"
        private const val NOTIFICATION_ID = 1001
        private const val API_POLL_INTERVAL_MILLIS = 60_000L
        private const val TICK_INTERVAL_MILLIS = 10_000L
        private const val NEAR_INTERVAL_MILLIS = 3_000L
        private const val FAR_INTERVAL_MILLIS = 12_000L
        private const val FINAL_SPEECH_TIMEOUT_MILLIS = 15_000L
        const val EXTRA_STATUS_ID = "extra_status_id"
        const val EXTRA_ENABLE_GPS = "extra_enable_gps"
        const val ACTION_STOP = "action_stop"
    }
}
