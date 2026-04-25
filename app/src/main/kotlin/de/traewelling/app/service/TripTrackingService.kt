package de.traewelling.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.traewelling.app.MainActivity
import de.traewelling.app.R
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.UtteranceProgressListener

class TripTrackingService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "TripTrackingChannel"
    private val NOTIFICATION_ID = 1001

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var prefs: PreferencesManager
    private lateinit var repo: TraewellingRepository
    private var trackingJob: Job? = null
    private var currentStatusId: Int? = null

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var lastAnnouncedStopId: Int? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        repo = TraewellingRepository(prefs)
        createNotificationChannel()

        serviceScope.launch {
            val engine = prefs.getTtsEngine()
            tts = if (engine != null) TextToSpeech(this@TripTrackingService, this@TripTrackingService, engine)
                  else TextToSpeech(this@TripTrackingService, this@TripTrackingService)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            serviceScope.launch {
                val langTag = prefs.getTtsLanguage()
                val locale = if (langTag != null) Locale.forLanguageTag(langTag) else Locale.GERMAN
                val result = tts?.setLanguage(locale)

                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    val voiceName = prefs.getTtsVoice()
                    if (voiceName != null) {
                        try {
                            val availableVoices = tts?.voices
                            val selectedVoice = availableVoices?.find { it.name == voiceName }
                            if (selectedVoice != null) {
                                tts?.voice = selectedVoice
                            }
                        } catch (e: Exception) {}
                    }
                    isTtsInitialized = true

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            abandonAudioFocus()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            abandonAudioFocus()
                        }
                    })
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusId = intent?.getIntExtra(EXTRA_STATUS_ID, -1) ?: -1

        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        if (statusId != -1) {
            currentStatusId = statusId
            startForegroundServiceWithNotification()
            startTracking(statusId)
        } else {
            // Check if there's a stored status ID we should resume
            serviceScope.launch {
                prefs.activeStatusId.collect { storedId ->
                    if (storedId != null) {
                        currentStatusId = storedId
                        startForegroundServiceWithNotification()
                        startTracking(storedId)
                    } else {
                        stopSelf()
                    }
                    // Only take the first value
                    throw kotlinx.coroutines.CancellationException("Startup check complete")
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification("Lade Reisedaten...", "Bitte warten")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTracking(statusId: Int) {
        if (currentStatusId != statusId) {
            lastAnnouncedStopId = null
            currentStatusId = statusId
        }
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                pollTripData(statusId)
                delay(60_000) // Poll every 60 seconds
            }
        }
    }

    private suspend fun pollTripData(statusId: Int) {
        val statusResult = repo.getStatusDetail(statusId)
        if (statusResult.isFailure) return

        val status = statusResult.getOrNull() ?: return
        val checkin = status.checkin ?: return
val tripId = checkin.trip ?: return

        val stopoversResult = repo.getStopovers(tripId)
        if (stopoversResult.isFailure) return

        val stopovers = stopoversResult.getOrNull() ?: return

val origin = checkin.origin
        val destination = checkin.destination

        val originIndex = stopovers.indexOfFirst { it.id == origin?.id }
        val destIndex = stopovers.indexOfFirst { it.id == destination?.id }

        val validStopovers = if (originIndex != -1) {
            val endIdx = if (destIndex != -1) destIndex + 1 else stopovers.size
            stopovers.subList(originIndex, endIdx)
        } else {
            stopovers
        }

        // Enrich stopovers with manual times
        val enrichedStops = validStopovers.map { stop ->
            when (stop.id) {
                origin?.id -> if (origin != null) origin.copy(departureReal = checkin.manualDeparture ?: origin.departureReal) else stop
                destination?.id -> if (destination != null) destination.copy(arrivalReal = checkin.manualArrival ?: destination.arrivalReal) else stop
                else -> stop
            }
        }

        val now = ZonedDateTime.now()

        // Find next stop
        val nextStop = enrichedStops.firstOrNull { stop ->
            val arrTimeStr = stop.arrivalReal ?: stop.arrivalPlanned ?: stop.arrival
            val depTimeStr = stop.departureReal ?: stop.departurePlanned ?: stop.departure
            val timeStr = arrTimeStr ?: depTimeStr ?: ""

            if (timeStr.isNotBlank()) {
                try {
                    val zdt = ZonedDateTime.parse(timeStr)
                    zdt.isAfter(now)
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }

        // Check if destination is reached
        val destTimeStr = destination?.arrivalReal ?: destination?.arrivalPlanned ?: destination?.arrival ?: ""
        var isDestinationReached = false
        if (destTimeStr.isNotBlank()) {
            try {
                val destZdt = ZonedDateTime.parse(destTimeStr)
                if (now.isAfter(destZdt) || nextStop == null) {
                    isDestinationReached = true
                }
            } catch (e: Exception) {}
        }

        if (isDestinationReached) {
            stopTracking()
            return
        }

        val lineName = checkin.lineName ?: "Zug"
        val nextStopName = nextStop?.name ?: destination?.name ?: "Unbekannt"
        val platform = nextStop?.arrivalPlatformReal ?: nextStop?.arrivalPlatformPlanned ?: nextStop?.platform
        val platformText = if (!platform.isNullOrBlank()) " • Gl. $platform" else ""

        val destName = destination?.name ?: ""

        val timeStr = nextStop?.arrivalReal ?: nextStop?.arrivalPlanned ?: nextStop?.arrival ?: ""
        val localTime = if (timeStr.isNotBlank()) {
            try {
                 ZonedDateTime.parse(timeStr).withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e: Exception) { "" }
        } else { "" }

        val title = "$lineName nach $destName"
        val content = "Nächster Halt: $nextStopName $localTime$platformText"

        updateNotification(title, content)

        // Handle TTS Announcement
        if (isTtsInitialized && prefs.getTtsEnabled() && nextStop != null) {
            val stopId = nextStop.id
            if (stopId != null && stopId != lastAnnouncedStopId) {
                if (timeStr.isNotBlank()) {
                    try {
                        val arrTime = ZonedDateTime.parse(timeStr)
                        val durationToArrival = java.time.Duration.between(now, arrTime)
                        // Announce if arrival is within the next 3 minutes
                        if (durationToArrival.toMinutes() in 0..3) {
                            // Ensure language and voice are up to date before speaking
                            val langTag = prefs.getTtsLanguage()
                            val locale = if (langTag != null) Locale.forLanguageTag(langTag) else Locale.GERMAN
                            tts?.setLanguage(locale)

                            val voiceName = prefs.getTtsVoice()
                            if (voiceName != null) {
                                val availableVoices = tts?.voices
                                val selectedVoice = availableVoices?.find { it.name == voiceName }
                                if (selectedVoice != null) {
                                    tts?.voice = selectedVoice
                                }
                            }

                            val platformAnnouncement = if (!platform.isNullOrBlank()) " auf Gleis $platform" else ""
                            val isDestination = nextStop.id == destination?.id
                            val isOrigin = lastAnnouncedStopId == null

                            val mode = checkin.lineName ?: "Zug"
                            val rawOperator = checkin.operator?.name ?: ""
                            val operatorName = if (rawOperator.startsWith("Betreiber:")) {
                                rawOperator.substringAfter("Betreiber:").trim()
                            } else {
                                rawOperator
                            }

                            val announcement = if (isOrigin) {
                                "Der $mode erreicht in kürze deine Anfangshaltestelle $nextStopName bitte einsteigen"
                            } else if (isDestination) {
                                "Du erreichst nun in kürze deine Ausstiegshaltestelle $nextStopName ich danke dir mit der Fahrt mit $operatorName"
                            } else {
                                "Nächste Haltestelle in Kürze, $nextStopName$platformAnnouncement."
                            }

                            requestAudioFocus()
                            tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "TTS_ANNOUNCEMENT")
                            lastAnnouncedStopId = stopId
                        }
                    } catch (e: Exception) {
                        // ignore parse errors
                    }
                }
            }
        }

        // Send broadcast to update widget
        val widgetIntent = Intent(this@TripTrackingService, de.traewelling.app.widget.TripWidgetProvider::class.java).apply {
            action = "de.traewelling.app.ACTION_UPDATE_WIDGET"
            putExtra("lineName", lineName)
            putExtra("nextStop", nextStopName)
            putExtra("destination", destName)
            putExtra("time", localTime)
            putExtra("platform", platform)
            putExtra("delay", calculateDelay(nextStop))
        }
        sendBroadcast(widgetIntent)
    }

    private fun calculateDelay(stop: de.traewelling.app.data.model.StopStation?): Int? {
        if (stop == null) return null
        val plannedStr = stop.arrivalPlanned ?: stop.arrival ?: return null
        val realStr = stop.arrivalReal ?: return null

        try {
            val planned = ZonedDateTime.parse(plannedStr)
            val real = ZonedDateTime.parse(realStr)
            val delayMinutes = java.time.Duration.between(planned, real).toMinutes().toInt()
            return if (delayMinutes > 0) delayMinutes else null
        } catch (e: Exception) {
            return null
        }
    }

private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .build()
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.traewelling_logo)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live-Reiseinformationen",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den aktuellen Status der aktiven Fahrt an"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        serviceScope.launch {
            prefs.saveActiveStatusId(null)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_STATUS_ID = "extra_status_id"
        const val ACTION_STOP = "action_stop"
    }
}
