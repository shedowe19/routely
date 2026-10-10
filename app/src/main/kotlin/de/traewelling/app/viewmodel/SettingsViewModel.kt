package de.traewelling.app.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale

data class SettingsUiState(
    val isTtsEnabled: Boolean = false,
    val selectedTtsEngine: String? = null,
    val selectedTtsLanguage: String? = null,
    val selectedTtsVoice: String? = null,
    val availableTtsEngines: List<SettingsTtsEngineOption> = emptyList(),
    val isTtsInitializing: Boolean = false,
    val ttsInitializationError: String? = null,
    val availableLanguages: List<Locale> = emptyList(),
    val availableVoices: List<android.speech.tts.Voice> = emptyList(),
    val appTheme: String = "LIGHT",
    val gpsTrackingEnabled: Boolean = true,
    val announcementRadiusMeters: Int = 0,
    val rideRecognitionEnabled: Boolean = false,
    val tripChangeAlertsEnabled: Boolean = true,
    val tripChangeSpeechEnabled: Boolean = true,
    val liveProgressEnabled: Boolean = true,
    val lockScreenDetailsEnabled: Boolean = true,
    val publicRailRealtimeEnabled: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var initializedEngine: String? = null
    private var allVoices: List<android.speech.tts.Voice> = emptyList()
    private val speechInitializer = SettingsSpeechInitializer(
        scope = viewModelScope,
        discoverEngines = ::discoverTtsEngines,
        createEngine = { engine, onResult ->
            val listener = TextToSpeech.OnInitListener { status -> onResult(status == TextToSpeech.SUCCESS) }
            if (engine != null) TextToSpeech(application, listener, engine) else TextToSpeech(application, listener)
        },
        shutdown = { instance: TextToSpeech -> instance.shutdown() },
        onReady = ::onTtsReady,
        onChanged = { state, failure, engines ->
            _uiState.update { it.copy(
                availableTtsEngines = engines,
                isTtsInitializing = state == SettingsSpeechState.INITIALIZING,
                ttsInitializationError = when (failure) {
                    SettingsSpeechFailure.TIMEOUT -> "Die Sprachengine antwortet nicht. Wähle eine andere Engine oder versuche es erneut."
                    SettingsSpeechFailure.INITIALIZATION, SettingsSpeechFailure.CONSTRUCTION ->
                        "Die Sprachengine konnte nicht gestartet werden. Wähle eine andere Engine oder versuche es erneut."
                    null -> null
                }
            ) }
        }
    )
    private val tts: TextToSpeech? get() = speechInitializer.instance

    init {
        viewModelScope.launch {
            launch {
                combine(prefs.isTtsEnabled, prefs.ttsEngine, prefs.ttsLanguage, prefs.ttsVoice) {
                    enabled, engine, language, voice -> TtsSettings(enabled, engine?.takeIf { it.isNotBlank() }, language, voice)
                }.distinctUntilChanged().collect { settings ->
                    _uiState.update { it.copy(
                        isTtsEnabled = settings.enabled, selectedTtsEngine = settings.engine,
                        selectedTtsLanguage = settings.language, selectedTtsVoice = settings.voice
                    ) }
                    if (settings.enabled) {
                        if (speechInitializer.state == SettingsSpeechState.DISABLED || initializedEngine != settings.engine)
                            initTts(settings.engine)
                        else updateAvailableVoices()
                    } else {
                        speechInitializer.disable()
                        allVoices = emptyList()
                        _uiState.update { it.copy(availableLanguages = emptyList(), availableVoices = emptyList()) }
                    }
                }
            }
            launch {
                prefs.appTheme.collect { theme -> _uiState.update { it.copy(appTheme = theme) } }
            }
            launch {
                prefs.gpsTrackingEnabled.collect { enabled ->
                    _uiState.update { it.copy(gpsTrackingEnabled = enabled) }
                }
            }
            launch {
                prefs.announcementRadiusMeters.collect { radius ->
                    _uiState.update { it.copy(announcementRadiusMeters = radius) }
                }
            }
            launch { prefs.rideRecognitionEnabled.collect { enabled -> _uiState.update { it.copy(rideRecognitionEnabled = enabled) } } }
            launch { prefs.tripChangeAlertsEnabled.collect { enabled -> _uiState.update { it.copy(tripChangeAlertsEnabled = enabled) } } }
            launch { prefs.tripChangeSpeechEnabled.collect { enabled -> _uiState.update { it.copy(tripChangeSpeechEnabled = enabled) } } }
            launch { prefs.liveProgressEnabled.collect { enabled -> _uiState.update { it.copy(liveProgressEnabled = enabled) } } }
            launch { prefs.lockScreenDetailsEnabled.collect { enabled -> _uiState.update { it.copy(lockScreenDetailsEnabled = enabled) } } }
            launch { prefs.publicRailRealtimeEnabled.collect { enabled -> _uiState.update { it.copy(publicRailRealtimeEnabled = enabled) } } }
        }
    }

    private fun initTts(engine: String?) {
        initializedEngine = engine
        allVoices = emptyList()
        _uiState.update { it.copy(availableLanguages = emptyList(), availableVoices = emptyList()) }
        speechInitializer.restart(engine)
    }

    @Suppress("DEPRECATION")
    private fun discoverTtsEngines(): List<SettingsTtsEngineOption> {
        val manager = getApplication<Application>().packageManager
        return manager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_ALL)
            .mapNotNull { resolved ->
                val service = resolved.serviceInfo?.takeIf { it.enabled && it.applicationInfo.enabled } ?: return@mapNotNull null
                SettingsTtsEngineOption(service.packageName, resolved.loadLabel(manager).toString().ifBlank { service.packageName })
            }
            .distinctBy { it.name }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun onTtsReady(instance: TextToSpeech) {
        val languages = try {
            instance.availableLanguages.orEmpty().sortedBy { it.displayName }
        } catch (e: Exception) {
            android.util.Log.w("SettingsViewModel", "Failed to fetch languages", e)
            emptyList()
        }
        allVoices = try {
            instance.voices?.toList().orEmpty()
        } catch (e: Exception) {
            android.util.Log.w("SettingsViewModel", "Failed to fetch voices", e)
            emptyList()
        }
        _uiState.update { it.copy(availableLanguages = languages) }
        updateAvailableVoices()
    }

    fun retryTtsInitialization() {
        if (_uiState.value.isTtsEnabled) initTts(_uiState.value.selectedTtsEngine)
    }

    private fun updateAvailableVoices() {
        _uiState.update { state -> state.copy(availableVoices = allVoices.filter {
            state.selectedTtsLanguage.isNullOrEmpty() || it.locale.toLanguageTag() == state.selectedTtsLanguage
        }) }
    }

    override fun onCleared() {
        speechInitializer.disable()
        super.onCleared()
    }

    fun toggleTts(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setTtsEnabled(enabled)
        }
    }

    fun selectTtsEngine(engine: String) {
        val normalizedEngine = if (engine.isEmpty()) null else engine
        viewModelScope.launch {
            prefs.saveTtsSettings(normalizedEngine, _uiState.value.selectedTtsLanguage, _uiState.value.selectedTtsVoice)
        }
    }

    fun selectTtsLanguage(language: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(_uiState.value.selectedTtsEngine, language, "")
        }
    }

    fun testTts() {
        if (_uiState.value.isTtsEnabled && speechInitializer.state == SettingsSpeechState.READY && tts != null) {
            val language = _uiState.value.selectedTtsLanguage
            val locale = if (!language.isNullOrEmpty()) Locale.forLanguageTag(language) else Locale.GERMAN
            val result = tts?.setLanguage(locale)

            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                val voiceName = _uiState.value.selectedTtsVoice
                if (!voiceName.isNullOrEmpty()) {
                    try {
                        val availableVoices = tts?.voices
                        val selectedVoice = availableVoices?.find { it.name == voiceName }
                        if (selectedVoice != null) {
                            tts?.voice = selectedVoice
                        }
                    } catch (e: Exception) { android.util.Log.w("SettingsViewModel", "Failed to set voice", e) }
                }
                tts?.speak("Dies ist ein Test der Sprachausgabe.", TextToSpeech.QUEUE_FLUSH, null, "TTS_TEST")
            }
        }
    }

    fun selectTtsVoice(voiceName: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(_uiState.value.selectedTtsEngine, _uiState.value.selectedTtsLanguage, voiceName)
        }
    }

    fun setAppTheme(theme: String) {
        viewModelScope.launch {
            prefs.setAppTheme(theme)
        }
    }

    fun setGpsTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setGpsTrackingEnabled(enabled) }
    }

    fun setAnnouncementRadiusMeters(radius: Int) {
        viewModelScope.launch { prefs.setAnnouncementRadiusMeters(radius) }
    }

    fun setTripChangeAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setTripChangeAlertsEnabled(enabled) }
    }
    fun setTripChangeSpeechEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setTripChangeSpeechEnabled(enabled) }
    }
    fun setLiveProgressEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setLiveProgressEnabled(enabled) }
    }
    fun setLockScreenDetailsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setLockScreenDetailsEnabled(enabled) }
    }

    fun setPublicRailRealtimeEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setPublicRailRealtimeEnabled(enabled) }
    }
}

private data class TtsSettings(val enabled: Boolean, val engine: String?, val language: String?, val voice: String?)
