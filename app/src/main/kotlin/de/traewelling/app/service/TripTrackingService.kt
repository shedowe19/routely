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
import android.location.LocationManager
import android.net.Uri
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
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.sev.SevJourneyEnricher
import de.traewelling.app.data.sev.SevStopResolver
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.data.repository.StatusMutationEvents
import de.traewelling.app.data.repository.StatusMutationSnapshot
import de.traewelling.app.data.routing.RoadRouteRepository
import de.traewelling.app.data.routing.TransitRouteRepository
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.util.AuthSession
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
import kotlinx.coroutines.flow.combine
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

class TripTrackingService : Service() {
    // The mutex also covers suspension points between cursor mutation and publication/persistence.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val trackingMutex = Mutex()
    private val gson = Gson()
    private lateinit var prefs: PreferencesManager
    private lateinit var repo: TraewellingRepository
    private lateinit var locationClient: FusedLocationProviderClient

    private var trackingJob: Job? = null
    private var mutationRefreshJob: Job? = null
    private val activeStatusBridge = ActiveTripStatusBridge()
    private var tickJob: Job? = null
    private var sevJob: Job? = null
    private var roadRouteJob: Job? = null
    private var roadRequestWindow: List<RoadSegmentRequest> = emptyList()
    private val cachedRoadRoutes = linkedMapOf<RoadSegmentRequest, RoadRouteGeometry>()
    private val roadAttempts = linkedMapOf<RoadSegmentRequest, Long>()
    private var transitRouteJob: Job? = null
    private var transitRouteRepository: TransitRouteRepository? = null
    private val transitRoutes = TransitRouteTrackingCache()
    private var lastTransitAttemptMillis: Long? = null
    private var wakeLockRenewalJob: Job? = null
    private var trackingWakeLock: TrackingWakeLockLease? = null
    private var currentStatusId: Int? = null
    private var lastStartId: Int = 0
    private var commandVersion = 0L
    private var pendingRequestedStatusId: Int? = null
    private var pendingRequestedRevision: String? = null
    private var currentAuthSession: AuthSession? = null
    private var observedAuthSession: AuthSession? = null
    private val startRequests = SessionStartRequests()
    private var generation = 0L
    private var stopping = false
    private var gpsRequestedByActivity = false
    private var gpsPreferenceEnabled = true
    private var radiusMeters = 0
    private var callback: LocationCallback? = null
    private var locationIntervalMillis = 0L
    private var locationRegistrationOwner = 0L
    private val locationRetries = LifecycleRetryBudget()
    private var locationRetryJob: Job? = null
    private var locationError: String? = null
    private var latestLocation: Location? = null
    private var locationClock = TrackingLocationClock()
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
    private var speechConfiguration = TrackingSpeechConfiguration()
    private val speechLifecycle = SpeechInitializationLifecycle()
    private var speechRetryJob: Job? = null
    private var speechInitTimeoutJob: Job? = null
    private var speechError: String? = null
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
            StatusMutationEvents.events.collect { event ->
                if (event is StatusMutation.LikeChanged || !activeStatusBridge.owns(event)) return@collect
                val statusId = currentStatusId ?: return@collect
                val ownerGeneration = generation
                trackingMutex.withLock {
                    if (!isCurrentTracking(statusId, ownerGeneration) || !activeStatusBridge.owns(event)) return@withLock
                    // Read the authoritative ledger: this event may already have been superseded.
                    ensureCurrentTripSnapshot(statusId, ownerGeneration)
                    requestMutationRefresh(statusId, ownerGeneration)
                }
            }
        }
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
            combine(prefs.isTtsEnabled, prefs.ttsEngine, prefs.ttsLanguage, prefs.ttsVoice) {
                enabled, engine, language, voice -> TrackingSpeechConfiguration(enabled,
                    engine?.takeIf { it.isNotBlank() }, language?.takeIf { it.isNotBlank() },
                    voice?.takeIf { it.isNotBlank() })
            }.distinctUntilChanged().collect { configuration ->
                trackingMutex.withLock {
                    val previous = speechConfiguration
                    speechConfiguration = configuration
                    if (currentStatusId == null || stopping) return@withLock
                    if (previous.enabled != configuration.enabled || previous.engine != configuration.engine || tts == null) {
                        restartSpeechEngine(configuration.enabled)
                    } else if (previous != configuration && speechLifecycle.state == SpeechEngineState.READY) {
                        cancelQueuedSpeech(releaseCurrentAnnouncement = true)
                        isTtsInitialized = configureSpeech()
                        replayPendingAnnouncement()
                    }
                    currentStatusId?.let { saveProgress(it) }
                    publishRuntimeIssues()
                    if (!configuration.enabled && completionStatusId != null) completionStatusId?.let { completeTracking(it) }
                }
            }
        }
        serviceScope.launch {
            prefs.gpsTrackingEnabled.distinctUntilChanged().collect { enabled ->
                trackingMutex.withLock {
                    gpsPreferenceEnabled = enabled
                    // Re-enabling needs an explicit, permission-checked start from the visible Activity.
                    if (!enabled) {
                        gpsRequestedByActivity = false
                        locationError = null
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
            prefs.trackingConfiguration.collect { emitted ->
                val config = prefs.trackingConfiguration.first()
                if (config != emitted) return@collect
                observedAuthSession = config.session
                // Invalidate immediately, even while another coroutine holds the
                // tracking mutex across preference/TTS reads. Numeric IDs can
                // repeat on another account or server.
                if (currentAuthSession?.let { it != config.session } == true) {
                    clearChangeNotifications(this@TripTrackingService)
                    val pendingStartMatches = config.activeStatusId != null && config.session.accessToken != null &&
                        startRequests.hasPending(config.session.revision, config.activeStatusId)
                    finishService(currentStatusId, preserveForegroundForPendingStart = pendingStartMatches)
                }
                trackingMutex.withLock {
                    // A queued emission may predate a trip switch that held this mutex.
                    val latest = prefs.trackingConfiguration.first()
                    observedAuthSession = latest.session
                    if (currentAuthSession?.let { it != latest.session } == true) {
                        val pendingStartMatches = latest.activeStatusId != null && latest.session.accessToken != null &&
                            startRequests.hasPending(latest.session.revision, latest.activeStatusId)
                        finishService(currentStatusId, preserveForegroundForPendingStart = pendingStartMatches)
                    }
                    val activeId = latest.activeStatusId
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

    private fun restartSpeechEngine(enabled: Boolean) {
        speechRetryJob?.cancel()
        speechInitTimeoutJob?.cancel()
        speechLifecycle.restart(enabled)
        cancelQueuedSpeech(releaseCurrentAnnouncement = true)
        closeSpeechInstance()
        speechError = null
        if (!enabled) pendingAnnouncement = null
        else startSpeechEngineAttempt()
    }

    private fun startSpeechEngineAttempt() {
        val statusId = currentStatusId ?: return
        val expectedGeneration = generation
        val session = currentAuthSession ?: return
        val token = speechLifecycle.beginAttempt() ?: return
        val listener = TextToSpeech.OnInitListener { status ->
            serviceScope.launch {
                trackingMutex.withLock {
                    if (!speechLifecycle.owns(token) || !isCurrentTracking(statusId, expectedGeneration) ||
                        prefs.getAuthSession() != session) return@withLock
                    if (status == TextToSpeech.SUCCESS) onSpeechInitialized(token)
                    else speechInitializationFailed(token)
                }
            }
        }
        try {
            tts = speechConfiguration.engine?.let { TextToSpeech(this, listener, it) }
                ?: TextToSpeech(this, listener)
        } catch (_: RuntimeException) {
            speechInitializationFailed(token)
            return
        }
        speechInitTimeoutJob?.cancel()
        speechInitTimeoutJob = serviceScope.launch {
            delay(SPEECH_INIT_TIMEOUT_MILLIS)
            trackingMutex.withLock {
                if (isCurrentTracking(statusId, expectedGeneration) && speechLifecycle.owns(token) &&
                    speechLifecycle.state == SpeechEngineState.INITIALIZING) speechInitializationFailed(token)
            }
        }
    }

    private suspend fun onSpeechInitialized(token: Long) {
        if (!speechLifecycle.owns(token)) return
        val instance = tts ?: return
        val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handleSpeechFinished(utteranceId, successful = true, initializationToken = token)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleSpeechFinished(utteranceId, successful = false, initializationToken = token)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleSpeechFinished(utteranceId, successful = false, initializationToken = token)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handleSpeechFinished(utteranceId, successful = false, initializationToken = token)
                }
            }
        val prepared = runCatching {
            instance.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS &&
                instance.setAudioAttributes(speechAudioAttributes()) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!prepared) { speechInitializationFailed(token); return }
        if (!speechLifecycle.ready(token)) return
        speechInitTimeoutJob?.cancel()
        isTtsInitialized = configureSpeech()
        replayPendingAnnouncement()
        publishRuntimeIssues()
    }

    private fun speechInitializationFailed(token: Long) {
        if (!speechLifecycle.owns(token) || speechLifecycle.state != SpeechEngineState.INITIALIZING) return
        speechInitTimeoutJob?.cancel()
        val retryDelay = speechLifecycle.failed(token)
        closeSpeechInstance()
        speechError = if (retryDelay == null) "Sprachausgabe nicht verfügbar. Sprachengine in den Einstellungen prüfen."
            else "Sprachausgabe startet nicht. Erneuter Versuch folgt."
        publishRuntimeIssues()
        val statusId = currentStatusId ?: return
        val expectedGeneration = generation
        speechRetryJob?.cancel()
        if (retryDelay != null) speechRetryJob = serviceScope.launch {
            delay(retryDelay)
            trackingMutex.withLock {
                if (isCurrentTracking(statusId, expectedGeneration) && speechConfiguration.enabled &&
                    speechLifecycle.state == SpeechEngineState.RETRY_WAIT) startSpeechEngineAttempt()
            }
        }
    }

    private fun closeSpeechInstance() {
        val instance = tts
        tts = null
        isTtsInitialized = false
        runCatching { instance?.stop() }
        runCatching { instance?.shutdown() }
    }

    private fun cancelQueuedSpeech(releaseCurrentAnnouncement: Boolean) {
        val statusId = currentStatusId
        val key = engine?.getProgress()?.nextStopKey
        if (releaseCurrentAnnouncement && statusId != null && key != null &&
            speechDeliveries.find(statusId, generation, key) != null) {
            engine?.releaseAnnouncement(key)
            val checkin = cachedCheckin
            val stop = checkin?.let { toTrackingStops(cachedStops, it).firstOrNull { stop -> stop.key == key } }
            if (stop != null) pendingAnnouncement = stop to (mutableTrackingLiveState.value?.source ?: TrackingSource.TIMETABLE)
        }
        runCatching { tts?.stop() }
        speechDeliveries.clear()
        completionUtteranceId = null
        abandonAudioFocus()
    }

    private suspend fun replayPendingAnnouncement() {
        if (!isTtsInitialized) return
        pendingAnnouncement?.let { (stop, source) ->
            pendingAnnouncement = null
            if (engine?.getProgress()?.nextStopKey != stop.key) return@let
            val checkin = cachedCheckin ?: return@let
            val currentStop = toTrackingStops(cachedStops, checkin).singleOrNull { it.key == stop.key && !it.cancelled }
                ?: return@let
            // A returning fix may belong to a later visit while the protected
            // cursor still waits for independent evidence.
            if (engine?.isReacquiringLocation() == true) return@let
            val utterance = speakStop(currentStop, source)
            if (utterance != null) {
                engine?.acknowledgeAnnouncement(stop.key)
                currentStatusId?.let { saveProgress(it) }
            }
            if (completionStatusId != null) {
                completionUtteranceId = utterance?.takeIf(speechDeliveries::contains)
                if (completionUtteranceId == null) completionStatusId?.let { completeTracking(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!serviceJob.isActive) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISMISS_LIVE_UPDATE) {
            val requestedId = intent.getIntExtra(EXTRA_STATUS_ID, -1)
            val requestedRevision = intent.getStringExtra(EXTRA_AUTH_SESSION_REVISION)
            if (currentStatusId == null) {
                if (pendingRequestedStatusId == null) stopSelf(startId)
                return if (pendingRequestedStatusId == null) START_NOT_STICKY else START_STICKY
            }
            serviceScope.launch {
                trackingMutex.withLock {
                    val ownedSession = currentAuthSession
                    if (currentStatusId == requestedId && ownedSession != null &&
                        requestedRevision == ownedSession.revision && prefs.getAuthSession() == ownedSession) {
                        liveUpdateDismissed = true
                        saveProgress(requestedId)
                        updateNotification(lastNotificationTitle, lastNotificationText)
                    }
                }
            }
            return START_STICKY
        }
        val requestedId = intent?.getIntExtra(EXTRA_STATUS_ID, -1)?.takeIf { it > 0 }
        val requestedRevision = intent?.getStringExtra(EXTRA_AUTH_SESSION_REVISION)
        if (intent?.action == ACTION_STOP) {
            val stopCommandVersion = commandVersion
            val ownedSession = currentAuthSession
            val stopGeneration = generation
            serviceScope.launch {
                trackingMutex.withLock {
                    val config = prefs.trackingConfiguration.first()
                    if ((stopCommandVersion != commandVersion && ownedSession != null) ||
                        (generation != stopGeneration && (ownedSession != null || requestedRevision == null)) ||
                        (requestedId != null && requestedId != config.activeStatusId) ||
                        requestedRevision != config.session.revision ||
                        (ownedSession != null && ownedSession != config.session)) return@withLock
                    val expectedId = requestedId ?: currentStatusId ?: config.activeStatusId
                    commandVersion++
                    startRequests.clear()
                    if (expectedId != null) stopTracking(expectedId, ownedSession ?: config.session) else finishService(null)
                }
            }
            return START_NOT_STICKY
        }
        val startRequest = startRequests.begin(requestedRevision, requestedId)
        pendingRequestedStatusId = requestedId
        pendingRequestedRevision = requestedRevision

        // A sticky restart has no visible-Activity authorization to create a location FGS.
        val requestedGps = intent?.getBooleanExtra(EXTRA_ENABLE_GPS, false) == true && hasPreciseLocation()
        if (!promoteToForeground(requestedGps, requestedId ?: currentStatusId,
                allowModelReuse = requestedRevision != null && requestedRevision == currentAuthSession?.revision)) {
            finishService(null)
            return START_NOT_STICKY
        }
        serviceScope.launch {
            var acceptedCommand: Long? = null
            var acceptedSession: AuthSession? = null
            try {
                trackingMutex.withLock {
                    val config = prefs.trackingConfiguration.first()
                    val statusId = requestedId ?: config.activeStatusId
                    if (statusId == null || config.activeStatusId != statusId || config.session.accessToken == null ||
                        !startRequests.accept(startRequest, config.session.revision)) {
                        startRequests.finish(startRequest)
                        val matchingStartPending = config.activeStatusId != null &&
                            startRequests.hasPending(config.session.revision, config.activeStatusId)
                        pendingRequestedStatusId = config.activeStatusId.takeIf { matchingStartPending }
                        pendingRequestedRevision = config.session.revision.takeIf { matchingStartPending }
                        if (currentStatusId == null && !matchingStartPending) finishService(null)
                        else {
                            if (currentStatusId != null) {
                                promoteToForeground(gpsRequestedByActivity && gpsPreferenceEnabled)
                                updateNotification(lastNotificationTitle, lastNotificationText)
                            }
                        }
                        return@withLock
                    }
                    val thisCommand = ++commandVersion
                    acceptedCommand = thisCommand
                    acceptedSession = config.session
                    observedAuthSession = config.session
                    pendingRequestedStatusId = statusId
                    pendingRequestedRevision = config.session.revision
                    gpsRequestedByActivity = requestedGps
                    if (!promoteToForeground(gpsRequestedByActivity, statusId,
                            allowModelReuse = currentAuthSession == config.session)) {
                        finishService(null)
                        return@withLock
                    }
                    engine?.setGpsEnabled(gpsPreferenceEnabled)
                    if (!gpsRequestedByActivity) {
                        engine?.invalidateLocation()
                        disableLocationUpdates()
                    }
                    startTracking(statusId, config.session)
                    if (thisCommand == commandVersion) {
                        pendingRequestedStatusId = null
                        pendingRequestedRevision = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                startRequests.finish(startRequest)
                trackingMutex.withLock {
                    // A failed old start must not tear down a replacement trip.
                    val config = prefs.trackingConfiguration.first()
                    if (acceptedCommand == commandVersion && currentAuthSession == acceptedSession && currentStatusId != null) finishService(null)
                    else if (currentStatusId == null && !startRequests.hasPending(config.session.revision, config.activeStatusId)) finishService(null)
                }
            } finally {
                startRequests.finish(startRequest)
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(useGps: Boolean, statusIdForNotification: Int? = currentStatusId,
                                    allowModelReuse: Boolean = true): Boolean {
        val notification = if (allowModelReuse && lastProgressModel != null && statusIdForNotification == currentStatusId) {
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

    private suspend fun startTracking(statusId: Int, session: AuthSession) {
        if (currentStatusId != statusId || currentAuthSession != session) {
            generation++
            currentStatusId = statusId
            currentAuthSession = session
            activeStatusBridge.bind(statusId, session.revision)
            mutableTrackingLiveState.value = null
            stopping = false
            trackingJob?.cancel()
            mutationRefreshJob?.cancel()
            mutationRefreshJob = null
            tickJob?.cancel()
            sevJob?.cancel()
            clearRoadRoutes()
            clearTransitRoutes()
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
            locationClock = TrackingLocationClock()
            locationError = null
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
            restartSpeechEngine(false)
            // Foreground promotion already succeeded and the active status was checked.
            ensureTrackingWakeLock()
            val restoringGeneration = generation
            restoreCachedTrip(statusId, restoringGeneration, session)
            if (!isCurrentTracking(statusId, restoringGeneration)) return
            updateNotification(lastNotificationTitle, lastNotificationText)
        }
        ensureTrackingWakeLock()
        if (speechConfiguration.enabled && tts == null && speechLifecycle.state != SpeechEngineState.INITIALIZING &&
            speechLifecycle.state != SpeechEngineState.RETRY_WAIT) restartSpeechEngine(true)
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
                        refreshTrip(statusId, expectedGeneration, session)
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
                        if ((!hasPreciseLocation() || !locationServicesEnabled()) && (callback != null || locationRetryJob?.isActive == true)) {
                            gpsRequestedByActivity = false
                            locationError = if (!hasPreciseLocation()) "Präziser Standort nicht freigegeben. Fahrplanmodus."
                                else "Ortung ausgeschaltet. Fahrplanmodus."
                            engine?.invalidateLocation()
                            disableLocationUpdates()
                            if (!promoteToForeground(false)) {
                                finishService(statusId)
                                return@withLock
                            }
                        }
                        revalidateSevStops(System.currentTimeMillis())
                        syncTransitRoutes(System.currentTimeMillis())
                        latestLocation?.let(::observeLocation)
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

    private suspend fun restoreCachedTrip(statusId: Int, expectedGeneration: Long, session: AuthSession) {
        val cache = runCatching {
            gson.fromJson(prefs.getTrackingState(), CachedTripTrackingState::class.java)
        }.getOrNull() ?: return
        if (!isCurrentTracking(statusId, expectedGeneration) || prefs.getAuthSession() != session) return
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
        // A process-local committed edit must be reconciled before cached progress can act.
        val mutation = repo.getStatusMutationSnapshot(statusId, session)
        if (activeStatusBridge.acceptsRestoredCache(mutation)) activeStatusBridge.adopt(mutation.revision)
    }

    private suspend fun refreshTrip(statusId: Int, expectedGeneration: Long, session: AuthSession) {
        if (!isCurrentTracking(statusId, expectedGeneration) || prefs.getAuthSession() != session) return
        val snapshot = repo.getStatusDetailSnapshot(statusId).getOrNull() ?: return
        val status = snapshot.status
        val checkin = status.checkin ?: return
        val tripId = checkin.trip ?: return
        if (!isCurrentTracking(statusId, expectedGeneration) || prefs.getAuthSession() != session) return
        val stops = repo.getStopovers(tripId).getOrNull() ?: return
        trackingMutex.withLock {
            if (!isCurrentTracking(statusId, expectedGeneration) ||
                prefs.getAuthSession() != session) return@withLock
            val currentMutation = repo.getStatusMutationSnapshot(statusId, session)
            if (!activeStatusBridge.acceptsRead(snapshot.revision, currentMutation)) return@withLock
            if (!adoptTripRoute(status, stops, snapshot.revision, statusId, expectedGeneration)) return@withLock
            if (!isCurrentTracking(statusId, expectedGeneration)) return@withLock
            val currentEngine = engine ?: return@withLock
            currentEngine.setGpsEnabled(gpsPreferenceEnabled)
            latestLocation?.let(::observeLocation)
            val update = latestLocation?.takeIf { isFreshLocation(it) }?.let {
                toFix(it)?.let { fix -> currentEngine.onLocation(fix, System.currentTimeMillis()) }
            } ?: currentEngine.onTimetable(System.currentTimeMillis())
            applyUpdate(update, statusId, expectedGeneration)
        }
    }

    private fun requestMutationRefresh(statusId: Int, expectedGeneration: Long) {
        val session = currentAuthSession ?: return
        if (mutationRefreshJob?.isActive == true) return
        mutationRefreshJob = serviceScope.launch {
            try { refreshTrip(statusId, expectedGeneration, session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The regular poll retries an unverified replacement. */ }
        }
    }

    /** Called while holding trackingMutex, including immediately before irreversible effects. */
    private suspend fun ensureCurrentTripSnapshot(statusId: Int, expectedGeneration: Long): Boolean {
        val session = currentAuthSession ?: return false
        if (!isCurrentTracking(statusId, expectedGeneration) || prefs.getAuthSession() != session) return false
        val committed = repo.getStatusMutationSnapshot(statusId, session)
        if (activeStatusBridge.canPublish(committed)) return true
        if (activeStatusBridge.wasDeleted(committed)) {
            stopTracking(statusId, session)
            return false
        }
        val replacement = activeStatusBridge.replacement(committed)
        if (replacement != null && replacement.checkin?.trip == cachedCheckin?.trip &&
            adoptTripRoute(replacement, cachedFullStops, committed.revision, statusId, expectedGeneration)) return false
        // An incomplete PUT, ledger eviction or unknown trip cannot authorize the old target.
        discardOldTripEffects()
        mutableTrackingLiveState.value = null
        requestMutationRefresh(statusId, expectedGeneration)
        startTracking(statusId, session)
        return false
    }

    private fun discardOldTripEffects() {
        completionJob?.cancel()
        completionJob = null
        completionStatusId = null
        completionUtteranceId = null
        cancelQueuedSpeech(releaseCurrentAnnouncement = true)
        pendingAnnouncement = null
    }

    private suspend fun adoptTripRoute(
        status: Status, fullStops: List<StopStation>, revision: Long,
        statusId: Int, expectedGeneration: Long
    ): Boolean {
        var replacement = prepareActiveTripRoute(status, fullStops, cachedCheckin, cachedStops, engine?.getProgress()) ?: return false
        if (replacement.boundariesChanged) {
            // Destination semantics belong to the route revision, even when the visit key survives.
            discardOldTripEffects()
            replacement = prepareActiveTripRoute(status, fullStops, cachedCheckin, cachedStops, engine?.getProgress()) ?: return false
            sevJob?.cancel()
            sevJob = null
            clearRoadRoutes()
            clearTransitRoutes()
            gpsJourneyTimes.reset()
        } else if (completionStatusId != null && activeStatusBridge.canPublish(
                StatusMutationSnapshot(revision, null))) return false
        val checkin = replacement.checkin
        val nextIndex = replacement.rebasedProgress?.nextIndex ?: 0
        val changes = tripChanges.observe(TripChangeSnapshot.fromApi(
            statusId, SystemClock.elapsedRealtime(), replacement.rawStops, checkin, nextIndex))
        if (cachedCheckin?.manualArrival != checkin.manualArrival || cachedCheckin?.manualDeparture != checkin.manualDeparture)
            gpsJourneyTimes.invalidateLocation()
        cachedCheckin = checkin
        cachedStops = replacement.stops
        cachedFullStops = replacement.fullStops
        revalidateSevStops(System.currentTimeMillis())
        val trackingStops = toTrackingStops(cachedStops, checkin)
        if (engine == null || replacement.boundariesChanged)
            engine = StationTrackingEngine(trackingStops, replacement.rebasedProgress ?: TrackingProgress(), radiusMeters)
        else engine?.updateRoute(trackingStops)
        if (activeStatusBridge.revision != revision) lastSavedJson = null
        activeStatusBridge.adopt(revision)
        syncTransitRoutes(System.currentTimeMillis())
        persistCurrentTripSnapshot(statusId)
        val relevantChanges = if (SevStopResolver.isReplacementBus(checkin)) changes.filterNot { it.kind == TripChangeKind.PLATFORM } else changes
        deliverTripChanges(relevantChanges, statusId, expectedGeneration)
        if (!isCurrentTracking(statusId, expectedGeneration)) return false
        scheduleSevEnrichment(statusId, expectedGeneration, checkin, fullStops)
        if (replacement.boundariesChanged) currentAuthSession?.let { startTracking(statusId, it) }
        return true
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
        pruneRoadRoutes(nowMillis)
    }

    private fun roadPairs(): List<RoadSegmentRequest> {
        val checkin = cachedCheckin ?: return emptyList()
        return RoadRouteSelection.allPairs(checkin, cachedStops,
            cachedStops.mapIndexed { index, stop -> stopKey(stop, index) }, cachedSevStops)
    }

    private fun roadWindow(progress: TrackingProgress): List<RoadSegmentRequest> =
        RoadRouteSelection.aroundCursor(roadPairs(), progress.nextStopKey)

    /** Route geometry is volatile and keyed by the actual public stop pair and visit identities. */
    private fun pruneRoadRoutes(nowMillis: Long) {
        val allowed = roadPairs().toSet()
        cachedRoadRoutes.entries.removeAll { (request, geometry) ->
            request !in allowed || !RoadRouteSelection.usable(request, geometry, nowMillis)
        }
        roadAttempts.keys.retainAll(allowed)
        if (roadRequestWindow.any { it !in allowed }) {
            roadRouteJob?.cancel()
            roadRouteJob = null
            roadRequestWindow = emptyList()
        }
    }

    private fun clearRoadRoutes() {
        roadRouteJob?.cancel()
        roadRouteJob = null
        roadRequestWindow = emptyList()
        cachedRoadRoutes.clear()
        roadAttempts.clear()
    }

    private fun segmentGeometries(progress: TrackingProgress, nowMillis: Long): List<GpsSegmentGeometry> =
        transitRoutes.segments(nowMillis) + roadWindow(progress).mapNotNull { request ->
            cachedRoadRoutes[request]?.takeIf { RoadRouteSelection.usable(request, it, nowMillis) }
                ?.let { GpsSegmentGeometry(request.fromKey, request.toKey, it) }
        }

    /** Route ownership excludes realtime/platforms, but includes every ordered visit and planned marker. */
    private fun syncTransitRoutes(nowMillis: Long) {
        val statusId = currentStatusId
        val session = currentAuthSession
        val checkin = cachedCheckin
        val request = if (statusId != null && session != null && checkin != null &&
            !stopping && completionStatusId == null && gpsRequestedByActivity && gpsPreferenceEnabled
        ) TransitRouteSelection.request(statusId, checkin, cachedFullStops, cachedStops,
            cachedStops.mapIndexed { index, stop -> stopKey(stop, index) }) else null
        val lease = if (request != null && session != null && statusId != null)
            TransitRouteTrackingCache.Lease(statusId, generation, session, request) else null
        if (transitRoutes.bind(lease)) {
            transitRouteJob?.cancel()
            transitRouteJob = null
            transitRouteRepository?.close()
            transitRouteRepository = null
            lastTransitAttemptMillis = null
        }
        engine?.updateSegmentGeometries(transitRoutes.segments(nowMillis))
    }

    private fun clearTransitRoutes() {
        transitRouteJob?.cancel()
        transitRouteJob = null
        transitRouteRepository?.close()
        transitRouteRepository = null
        transitRoutes.bind(null)
        lastTransitAttemptMillis = null
        engine?.updateSegmentGeometries(emptyList())
    }

    /** The session-owned HTTP request never holds trackingMutex and contains no device location. */
    private fun scheduleTransitRoute(statusId: Int, expectedGeneration: Long, nowMillis: Long) {
        if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null) return
        val lease = transitRoutes.lease ?: return
        if (transitRouteJob?.isActive == true || !transitRoutes.needsRefresh(nowMillis) ||
            lastTransitAttemptMillis?.let { nowMillis - it in 0 until TRANSIT_RETRY_INTERVAL_MILLIS } == true
        ) return
        val repository = transitRouteRepository ?: runCatching {
            TransitRouteRepository(lease.session)
        }.getOrNull()?.also { transitRouteRepository = it } ?: return
        transitRouteJob = serviceScope.launch {
            if (!isCurrentTracking(statusId, expectedGeneration) || transitRoutes.lease != lease ||
                prefs.getAuthSession() != lease.session || !isActive
            ) return@launch
            lastTransitAttemptMillis = System.currentTimeMillis()
            val geometry = try {
                repository.getRoute(lease.request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@launch
            trackingMutex.withLock {
                if (!isActive || !isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null ||
                    prefs.getAuthSession() != lease.session
                ) return@withLock
                val loadedAt = System.currentTimeMillis()
                // Rebuild from today's complete route before accepting an in-flight result.
                syncTransitRoutes(loadedAt)
                if (!isActive || !transitRoutes.adopt(lease, geometry, loadedAt)) return@withLock
                val currentEngine = engine ?: return@withLock
                currentEngine.updateSegmentGeometries(transitRoutes.segments(loadedAt))
                // A loaded path is not a new GPS fix, arrival or directed movement observation.
                applyUpdate(currentEngine.onTimetable(loadedAt), statusId, expectedGeneration)
            }
        }
    }

    /** Network waits run in a separate child; they never delay a GPS update under trackingMutex. */
    private fun scheduleRoadRoutes(statusId: Int, expectedGeneration: Long, progress: TrackingProgress, nowMillis: Long) {
        if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null || progress.completed) return
        if (!gpsRequestedByActivity || !gpsPreferenceEnabled) {
            roadRouteJob?.cancel()
            roadRouteJob = null
            roadRequestWindow = emptyList()
            return
        }
        pruneRoadRoutes(nowMillis)
        val window = roadWindow(progress)
        if (roadRequestWindow != window) {
            roadRouteJob?.cancel()
            roadRouteJob = null
            roadRequestWindow = window
        }
        if (window.isEmpty() || roadRouteJob?.isActive == true) return
        val missing = window.filter { request ->
            cachedRoadRoutes[request]?.let { RoadRouteSelection.usable(request, it, nowMillis) } != true &&
                roadAttempts[request]?.let { nowMillis - it in 0 until ROAD_RETRY_INTERVAL_MILLIS } != true
        }
        if (missing.isEmpty()) return
        roadRouteJob = serviceScope.launch {
            for (request in missing) {
                if (!isActive || !isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null ||
                    !gpsRequestedByActivity || !gpsPreferenceEnabled
                ) break
                // A cancelled preload must not mark its unstarted successor as a failed attempt.
                roadAttempts[request] = System.currentTimeMillis()
                val geometry = try {
                    RoadRouteRepository.getRoute(request.from, request.to)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                } ?: continue
                trackingMutex.withLock {
                    if (!isCurrentTracking(statusId, expectedGeneration) || completionStatusId != null) return@withLock
                    val loadedAt = System.currentTimeMillis()
                    revalidateSevStops(loadedAt)
                    if (!isActive) return@withLock
                    val currentEngine = engine ?: return@withLock
                    val currentProgress = currentEngine.getProgress()
                    // Coordinates, validity, visit identities or the cursor may have changed in flight.
                    if (request !in roadWindow(currentProgress) ||
                        !RoadRouteSelection.usable(request, geometry, loadedAt)
                    ) return@withLock
                    cachedRoadRoutes[request] = geometry
                    while (cachedRoadRoutes.size > MAX_CACHED_ROAD_SEGMENTS) {
                        cachedRoadRoutes.remove(cachedRoadRoutes.keys.first())
                    }
                    // A geometry response is not a fresh GPS fix or new station/dwell evidence.
                    applyUpdate(currentEngine.onTimetable(loadedAt), statusId, expectedGeneration)
                }
            }
        }
    }

    private fun stopKey(stop: StopStation, index: Int): String = activeTripStopKey(stop, index)

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

    private fun locationServicesEnabled(): Boolean = runCatching {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    private fun isFreshLocation(location: Location): Boolean {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        return ageMillis in 0..StationTrackingEngine.MAX_FIX_AGE_MILLIS && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy in 0f..StationTrackingEngine.MAX_ACCURACY_METERS.toFloat()
    }

    private fun observeLocation(location: Location): TrackingLocationObservation {
        val observation = locationClock.observe(location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
            location.hasSpeed(), location.speed.toDouble())
        if (observation.clockChanged) {
            latestLocation = null
            engine?.resetLocationClock()
            gpsJourneyTimes.reset()
        }
        return observation
    }

    private fun toFix(location: Location): LocationFix? = observeLocation(location).fix

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMillis: Long, retryOwner: Long? = null) {
        if (!gpsRequestedByActivity || !gpsPreferenceEnabled || !hasPreciseLocation() || !locationServicesEnabled() ||
            stopping || currentStatusId == null || completionStatusId != null) {
            disableLocationUpdates()
            return
        }
        if (callback != null && locationIntervalMillis == intervalMillis) return
        if (retryOwner != null && retryOwner != locationRegistrationOwner) return
        if (retryOwner == null && locationRetryJob?.isActive == true) return
        val registrationOwner = retryOwner ?: (++locationRegistrationOwner).also { locationRetries.begin(it) }
        disableLocationUpdates(clearLocation = false, cancelRetry = false)
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
                        if (!hasPreciseLocation() || !locationServicesEnabled()) {
                            gpsRequestedByActivity = false
                            locationError = if (!hasPreciseLocation()) "Präziser Standort nicht freigegeben. Fahrplanmodus."
                                else "Ortung ausgeschaltet. Fahrplanmodus."
                            engine?.invalidateLocation()
                            disableLocationUpdates()
                            if (!promoteToForeground(false)) finishService(callbackStatusId)
                            publishRuntimeIssues()
                            return@withLock
                        }
                        var nextInterval = locationIntervalMillis
                        for (location in locations) {
                            if (!isCurrentTracking(callbackStatusId, callbackGeneration) || completionStatusId != null) break
                            val observation = observeLocation(location)
                            if (!observation.isNew || observation.fix == null) continue
                            if (!isFreshLocation(location)) {
                                latestLocation = null
                                gpsJourneyTimes.invalidateLocation()
                                // Accuracy failures still break a transient recovery proof.
                                // Only the clock adapter may discard an old/replayed sample.
                                engine?.let { currentEngine ->
                                    dispatchTrackingLocationObservation(observation, currentEngine, System.currentTimeMillis())
                                        ?.let { applyUpdate(it, callbackStatusId, callbackGeneration) }
                                }
                                continue
                            }
                            locationError = null
                            latestLocation = location
                            revalidateSevStops(System.currentTimeMillis())
                            syncTransitRoutes(System.currentTimeMillis())
                            val currentEngine = engine ?: continue
                            val update = dispatchTrackingLocationObservation(observation, currentEngine, System.currentTimeMillis())
                                ?: continue
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
                .addOnSuccessListener {
                    serviceScope.launch { trackingMutex.withLock {
                        if (callback === nextCallback && locationRegistrationOwner == registrationOwner &&
                            isCurrentTracking(callbackStatusId, callbackGeneration)) {
                            locationRetries.success(registrationOwner)
                            locationError = null
                            publishRuntimeIssues()
                        }
                    } }
                }
                .addOnFailureListener { error ->
                    onLocationRegistrationFailed(nextCallback, registrationOwner, callbackStatusId, callbackGeneration, intervalMillis, error)
                }
        } catch (error: RuntimeException) {
            onLocationRegistrationFailed(nextCallback, registrationOwner, callbackStatusId, callbackGeneration, intervalMillis, error)
        }
    }

    private fun onLocationRegistrationFailed(listener: LocationCallback, owner: Long, statusId: Int,
                                             expectedGeneration: Long, intervalMillis: Long, error: Exception) {
        serviceScope.launch {
            trackingMutex.withLock {
                if (callback !== listener || owner != locationRegistrationOwner ||
                    !isCurrentTracking(statusId, expectedGeneration)) return@withLock
                disableLocationUpdates(cancelRetry = false)
                engine?.invalidateLocation()
                if (error is SecurityException || !hasPreciseLocation() || !locationServicesEnabled() ||
                    !gpsRequestedByActivity || !gpsPreferenceEnabled || completionStatusId != null) {
                    gpsRequestedByActivity = false
                    disableLocationUpdates()
                    locationError = "GPS-Zugriff nicht verfügbar. Standortfreigabe und Ortung prüfen."
                    if (!promoteToForeground(false)) finishService(statusId)
                } else {
                    val retryDelay = locationRetries.failure(owner)
                    locationError = if (retryDelay == null) "GPS-Updates nicht verfügbar. App öffnen, um erneut zu starten."
                        else "GPS-Updates starten nicht. Erneuter Versuch folgt."
                    if (retryDelay != null) locationRetryJob = serviceScope.launch {
                        delay(retryDelay)
                        trackingMutex.withLock {
                            locationRetryJob = null
                            if (owner == locationRegistrationOwner && isCurrentTracking(statusId, expectedGeneration)) {
                                requestLocationUpdates(intervalMillis, owner)
                            }
                        }
                    }
                }
                engine?.onTimetable(System.currentTimeMillis())?.let { applyUpdate(it, statusId, expectedGeneration) }
                publishRuntimeIssues()
            }
        }
    }

    private fun disableLocationUpdates(clearLocation: Boolean = true, cancelRetry: Boolean = true) {
        if (cancelRetry) {
            locationRetryJob?.cancel()
            locationRetryJob = null
            locationRetries.stop()
            locationRegistrationOwner++
        }
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
        val session = currentAuthSession ?: return
        if (prefs.getAuthSession() != session || !isCurrentTracking(statusId, expectedGeneration)) return
        if (!ensureCurrentTripSnapshot(statusId, expectedGeneration)) return
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
        pruneRoadRoutes(nowMillis)
        syncTransitRoutes(nowMillis)
        val gpsTimes = gpsJourneyTimes.update(
            route = toTrackingStops(cachedStops, checkin),
            progress = progress,
            source = update.source,
            fix = latestLocation?.takeIf {
                gpsRequestedByActivity && gpsPreferenceEnabled && isFreshLocation(it)
            }?.let(::toFix),
            nowMillis = nowMillis,
            segmentGeometries = segmentGeometries(progress, nowMillis),
            useRoadGeometry = SevStopResolver.isReplacementBus(checkin)
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
            sevStops = cachedSevStops,
            gpsTimeUnavailableReason = gpsJourneyTimes.unavailableReason(),
            sessionRevision = session.revision,
            gpsGeometrySource = gpsJourneyTimes.geometrySource() ?: engine?.geometrySource(),
            locationError = locationError,
            speechError = speechError
        )
        mutableTrackingLiveState.value = liveState
        lastProgressModel = TripProgressModel.from(cachedStops, liveState, destinationName,
            manualDestinationArrival = checkin.manualArrival, nowMillis = nowMillis)
        val platform = trackingPlatform(rawStop, nextStop?.isOrigin == true, SevStopResolver.isReplacementBus(checkin))
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
            if (!isCurrentTracking(statusId, expectedGeneration) || !ensureCurrentTripSnapshot(statusId, expectedGeneration)) return
            if (speechEnabled && isTtsInitialized) queuedUtterance = speakStop(announcedStop, update.source)
            if (!ensureCurrentTripSnapshot(statusId, expectedGeneration)) return
            if (queuedUtterance == null) {
                engine?.releaseAnnouncement(announcedStop.key)
                if (speechEnabled && !isTtsInitialized) pendingAnnouncement = announcedStop to update.source
            }
        }
        saveProgress(statusId)
        if (!isCurrentTracking(statusId, expectedGeneration) || !ensureCurrentTripSnapshot(statusId, expectedGeneration)) return
        if (update.destinationReached) {
            val existingDestinationUtterance = update.stop?.takeIf { it.isDestination }
                ?.let { speechDeliveries.find(statusId, expectedGeneration, it.key)?.utteranceId }
            val activeQueuedUtterance = queuedUtterance?.takeIf(speechDeliveries::contains)
            if (activeQueuedUtterance != null || existingDestinationUtterance != null || pendingAnnouncement != null) {
                awaitFinalSpeech(statusId, activeQueuedUtterance ?: existingDestinationUtterance)
            } else completeTracking(statusId)
        } else {
            scheduleRoadRoutes(statusId, expectedGeneration, progress, nowMillis)
            scheduleTransitRoute(statusId, expectedGeneration, nowMillis)
        }
    }

    private fun isCurrentTracking(statusId: Int, expectedGeneration: Long): Boolean =
        generation == expectedGeneration && currentStatusId == statusId && !stopping &&
            currentAuthSession != null && currentAuthSession == observedAuthSession

    private suspend fun saveProgress(statusId: Int) {
        val savingGeneration = generation
        if (!ensureCurrentTripSnapshot(statusId, savingGeneration)) return
        persistCurrentTripSnapshot(statusId)
    }

    private suspend fun persistCurrentTripSnapshot(statusId: Int) {
        val session = currentAuthSession ?: return
        val savingGeneration = generation
        val revision = activeStatusBridge.revision ?: return
        val checkin = cachedCheckin ?: return
        val currentEngine = engine ?: return
        val json = gson.toJson(CachedTripTrackingState(statusId = statusId, checkin = checkin, stopovers = cachedStops,
            progress = currentEngine.getProgress(), changes = tripChanges.getState(), liveUpdateDismissed = liveUpdateDismissed,
            fullStopovers = cachedFullStops, sevMaps = cachedSevMaps))
        if (json != lastSavedJson) {
            val saved = repo.withStatusRevision(statusId, session, revision) {
                if (isCurrentTracking(statusId, savingGeneration) && activeStatusBridge.revision == revision)
                    prefs.saveTrackingState(statusId, json, session)
            }
            if (saved && isCurrentTracking(statusId, savingGeneration) && currentAuthSession == session) lastSavedJson = json
        }
    }

    private fun configureSpeech(): Boolean {
        val instance = tts ?: return false
        return try {
            val language = speechConfiguration.language?.let(Locale::forLanguageTag) ?: Locale.GERMAN
            if (instance.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) {
                speechError = "Gewählte Sprache fehlt in der Sprachengine. Einstellungen prüfen."
                false
            } else {
                val requestedVoice = speechConfiguration.voice
                val voice = requestedVoice?.let { name -> instance.voices?.firstOrNull { it.name == name } }
                if (requestedVoice != null && (voice == null || instance.setVoice(voice) != TextToSpeech.SUCCESS)) {
                    speechError = "Gewählte Stimme ist in dieser Sprachengine nicht verfügbar. Einstellungen prüfen."
                    false
                } else {
                    speechError = null
                    true
                }
            }
        } catch (_: RuntimeException) {
            speechError = "Sprachengine antwortet nicht. Einstellungen prüfen."
            false
        }
    }

    private suspend fun speakStop(stop: TrackingStop, source: TrackingSource): String? {
        val statusId = currentStatusId ?: return null
        val session = currentAuthSession ?: return null
        val expectedGeneration = generation
        val revision = activeStatusBridge.revision ?: return null
        var utterance: String? = null
        val current = repo.withStatusRevision(statusId, session, revision) {
            if (!isCurrentTracking(statusId, expectedGeneration) || activeStatusBridge.revision != revision) return@withStatusRevision
            val checkin = cachedCheckin ?: return@withStatusRevision
            val currentStop = toTrackingStops(cachedStops, checkin).firstOrNull { it.key == stop.key }
            // The same visit can have changed from destination to an ordinary intermediate stop.
            if (currentStop != stop) return@withStatusRevision
            utterance = queueStopSpeech(stop, source)
        }
        if (!current) ensureCurrentTripSnapshot(statusId, expectedGeneration)
        return utterance
    }

    private suspend fun queueStopSpeech(stop: TrackingStop, source: TrackingSource): String? {
        val statusId = currentStatusId ?: return null
        val expectedGeneration = generation
        speechDeliveries.find(statusId, expectedGeneration, stop.key)?.let { return it.utteranceId }
        if (!configureSpeech()) { publishRuntimeIssues(); return null }
        val session = currentAuthSession ?: return null
        if (!prefs.getTtsEnabled() || prefs.getAuthSession() != session ||
            !isCurrentTracking(statusId, expectedGeneration)) return null
        val checkin = cachedCheckin ?: return null
        val platformStop = cachedStops.withIndex().firstOrNull { (index, raw) -> stopKey(raw, index) == stop.key }?.value
        val platform = trackingPlatform(platformStop, stop.isOrigin, SevStopResolver.isReplacementBus(checkin))
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
        val session = currentAuthSession ?: return
        if (prefs.getAuthSession() != session || !isCurrentTracking(statusId, expectedGeneration)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val title = if (changes.size == 1) changes.first().title else "${changes.size} Änderungen auf deiner Fahrt"
        val message = changes.joinToString("\n") { it.message }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "de.traewelling.app.OPEN_STATUS.$statusId"
            putExtra("open_status_id", statusId)
            putExtra(EXTRA_AUTH_SESSION_REVISION, session.revision)
            data = Uri.parse("routely://tracking/$statusId/${session.revision}/changes")
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
        if (!configureSpeech()) { publishRuntimeIssues(); return }
        if (prefs.getAuthSession() != session || !isCurrentTracking(statusId, expectedGeneration) || !requestAudioFocus()) return
        val eventKey = changes.joinToString("|") { it.key }
        val delivery = speechDeliveries.begin(statusId, expectedGeneration, eventKey, SpeechDeliveryKind.TRIP_CHANGE)
        if (tts?.speak(message, TextToSpeech.QUEUE_ADD, null, delivery.utteranceId) != TextToSpeech.SUCCESS) {
            speechDeliveries.finish(delivery.utteranceId)
            if (speechDeliveries.isEmpty) abandonAudioFocus()
        }
    }

    private fun handleSpeechFinished(utteranceId: String?, successful: Boolean, initializationToken: Long) {
        if (utteranceId == null) return
        serviceScope.launch {
            trackingMutex.withLock {
                if (!speechLifecycle.owns(initializationToken)) return@withLock
                val delivery = speechDeliveries.finish(utteranceId) ?: return@withLock
                // QUEUE_ADD entries share the focus until the last entry has finished.
                if (speechDeliveries.isEmpty) abandonAudioFocus()
                if (!isCurrentTracking(delivery.statusId, delivery.generation)) return@withLock
                speechError = if (successful) null else "Ansage unterbrochen oder fehlgeschlagen. Sprachausgabe prüfen."
                publishRuntimeIssues()
                if (!successful && delivery.kind == SpeechDeliveryKind.STOP && engine?.getProgress()?.nextStopKey == delivery.stopKey) {
                    engine?.releaseAnnouncement(delivery.stopKey)
                    saveProgress(delivery.statusId)
                }
                completeAfterSpeech(utteranceId)
            }
        }
    }

    private fun awaitFinalSpeech(statusId: Int, utteranceId: String?) {
        val completionGeneration = generation
        completionStatusId = statusId
        completionUtteranceId = utteranceId
        trackingJob?.cancel()
        tickJob?.cancel()
        sevJob?.cancel()
        clearRoadRoutes()
        clearTransitRoutes()
        disableLocationUpdates()
        completionJob?.cancel()
        completionJob = serviceScope.launch {
            delay(FINAL_SPEECH_TIMEOUT_MILLIS)
            trackingMutex.withLock {
                if (completionStatusId == statusId && isCurrentTracking(statusId, completionGeneration)) completeTracking(statusId)
            }
        }
    }

    private suspend fun completeAfterSpeech(utteranceId: String?) {
        val statusId = completionStatusId ?: return
        if (utteranceId != null && utteranceId == completionUtteranceId && currentStatusId == statusId) {
            completeTracking(statusId)
        }
    }

    private suspend fun completeTracking(statusId: Int) {
        val session = currentAuthSession ?: return
        val expectedGeneration = generation
        if (!ensureCurrentTripSnapshot(statusId, expectedGeneration)) {
            // A text/time edit may have been adopted by this callback. A changed target
            // was rebased with completed=false and cannot pass the final check below.
            if (!isCurrentTracking(statusId, expectedGeneration) || !activeStatusBridge.canPublish(
                    repo.getStatusMutationSnapshot(statusId, session))) return
        }
        val revision = activeStatusBridge.revision ?: return
        val completed = repo.withStatusRevision(statusId, session, revision) {
            if (isCurrentTracking(statusId, expectedGeneration) && engine?.getProgress()?.completed == true)
                stopTracking(statusId, session)
        }
        if (!completed) ensureCurrentTripSnapshot(statusId, expectedGeneration)
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
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(title, contentWithRuntimeIssues(content)))
        }
    }

    private fun contentWithRuntimeIssues(content: String): String =
        (listOf(content) + listOfNotNull(locationError, speechError)).joinToString(" • ")

    private fun publishRuntimeIssues() {
        val statusId = currentStatusId ?: return
        if (!isCurrentTracking(statusId, generation)) return
        mutableTrackingLiveState.value?.takeIf { it.statusId == statusId && it.sessionRevision == currentAuthSession?.revision }
            ?.let { mutableTrackingLiveState.value = it.copy(locationError = locationError, speechError = speechError) }
        updateNotification(lastNotificationTitle, lastNotificationText)
    }

    private fun createNotification(title: String, content: String, model: TripProgressModel? = lastProgressModel,
                                   statusIdForNotification: Int? = currentStatusId): Notification {
        val statusId = statusIdForNotification ?: -1
        val notificationRevision = pendingRequestedRevision.takeIf { pendingRequestedStatusId == statusId }
            ?: currentAuthSession?.revision
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "de.traewelling.app.OPEN_STATUS.$statusId"
            putExtra(EXTRA_AUTH_SESSION_REVISION, notificationRevision)
            data = Uri.parse("routely://tracking/$statusId/$notificationRevision/open")
            if (statusId > 0 && notificationRevision != null) putExtra("open_status_id", statusId)
        }
        val openPendingIntent = PendingIntent.getActivity(this, statusId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_STATUS_ID, statusId)
            val revision = notificationRevision
            putExtra(EXTRA_AUTH_SESSION_REVISION, revision)
            data = Uri.parse("routely://tracking/$statusId/$revision/stop")
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deleteIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_DISMISS_LIVE_UPDATE
            putExtra(EXTRA_STATUS_ID, statusId)
            val revision = notificationRevision
            putExtra(EXTRA_AUTH_SESSION_REVISION, revision)
            data = Uri.parse("routely://tracking/$statusId/$revision/dismiss")
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

    private suspend fun stopTracking(expectedStatusId: Int, expectedSession: AuthSession? = currentAuthSession) {
        if (currentStatusId != null && currentStatusId != expectedStatusId) return
        if (currentAuthSession != null && currentAuthSession != expectedSession) return
        val stoppingGeneration = generation
        stopping = true
        clearRoadRoutes()
        clearTransitRoutes()
        disableLocationUpdates()
        // Keep the current bounded CPU lease through the atomic ID+progress clear.
        // Renewal sees stopping=true, so a stalled commit cannot extend the lease.
        try {
            prefs.clearActiveTracking(expectedStatusId, expectedSession)
        } finally {
            // Storage failure must not leave an idle foreground service running.
            if (generation == stoppingGeneration) finishService(expectedStatusId)
        }
    }

    private fun finishService(expectedStatusId: Int?, preserveForegroundForPendingStart: Boolean = false) {
        if (expectedStatusId != null && currentStatusId != null && currentStatusId != expectedStatusId) return
        stopping = true
        releaseTrackingWakeLock()
        generation++
        trackingJob?.cancel()
        mutationRefreshJob?.cancel()
        mutationRefreshJob = null
        activeStatusBridge.clear()
        tickJob?.cancel()
        sevJob?.cancel()
        clearRoadRoutes()
        clearTransitRoutes()
        completionJob?.cancel()
        completionStatusId = null
        completionUtteranceId = null
        if (!preserveForegroundForPendingStart) {
            pendingRequestedStatusId = null
            pendingRequestedRevision = null
        }
        disableLocationUpdates()
        pendingAnnouncement = null
        restartSpeechEngine(false)
        locationError = null
        currentStatusId = null
        currentAuthSession = null
        lastProgressModel = null
        mutableTrackingLiveState.value = null
        publishWidgetWaiting()
        if (!preserveForegroundForPendingStart) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        startRequests.clear()
        clearRoadRoutes()
        clearTransitRoutes()
        releaseTrackingWakeLock()
        disableLocationUpdates()
        publishWidgetWaiting()
        abandonAudioFocus()
        serviceJob.cancel()
        restartSpeechEngine(false)
        mutableTrackingLiveState.value = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ may end a dataSync foreground lease (including when the
        // compatibility limit is enabled). Stop synchronously within its grace
        // period; a preference commit or final TTS must not delay system cleanup.
        // The active ID/cache remain available for an explicit visible restart.
        commandVersion++
        startRequests.clear()
        finishService(null)
        // Also cancel a start that had already entered the mutex and is waiting
        // for cached preferences; it must not republish data after the timeout.
        serviceJob.cancel()
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
        private const val ROAD_RETRY_INTERVAL_MILLIS = 60_000L
        private const val MAX_CACHED_ROAD_SEGMENTS = 8
        private const val TRANSIT_RETRY_INTERVAL_MILLIS = 60_000L
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
        private const val SPEECH_INIT_TIMEOUT_MILLIS = 10_000L
        const val EXTRA_STATUS_ID = "extra_status_id"
        const val EXTRA_ENABLE_GPS = "extra_enable_gps"
        const val EXTRA_AUTH_SESSION_REVISION = "extra_auth_session_revision"
        const val ACTION_STOP = "action_stop"
        const val ACTION_DISMISS_LIVE_UPDATE = "DISMISS_LIVE_UPDATE"
    }
}
