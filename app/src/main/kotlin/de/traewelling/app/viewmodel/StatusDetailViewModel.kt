package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StatusDetailUiState(
    val isLoading: Boolean = false,
    val status: Status? = null,
    val stopovers: List<StopStation> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = 0,
    val isDeleting: Boolean = false,
    val isOwnStatus: Boolean = false,

    // Station Coordinates (ID -> Pair(Lat, Lon))
    val stationCoordinates: Map<Int, Pair<Double, Double>> = emptyMap(),

    // Editing state
    val isEditing: Boolean = false,
    val isUpdating: Boolean = false,
    val editBody: String = "",
    val editDeparture: String = "",
    val editArrival: String = "",
    val editDestinationId: Int? = null,
    val editVisibility: Int = 0
)

class StatusDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(prefs)

    private val _uiState = MutableStateFlow(StatusDetailUiState())
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var currentStatusId: Int? = null

    fun loadStatusDetail(statusId: Int) {
        currentStatusId = statusId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load status detail
            repo.getStatusDetail(statusId)
                .onSuccess { status ->
                    _uiState.update { it.copy(status = status) }
                    checkIfOwnStatus(status)

                    // Load stopovers using the trip ID from the checkin
                    val tripId = status.checkin?.trip
                    if (tripId != null) {
                        repo.getStopovers(tripId)
                            .onSuccess { stops ->
                                // Enrich stopovers with manual times from the checkin origin/destination
                                val origin = status.checkin?.origin?.let {
                                    it.copy(departureReal = status.checkin?.manualDeparture ?: it.departureReal)
                                }
                                val destination = status.checkin?.destination?.let {
                                    it.copy(arrivalReal = status.checkin?.manualArrival ?: it.arrivalReal)
                                }
                                
                                val enrichedStatus = status.copy(
                                    checkin = status.checkin?.copy(
                                        origin = origin,
                                        destination = destination
                                    )
                                )
                                
                                val enrichedStops: List<de.traewelling.app.data.model.StopStation> = stops.map { stop ->
                                    when (stop.id) {
                                        origin?.id -> if (origin?.id != null) origin else stop
                                        destination?.id -> if (destination?.id != null) destination else stop
                                        else -> stop
                                    }
                                }
                                
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        status = enrichedStatus,
                                        stopovers = enrichedStops,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                }
                                loadCoordinatesForStops(enrichedStops)
                            }
                            .onFailure { e ->
                                _uiState.update {
                                    it.copy(isLoading = false, error = "Halte konnten nicht geladen werden: ${e.message}")
                                }
                            }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Status nicht gefunden: ${e.message}")
                    }
                }

            // Start auto-refresh for live delay data
            startAutoRefresh(statusId)
        }
    }

    private fun startAutoRefresh(statusId: Int) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000) // Refresh every 30 seconds
                refreshSilently(statusId)
            }
        }
    }

    private suspend fun refreshSilently(statusId: Int) {
        // Silently update — no loading spinner
        repo.getStatusDetail(statusId).onSuccess { status ->
            _uiState.update { it.copy(status = status) }

            val tripId = status.checkin?.trip
            if (tripId != null) {
                repo.getStopovers(tripId).onSuccess { stops ->
                    val origin = status.checkin?.origin?.let {
                        it.copy(departureReal = status.checkin?.manualDeparture ?: it.departureReal)
                    }
                    val destination = status.checkin?.destination?.let {
                        it.copy(arrivalReal = status.checkin?.manualArrival ?: it.arrivalReal)
                    }
                    
                    val enrichedStatus = status.copy(
                        checkin = status.checkin?.copy(
                            origin = origin,
                            destination = destination
                        )
                    )
                    
                    val enrichedStops: List<de.traewelling.app.data.model.StopStation> = stops.map { stop ->
                        when (stop.id) {
                            origin?.id -> if (origin?.id != null) origin else stop
                            destination?.id -> if (destination?.id != null) destination else stop
                            else -> stop
                        }
                    }
                    _uiState.update {
                        it.copy(
                            status = enrichedStatus,
                            stopovers = enrichedStops, 
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    loadCoordinatesForStops(enrichedStops)
                }
            }
        }
    }

    private fun loadCoordinatesForStops(stops: List<de.traewelling.app.data.model.StopStation>) {
        viewModelScope.launch {
            val coords = mutableMapOf<Int, Pair<Double, Double>>()
            // Add existing coordinates so we don't refetch
            coords.putAll(_uiState.value.stationCoordinates)

            stops.forEach { stop ->
                val id = stop.id
                if (id != null && !coords.containsKey(id)) {
                    repo.getStation(id).onSuccess { station ->
                        val lat = station.latitude
                        val lon = station.longitude
                        if (lat != null && lon != null) {
                            coords[id] = Pair(lat, lon)
                        }
                    }
                }
            }
            _uiState.update { it.copy(stationCoordinates = coords) }
        }
    }

    fun refresh() {
        currentStatusId?.let { loadStatusDetail(it) }
    }

    fun deleteStatus(onSuccess: () -> Unit) {
        val statusId = currentStatusId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            repo.deleteStatus(statusId)
                .onSuccess {
                    _uiState.update { it.copy(isDeleting = false) }
                    onSuccess()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isDeleting = false, error = "Löschen fehlgeschlagen: ${e.message}") }
                }
        }
    }

    // ─── Editing ──────────────────────────────────────────────────────────────

    fun startEditing() {
        val status = _uiState.value.status ?: return
        _uiState.update {
            it.copy(
                isEditing = true,
                editBody = status.body ?: "",
                editDeparture = status.checkin?.origin?.departureReal ?: status.checkin?.origin?.departurePlanned ?: "",
                editArrival = status.checkin?.destination?.arrivalReal ?: status.checkin?.destination?.arrivalPlanned ?: "",
                editDestinationId = status.checkin?.destination?.id,
                editVisibility = status.visibility ?: 0
            )
        }
    }

    fun stopEditing() {
        _uiState.update { it.copy(isEditing = false) }
    }

    fun updateEditBody(body: String) {
        _uiState.update { it.copy(editBody = body) }
    }

    fun updateEditDeparture(time: String) {
        _uiState.update { it.copy(editDeparture = time) }
    }

    fun updateEditArrival(time: String) {
        _uiState.update { it.copy(editArrival = time) }
    }

    fun updateEditDestination(stationId: Int) {
        _uiState.update { it.copy(editDestinationId = stationId) }
    }

    fun updateEditVisibility(visibility: Int) {
        _uiState.update { it.copy(editVisibility = visibility) }
    }

    fun saveStatusEdit() {
        val statusId = currentStatusId ?: return
        val state = _uiState.value
        
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            
            val request = de.traewelling.app.data.model.UpdateStatusRequest(
                body = state.editBody,
                visibility = state.editVisibility,
                destination = state.editDestinationId,
                departure = state.editDeparture,
                arrival = state.editArrival
            )
            
            repo.updateStatus(statusId, request)
                .onSuccess { updatedStatus ->
                    _uiState.update { 
                        it.copy(
                            isUpdating = false, 
                            isEditing = false,
                            status = updatedStatus
                        )
                    }
                    // Refresh to get updated stopovers if destination changed
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { 
                        it.copy(isUpdating = false, error = "Änderung fehlgeschlagen: ${e.message}") 
                    }
                }
        }
    }

    private fun checkIfOwnStatus(status: Status) {
        viewModelScope.launch {
            repo.getCurrentUser().onSuccess { currentUser ->
                _uiState.update { it.copy(isOwnStatus = status.user?.id == currentUser.id) }
            }
        }
    }

    fun reset() {
        autoRefreshJob?.cancel()
        currentStatusId = null
        _uiState.value = StatusDetailUiState()
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
    }
}
