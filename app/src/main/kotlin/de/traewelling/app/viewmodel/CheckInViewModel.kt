package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.*
import android.content.Intent
import androidx.core.content.ContextCompat
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.service.TripTrackingService
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class CheckInStep { STATION, DEPARTURES, DESTINATION, CONFIRM, SUCCESS }

data class CheckInUiState(
    val step: CheckInStep = CheckInStep.STATION,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Station search
    val stationQuery: String = "",
    val searchResults: List<TrainStation> = emptyList(),
    // Selected station & departures
    val selectedStation: TrainStation? = null,
    val departures: List<DepartureTrip> = emptyList(),
    // Selected departure & loaded trip details (flat stopovers)
    val selectedDeparture: DepartureTrip? = null,
    val selectedTripDetails: TripDetails? = null,
    val filteredDestinations: List<StopStation> = emptyList(),
    // Selected destination (flat StopStation — id and name at top level)
    val selectedDestination: StopStation? = null,
    // Optional status message
    val statusBody: String = "",
    // Manual times
    val manualDeparture: String = "",
    val manualArrival: String = "",
    // Result
    val checkInResult: CheckInResult? = null
)

class CheckInViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    // ─── Step 1: Station search ───────────────────────────────────────────────

    fun updateStationQuery(query: String) {
        _uiState.update { it.copy(stationQuery = query, searchResults = emptyList(), error = null) }
        if (query.length < 2) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            _uiState.update { it.copy(isLoading = true) }
            repo.searchStations(query)
                .onSuccess { stations ->
                    _uiState.update { it.copy(isLoading = false, searchResults = stations) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Suche fehlgeschlagen: ${e.message}") }
                }
        }
    }

    // ─── Step 2: Load departures using station.id ─────────────────────────────

    fun selectStation(station: TrainStation) {
        val stationId = station.id
        if (stationId == null) {
            _uiState.update { it.copy(error = "Bahnhof hat keine gültige ID.") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedStation = station,
                    stationQuery    = station.name ?: "",
                    searchResults   = emptyList(),
                    isLoading       = true,
                    error           = null
                )
            }
            repo.getStationDepartures(stationId)
                .onSuccess { trips ->
                    _uiState.update {
                        it.copy(isLoading = false, departures = trips, step = CheckInStep.DEPARTURES)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Abfahrten konnten nicht geladen werden: ${e.message}")
                    }
                }
        }
    }

    // ─── Step 3: User picks a departure → load full trip (stopovers) ──────────

    fun selectTrip(departure: DepartureTrip) {
        val lineName = departure.line?.name ?: ""
        viewModelScope.launch {
            _uiState.update {
                it.copy(selectedDeparture = departure, isLoading = true, error = null)
            }
            repo.getTrip(hafasTripId = departure.tripId, lineName = lineName)
                .onSuccess { tripDetails ->
                    val origin = _uiState.value.selectedStation
                    val stopovers = tripDetails.stopovers ?: emptyList()
                    
                    // Find the origin station in the trip stopovers to filter the destination list
                    // Use a robust word-based matching to handle name variations (e.g., "Spornitz Schule" vs "Schule, Spornitz")
                    val originWords = origin?.name?.lowercase()?.split(Regex("\\W+"))?.filter { it.length > 2 } ?: emptyList()
                    val originIdx = stopovers.indexOfFirst { stop ->
                        stop.id == origin?.id || (stop.name != null && originWords.isNotEmpty() && 
                        originWords.all { stop.name.lowercase().contains(it) })
                    }
                    
                    // Only show stations AFTER the origin as possible destinations
                    val filteredStopovers = if (originIdx != -1) {
                        stopovers.drop(originIdx + 1)
                    } else {
                        stopovers
                    }

                    _uiState.update {
                        it.copy(
                            isLoading            = false,
                            selectedTripDetails  = tripDetails,
                            filteredDestinations = filteredStopovers,
                            step                 = CheckInStep.DESTINATION
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Halte konnten nicht geladen werden: ${e.message}")
                    }
                }
        }
    }

    // ─── Step 4: User picks destination stopover (flat StopStation) ───────────

    fun selectDestination(stopStation: StopStation) {
        val state = _uiState.value
        val origin = state.selectedStation
        val originStop = state.selectedTripDetails?.stopovers?.find { stop ->
            val words = origin?.name?.lowercase()?.split(Regex("\\W+"))?.filter { it.length > 2 } ?: emptyList()
            stop.id == origin?.id || (stop.name != null && words.isNotEmpty() && words.all { stop.name.lowercase().contains(it) })
        }
        
        val depTime = originStop?.departurePlanned ?: originStop?.departure ?: state.selectedDeparture?.plannedWhen ?: ""
        val arrTime = stopStation.arrivalPlanned ?: stopStation.arrival ?: ""

        _uiState.update { 
            it.copy(
                selectedDestination = stopStation, 
                manualDeparture = depTime,
                manualArrival = arrTime,
                step = CheckInStep.CONFIRM 
            ) 
        }
    }

    fun updateManualDeparture(time: String) = _uiState.update { it.copy(manualDeparture = time) }
    fun updateManualArrival(time: String) = _uiState.update { it.copy(manualArrival = time) }

    fun updateStatusBody(body: String) = _uiState.update { it.copy(statusBody = body) }

    // ─── Step 5: Confirm check-in ─────────────────────────────────────────────

    fun confirmCheckIn() {
        val state       = _uiState.value
        val departure   = state.selectedDeparture   ?: return
        val origin      = state.selectedStation     ?: return
        val destination = state.selectedDestination ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Match origin from the full trip details (we need the original ID and timestamp)
            val originWords = origin.name?.lowercase()?.split(Regex("\\W+"))?.filter { it.length > 2 } ?: emptyList()
            val originStop = state.selectedTripDetails?.stopovers?.find { stop ->
                stop.id == origin.id || (stop.name != null && originWords.isNotEmpty() && 
                originWords.all { stop.name.lowercase().contains(it) })
            }
            
            val request = CheckInRequest(
                tripId               = departure.tripId,
                lineName             = departure.line?.name ?: "",
                startStationId       = originStop?.id ?: origin.id ?: 0,
                destinationStationId = destination.id ?: 0,
                departure            = state.manualDeparture.ifBlank { originStop?.departurePlanned ?: originStop?.departure ?: departure.plannedWhen ?: "" },
                arrival              = state.manualArrival.ifBlank { destination.arrivalPlanned ?: destination.arrival ?: "" },
                body                 = state.statusBody.ifBlank { null }
            )

            repo.checkIn(request)
                .onSuccess { result ->
                    _uiState.update { it.copy(isLoading = false, checkInResult = result, step = CheckInStep.SUCCESS) }

                    // Start TripTrackingService
                    result?.status?.id?.let { statusId ->
                        launch {
                            prefs.saveActiveStatusId(statusId)
                            val serviceIntent = Intent(getApplication(), TripTrackingService::class.java).apply {
                                putExtra(TripTrackingService.EXTRA_STATUS_ID, statusId)
                            }
                            ContextCompat.startForegroundService(getApplication(), serviceIntent)
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Check-in fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun reset() { _uiState.value = CheckInUiState() }

    fun goBack() {
        searchJob?.cancel()
        _uiState.update { state ->
            when (state.step) {
                CheckInStep.DEPARTURES  -> state.copy(
                    step = CheckInStep.STATION, selectedStation = null, departures = emptyList()
                )
                CheckInStep.DESTINATION -> state.copy(
                    step = CheckInStep.DEPARTURES, selectedDeparture = null, selectedTripDetails = null
                )
                CheckInStep.CONFIRM     -> state.copy(
                    step = CheckInStep.DESTINATION, selectedDestination = null
                )
                else -> state
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
