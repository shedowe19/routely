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
import java.time.OffsetDateTime

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
    // Selected departure & loaded trip details
    val selectedDeparture: DepartureTrip? = null,
    val selectedTripDetails: TripDetails? = null,
    val filteredDestinations: List<StopStation> = emptyList(),
    // Selected destination stopover; station identity lives in station
    val selectedDestination: StopStation? = null,
    // Optional status message
    val statusBody: String = "",
    // Travel reason sent as API field "business" (0=private, 1=business, 2=commute)
    val travelReason: TravelReason = TravelReason.PRIVATE,
    // Manual times
    val manualDeparture: String = "",
    val manualArrival: String = "",
    // Result
    val checkInResult: CheckInResult? = null,
    val resolvedOriginStop: StopStation? = null
)

class CheckInViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    // ─── Step 1: Station search ───────────────────────────────────────────────

    fun searchNearbyStations(lat: Double, lon: Double) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, stationQuery = "Stationen in der Nähe...") }
            repo.getNearbyStations(lat, lon)
                .onSuccess { stations ->
                    val distinctStations = stations.distinctBy { st -> st.id }
                    if (distinctStations.size == 1) {
                        selectStation(distinctStations.first())
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                searchResults = distinctStations,
                                stationQuery = "Nahegelegene Stationen"
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Standortsuche fehlgeschlagen: ${e.message}",
                            stationQuery = ""
                        )
                    }
                }
        }
    }

    fun updateStationQuery(query: String) {
        _uiState.update { it.copy(stationQuery = query, searchResults = emptyList(), error = null) }
        if (query.length < 2) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            _uiState.update { it.copy(isLoading = true) }
            repo.searchStations(query)
                .onSuccess { stations ->
                    _uiState.update { it.copy(isLoading = false, searchResults = stations.distinctBy { it.id }) }
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
                    // Radius-based departure queries can return a nearby station's service.
                    // The departure's station is the authoritative boarding station.
                    val origin = departure.station ?: _uiState.value.selectedStation
                    val stopovers = tripDetails.stopovers ?: emptyList()
                    
                    val finalOriginIdx = resolveOriginIndex(stopovers, origin, departure)

                    // Only show stations AFTER the origin as possible destinations
                    val filteredStopovers = if (finalOriginIdx != -1) {
                        stopovers.drop(finalOriginIdx + 1)
                    } else {
                        stopovers
                    }

                    _uiState.update {
                        it.copy(
                            isLoading            = false,
                            selectedTripDetails  = tripDetails,
                            selectedStation      = origin,
                            filteredDestinations = filteredStopovers,
                            resolvedOriginStop   = if (finalOriginIdx != -1) stopovers[finalOriginIdx] else null,
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

    // ─── Step 4: User picks destination stopover ───────────

    fun selectDestination(stopStation: StopStation) {
        if (stopStation.stationId == null) {
            _uiState.update { it.copy(error = "Zielbahnhof hat keine gültige ID.") }
            return
        }
        _uiState.update { 
            it.copy(
                selectedDestination = stopStation, 
                manualDeparture = "",
                manualArrival = "",
                step = CheckInStep.CONFIRM 
            ) 
        }
    }

    fun updateManualDeparture(time: String) = _uiState.update { it.copy(manualDeparture = time) }
    fun updateManualArrival(time: String) = _uiState.update { it.copy(manualArrival = time) }

    fun updateStatusBody(body: String) = _uiState.update { it.copy(statusBody = body) }

    fun updateTravelReason(reason: TravelReason) = _uiState.update { it.copy(travelReason = reason) }

    // ─── Step 5: Confirm check-in ─────────────────────────────────────────────

    fun confirmCheckIn() {
        val state       = _uiState.value
        val departure   = state.selectedDeparture   ?: return
        val origin      = departure.station ?: state.selectedStation ?: return
        val destination = state.selectedDestination ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Stopover IDs are distinct from station IDs. Resolve the matching visit
            // by station identity and departure time, then send the nested station ID.
            val originStop = state.resolvedOriginStop
                ?: state.selectedTripDetails?.stopovers?.let { stops ->
                    stops.getOrNull(resolveOriginIndex(stops, origin, departure))
                }
            val startStationId = originStop?.stationId ?: origin.id
            val destinationStationId = destination.stationId
            val departureTime = state.manualDeparture.ifBlank {
                originStop?.departurePlanned ?: originStop?.effectiveDeparture
                    ?: departure.plannedWhen ?: departure.realWhen ?: ""
            }
            val arrivalTime = state.manualArrival.ifBlank {
                destination.arrivalPlanned ?: destination.effectiveArrival ?: ""
            }
            if (startStationId == null || destinationStationId == null) {
                _uiState.update { it.copy(isLoading = false, error = "Start- oder Zielbahnhof hat keine gültige ID.") }
                return@launch
            }
            if (departureTime.isBlank() || arrivalTime.isBlank()) {
                _uiState.update { it.copy(isLoading = false, error = "Abfahrts- oder Ankunftszeit fehlt.") }
                return@launch
            }

            val request = CheckInRequest(
                tripId               = departure.tripId,
                lineName             = departure.line?.name ?: "",
                startStationId       = startStationId,
                destinationStationId = destinationStationId,
                departure            = departureTime,
                arrival              = arrivalTime,
                body                 = state.statusBody.ifBlank { null },
                business             = state.travelReason.apiValue
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

    private fun resolveOriginIndex(
        stops: List<StopStation>,
        origin: TrainStation?,
        departure: DepartureTrip
    ): Int {
        if (origin == null) return -1
        val ibnr = origin.identifier("de_db_ibnr")
        val originWords = origin.name?.lowercase()?.split(Regex("\\W+"))
            ?.filter { it.length > 2 }.orEmpty()
        val matchingIndices = stops.indices.filter { index ->
            val stop = stops[index]
            val idMatch = origin.id != null && stop.stationId == origin.id
            val identifierMatch = ibnr != null && stop.stationIdentifier("de_db_ibnr") == ibnr
            val nameMatch = stop.stationId == null && stop.stationName?.let { name ->
                originWords.isNotEmpty() && originWords.all { name.lowercase().contains(it) }
            } == true
            idMatch || identifierMatch || nameMatch
        }
        return matchingIndices.firstOrNull { index ->
            val stop = stops[index]
            sameInstant(stop.departurePlanned, departure.plannedWhen) ||
                sameInstant(stop.effectiveDeparture, departure.realWhen)
        } ?: matchingIndices.firstOrNull() ?: -1
    }

    private fun sameInstant(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        if (first == second) return true
        return runCatching {
            OffsetDateTime.parse(first).toInstant() == OffsetDateTime.parse(second).toInstant()
        }.getOrDefault(false)
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
                    step = CheckInStep.DEPARTURES, selectedDeparture = null, selectedTripDetails = null, resolvedOriginStop = null
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
