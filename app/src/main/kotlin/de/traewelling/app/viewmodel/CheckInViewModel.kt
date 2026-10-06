package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.*
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.service.RecognizedRide
import de.traewelling.app.service.RideRecognitionService
import de.traewelling.app.service.RideRecognitionState
import de.traewelling.app.service.RideRecognitionEngine
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
    val completionWarning: String? = null,
    val resolvedOriginStop: StopStation? = null,
    val rideRecognitionEnabled: Boolean = false,
    val rideRecognition: RideRecognitionState = RideRecognitionState(),
    val activeRidePresent: Boolean = false
)

class CheckInViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var checkInJob: Job? = null
    private var selectionGeneration = 0L

    init {
        viewModelScope.launch {
            combine(prefs.rideRecognitionEnabled, RideRecognitionService.state, prefs.activeStatusId, prefs.authSession) { enabled, recognition, active, session ->
                Triple(enabled, recognition.takeIf { session.accessToken != null && it.authSessionRevision == session.revision }
                    ?: RideRecognitionState(), active != null)
            }.collect { (enabled, recognition, active) ->
                _uiState.update { it.copy(rideRecognitionEnabled = enabled, rideRecognition = recognition, activeRidePresent = active) }
            }
        }
    }

    /** Acceptance only prepares a manual check-in. It never publishes a status. */
    fun acceptRecognizedRide(candidate: RecognizedRide) {
        val requestedSelection = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val current = RideRecognitionService.state.value
            val freshCandidate = current.candidates.firstOrNull {
                it.id == candidate.id && it.sessionId == candidate.sessionId &&
                    it.latestFixMillis == candidate.latestFixMillis
            }
            val enabled = prefs.getRideRecognitionEnabled()
            val active = prefs.activeStatusId.first()
            val session = prefs.getAuthSession()
            val loggedIn = session.accessToken != null
            val now = System.currentTimeMillis()
            ensureActive()
            if (requestedSelection != selectionGeneration) return@launch
            if (!enabled || active != null || !loggedIn || current.authSessionRevision != session.revision || freshCandidate == null ||
                now - freshCandidate.latestFixMillis !in 0..RideRecognitionEngine.MAX_FIX_AGE_MILLIS ||
                current.sessionId != candidate.sessionId || RideRecognitionService.state.value != current) {
                _uiState.update { it.copy(error = "Dieser Vorschlag ist nicht mehr aktuell. Bitte warte auf eine neue Erkennung.") }
                return@launch
            }
            val ride = freshCandidate.ride
            val origin = ride.origin ?: return@launch
            val originStation = origin.station ?: return@launch
            val destinations = validCheckInDestinations(ride.trip.stopovers.orEmpty(), ride.originIndex)
            if (destinations.isEmpty()) {
                _uiState.update { it.copy(error = "Für diese Fahrt sind keine gültigen Ziele verfügbar.") }
                return@launch
            }
            _uiState.update { it.copy(
                step = CheckInStep.DESTINATION, selectedStation = originStation,
                selectedDeparture = ride.departure.copy(station = originStation),
                selectedTripDetails = ride.trip, resolvedOriginStop = origin,
                filteredDestinations = destinations, selectedDestination = null,
                isLoading = false, error = null, stationQuery = originStation.name.orEmpty()
            ) }
        }
    }


    // ─── Step 1: Station search ───────────────────────────────────────────────

    fun beginLocationLookup(): Long {
        val request = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        return request
    }

    fun isLocationLookupCurrent(request: Long): Boolean =
        request == selectionGeneration && _uiState.value.step == CheckInStep.STATION

    fun finishLocationLookup(request: Long, latitude: Double?, longitude: Double?, error: String? = null) {
        if (!isLocationLookupCurrent(request)) return
        if (latitude != null && longitude != null) searchNearbyStations(latitude, longitude)
        else _uiState.update { it.copy(isLoading = false, error = error ?: "Kein aktueller Standort verfügbar. Bitte suche die Station manuell.") }
    }

    fun cancelLocationLookup(request: Long) {
        if (!isLocationLookupCurrent(request)) return
        ++selectionGeneration
        _uiState.update { it.copy(isLoading = false) }
    }

    fun searchNearbyStations(lat: Double, lon: Double) {
        val requestGeneration = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, stationQuery = "Stationen in der Nähe...") }
            repo.getNearbyStations(lat, lon)
                .onSuccess { stations ->
                    if (requestGeneration != selectionGeneration) return@onSuccess
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
                    if (e is CancellationException) throw e
                    if (requestGeneration != selectionGeneration) return@onFailure
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
        val requestGeneration = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        _uiState.update { it.copy(stationQuery = query, searchResults = emptyList(), error = null, isLoading = false) }
        if (query.length < 2) return
        searchJob = viewModelScope.launch {
            delay(350)
            if (requestGeneration != selectionGeneration) return@launch
            _uiState.update { it.copy(isLoading = true) }
            repo.searchStations(query)
                .onSuccess { stations ->
                    if (requestGeneration != selectionGeneration) return@onSuccess
                    _uiState.update { it.copy(isLoading = false, searchResults = stations.distinctBy { it.id }) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (requestGeneration != selectionGeneration) return@onFailure
                    _uiState.update { it.copy(isLoading = false, error = "Suche fehlgeschlagen: ${e.message}") }
                }
        }
    }

    // ─── Step 2: Load departures using station.id ─────────────────────────────

    fun selectStation(station: TrainStation) {
        val requestGeneration = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        val stationId = station.id
        if (stationId == null) {
            _uiState.update { it.copy(error = "Bahnhof hat keine gültige ID.") }
            return
        }
        selectionJob = viewModelScope.launch {
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
                    if (requestGeneration != selectionGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(isLoading = false, departures = trips, step = CheckInStep.DEPARTURES)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (requestGeneration != selectionGeneration) return@onFailure
                    _uiState.update {
                        it.copy(isLoading = false, error = "Abfahrten konnten nicht geladen werden: ${e.message}")
                    }
                }
        }
    }

    // ─── Step 3: User picks a departure → load full trip (stopovers) ──────────

    fun selectTrip(departure: DepartureTrip) {
        val requestGeneration = ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        if ((departure.tripId as String?).isNullOrBlank()) {
            _uiState.update { it.copy(isLoading = false, error = "Für diese Abfahrt fehlt die Fahrt-ID.") }
            return
        }
        val lineName = departure.line?.name ?: ""
        selectionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(selectedDeparture = departure, isLoading = true, error = null)
            }
            repo.getTrip(hafasTripId = departure.tripId, lineName = lineName)
                .onSuccess { tripDetails ->
                    if (requestGeneration != selectionGeneration) return@onSuccess
                    // Radius-based departure queries can return a nearby station's service.
                    // The departure's station is the authoritative boarding station.
                    val origin = departure.station ?: _uiState.value.selectedStation
                    val stopovers = tripDetails.stopovers ?: emptyList()
                    
                    val finalOriginIdx = resolveCheckInOriginIndex(stopovers, origin, departure)

                    // Only show stations AFTER the origin as possible destinations
                    val filteredStopovers = validCheckInDestinations(stopovers, finalOriginIdx)
                    if (filteredStopovers.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false,
                            error = if (finalOriginIdx < 0) "Der Einstiegshalt konnte nicht eindeutig bestimmt werden. Bitte wähle eine andere Abfahrt."
                                else "Für diese Fahrt sind keine gültigen Ziele nach dem Einstieg verfügbar.") }
                        return@onSuccess
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
                    if (e is CancellationException) throw e
                    if (requestGeneration != selectionGeneration) return@onFailure
                    _uiState.update {
                        it.copy(isLoading = false, error = "Halte konnten nicht geladen werden: ${e.message}")
                    }
                }
        }
    }

    // ─── Step 4: User picks destination stopover ───────────

    fun selectDestination(stopStation: StopStation) {
        if (_uiState.value.isLoading) return
        if (stopStation.cancelled == true || stopStation.stationId == null ||
            _uiState.value.filteredDestinations.none { it.matchesStopover(stopStation) }) {
            _uiState.update { it.copy(error = "Dieser Zielhalt ist für die ausgewählte Fahrt nicht verfügbar.") }
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
        if (state.isLoading || state.step != CheckInStep.CONFIRM) return
        val departure   = state.selectedDeparture   ?: return
        val origin      = departure.station ?: state.selectedStation ?: return
        val destination = state.selectedDestination ?: return
        if ((departure.tripId as String?).isNullOrBlank() || destination.cancelled == true ||
            state.filteredDestinations.none { it.matchesStopover(destination) }) {
            _uiState.update { it.copy(error = "Die ausgewählte Fahrt oder der Zielhalt ist nicht mehr gültig.") }
            return
        }
        val requestGeneration = selectionGeneration
        _uiState.update { it.copy(isLoading = true, error = null) }

        checkInJob = viewModelScope.launch {
            val session = prefs.getAuthSession()
            ensureActive()
            if (requestGeneration != selectionGeneration) return@launch
            if (session.accessToken == null) {
                _uiState.update { it.copy(isLoading = false, error = "Bitte melde dich erneut an.") }
                return@launch
            }

            // Stopover IDs are distinct from station IDs. Resolve the matching visit
            // by station identity and departure time, then send the nested station ID.
            val originStop = state.resolvedOriginStop
                ?: state.selectedTripDetails?.stopovers?.let { stops ->
                    stops.getOrNull(resolveCheckInOriginIndex(stops, origin, departure))
                }
            val submission = try {
                buildCheckInSubmission(state, originStop)
            } catch (invalid: IllegalArgumentException) {
                _uiState.update { it.copy(isLoading = false, error = invalid.message) }
                return@launch
            }
            submitCheckIn(
                submission = submission,
                create = { repo.checkIn(it, expectedSession = session) },
                onCreated = { created ->
                    ensureActive()
                    if (requestGeneration != selectionGeneration)
                        throw CancellationException("Check-in selection changed")
                    // Once POST succeeded, no later correction/storage failure may offer another POST.
                    _uiState.update { it.copy(checkInResult = created, step = CheckInStep.SUCCESS, isLoading = true) }
                    if (prefs.getAuthSession() != session)
                        throw CancellationException("Session changed after check-in")
                    if (created?.status?.id != null && !prefs.saveActiveStatusIdIfMatches(session, created.status.id))
                        throw CancellationException("Session changed after check-in")
                },
                correctTimes = { statusId, request -> repo.updateStatus(statusId, request, expectedSession = session) }
            )
                .onSuccess { completion ->
                    ensureActive()
                    if (requestGeneration != selectionGeneration || prefs.getAuthSession() != session) return@onSuccess
                    _uiState.update { it.copy(isLoading = false, checkInResult = completion.result,
                        completionWarning = completion.warning, step = CheckInStep.SUCCESS) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (requestGeneration != selectionGeneration) return@onFailure
                    _uiState.update {
                        if (it.step == CheckInStep.SUCCESS) it.copy(isLoading = false,
                            completionWarning = "Der Check-in wurde angenommen. Weitere Schritte sind fehlgeschlagen; prüfe die Fahrt im Profil. Ein erneuter Check-in ist nicht nötig.")
                        else it.copy(isLoading = false, error = "Check-in fehlgeschlagen: ${e.message}")
                    }
                }
        }
    }

    fun reset() {
        if (_uiState.value.step == CheckInStep.SUCCESS && _uiState.value.isLoading) return
        selectionGeneration++
        searchJob?.cancel()
        selectionJob?.cancel()
        checkInJob?.cancel()
        _uiState.update { CheckInUiState(rideRecognitionEnabled = it.rideRecognitionEnabled,
            rideRecognition = it.rideRecognition, activeRidePresent = it.activeRidePresent) }
    }

    fun goBack() {
        // A submitted POST may already have created a status on the server.
        if (_uiState.value.step == CheckInStep.CONFIRM && _uiState.value.isLoading) return
        selectionGeneration++
        searchJob?.cancel()
        selectionJob?.cancel()
        _uiState.update { state ->
            val idleState = state.copy(isLoading = false, error = null)
            when (state.step) {
                CheckInStep.DEPARTURES  -> idleState.copy(
                    step = CheckInStep.STATION, selectedStation = null, departures = emptyList()
                )
                CheckInStep.DESTINATION -> idleState.copy(
                    step = CheckInStep.DEPARTURES, selectedDeparture = null, selectedTripDetails = null, resolvedOriginStop = null
                )
                CheckInStep.CONFIRM     -> idleState.copy(
                    step = CheckInStep.DESTINATION, selectedDestination = null
                )
                else -> idleState
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
