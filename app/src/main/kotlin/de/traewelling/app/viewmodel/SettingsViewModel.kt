package de.traewelling.app.viewmodel

import android.app.Application
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
    val availableTtsEngines: List<TextToSpeech.EngineInfo> = emptyList(),
    val availableLanguages: List<Locale> = emptyList(),
    val availableVoices: List<android.speech.tts.Voice> = emptyList(),
    val appTheme: String = "LIGHT",
    val gpsTrackingEnabled: Boolean = true,
    val announcementRadiusMeters: Int = 0,
    val rideRecognitionEnabled: Boolean = false,
    val tripChangeAlertsEnabled: Boolean = true,
    val tripChangeSpeechEnabled: Boolean = true,
    val liveProgressEnabled: Boolean = true,
    val lockScreenDetailsEnabled: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val prefs = PreferencesManager(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var tts: TextToSpeech? = null

    init {
        viewModelScope.launch {
            launch {
                prefs.isTtsEnabled.collect { enabled ->
                    _uiState.update { it.copy(isTtsEnabled = enabled) }
                    if (enabled && tts == null) {
                        initTts()
                    } else if (!enabled && tts != null) {
                        tts?.shutdown()
                        tts = null
                    }
                }
            }
            launch {
                prefs.ttsEngine.collect { eng -> _uiState.update { it.copy(selectedTtsEngine = eng) } }
            }
            launch {
                prefs.ttsLanguage.collect { lang -> _uiState.update { it.copy(selectedTtsLanguage = lang) } }
            }
            launch {
                prefs.ttsVoice.collect { voice -> _uiState.update { it.copy(selectedTtsVoice = voice) } }
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
        }
    }

    private fun initTts(engine: String? = null) {
        val normalizedEngine = if (engine.isNullOrEmpty()) null else engine
        val normalizedSelectedEngine = if (_uiState.value.selectedTtsEngine.isNullOrEmpty()) null else _uiState.value.selectedTtsEngine
        val currentEngine = normalizedEngine ?: normalizedSelectedEngine
        tts?.shutdown()
        tts = if (currentEngine != null) {
            TextToSpeech(getApplication(), this, currentEngine)
        } else {
            TextToSpeech(getApplication(), this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { ttsInstance ->
                val engines = ttsInstance.engines
                val languages = Locale.getAvailableLocales().filter {
                    try {
                        ttsInstance.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
                    } catch (e: Exception) { android.util.Log.w("SettingsViewModel", "isLanguageAvailable check failed", e); false }
                }.sortedBy { it.displayName }

                val voices = try {
                    ttsInstance.voices?.toList() ?: emptyList()
                } catch (e: Exception) { android.util.Log.w("SettingsViewModel", "Failed to fetch voices", e); emptyList() }

                _uiState.update { state ->
                    val filteredVoices = if (!state.selectedTtsLanguage.isNullOrEmpty()) {
                        voices.filter { it.locale.toLanguageTag() == state.selectedTtsLanguage }
                    } else {
                        voices
                    }
                    state.copy(
                        availableTtsEngines = engines,
                        availableLanguages = languages,
                        availableVoices = filteredVoices
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
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
            initTts(normalizedEngine) // Re-init with new engine to load its voices/langs
        }
    }

    fun selectTtsLanguage(language: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(_uiState.value.selectedTtsEngine, language, "")
            _uiState.update { state ->
                state.copy(selectedTtsLanguage = language, selectedTtsVoice = "")
            }
            // Update voices based on the new language
            tts?.let { ttsInstance ->
                val voices = try {
                    ttsInstance.voices?.toList() ?: emptyList()
                } catch (e: Exception) { android.util.Log.w("SettingsViewModel", "Failed to fetch voices", e); emptyList() }

                _uiState.update { state ->
                    val filteredVoices = if (language.isNotEmpty()) {
                        voices.filter { it.locale.toLanguageTag() == language }
                    } else {
                        voices
                    }
                    state.copy(availableVoices = filteredVoices)
                }
            }
        }
    }

    fun testTts() {
        if (_uiState.value.isTtsEnabled && tts != null) {
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
}
