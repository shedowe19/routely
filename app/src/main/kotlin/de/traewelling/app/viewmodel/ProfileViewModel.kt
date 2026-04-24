package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.speech.tts.TextToSpeech
import java.util.Locale

data class ProfileUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val statistics: StatisticsData? = null,
    val recentStatuses: List<Status> = emptyList(),
    val isTtsEnabled: Boolean = false,
    val selectedTtsEngine: String? = null,
    val selectedTtsLanguage: String? = null,
    val selectedTtsVoice: String? = null,
    val availableTtsEngines: List<TextToSpeech.EngineInfo> = emptyList(),
    val availableLanguages: List<Locale> = emptyList(),
    val availableVoices: List<android.speech.tts.Voice> = emptyList(),
    val error: String? = null
)

class ProfileViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

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
        }
    }

    private fun initTts(engine: String? = null) {
        val currentEngine = engine ?: _uiState.value.selectedTtsEngine
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
                    } catch (e: Exception) { false }
                }.sortedBy { it.displayName }

                val voices = try {
                    ttsInstance.voices?.toList() ?: emptyList()
                } catch (e: Exception) { emptyList() }

                _uiState.update {
                    it.copy(
                        availableTtsEngines = engines,
                        availableLanguages = languages,
                        availableVoices = voices
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
    }

    fun loadProfile() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val userResult  = repo.getCurrentUser()
            val statsResult = repo.getStatistics()

            userResult.onSuccess { user ->
                _uiState.update { it.copy(user = user) }
                repo.getUserStatuses(user.username, 1).onSuccess { response ->
                    _uiState.update { it.copy(recentStatuses = response.data ?: emptyList()) }
                }
            }

            statsResult.onSuccess { statsData ->
                _uiState.update { it.copy(statistics = statsData) }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (userResult.isFailure)
                        "Profil konnte nicht geladen werden: ${userResult.exceptionOrNull()?.message}"
                    else null
                )
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(user = null, statistics = null, recentStatuses = emptyList()) }
        loadProfile()
    }

    fun toggleTts(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setTtsEnabled(enabled)
        }
    }

    fun selectTtsEngine(engine: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(engine, _uiState.value.selectedTtsLanguage, _uiState.value.selectedTtsVoice)
            initTts(engine) // Re-init with new engine to load its voices/langs
        }
    }

    fun selectTtsLanguage(language: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(_uiState.value.selectedTtsEngine, language, _uiState.value.selectedTtsVoice)
        }
    }

    fun selectTtsVoice(voiceName: String) {
        viewModelScope.launch {
            prefs.saveTtsSettings(_uiState.value.selectedTtsEngine, _uiState.value.selectedTtsLanguage, voiceName)
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
