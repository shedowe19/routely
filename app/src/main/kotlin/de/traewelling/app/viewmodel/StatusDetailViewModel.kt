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
import android.util.Log
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class StatusDetailUiState(
    val isLoading: Boolean = false,
    val status: Status? = null,
    val stopovers: List<StopStation> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = 0,
    val isDeleting: Boolean = false,
    val isOwnStatus: Boolean = false,

    // Editing state
    val isEditing: Boolean = false,
    val isUpdating: Boolean = false,
    val editBody: String = "",
    val editDeparture: String = "",
    val editArrival: String = "",
    val editDestinationId: Int? = null,
    val editVisibility: Int = 0,

    val editingStopoverId: Int? = null,
    val editingStopoverDeparture: String = "",
    val manualStopoverDepartures: Map<Int, String> = emptyMap()
)

class StatusDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(StatusDetailUiState())
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var currentStatusId: Int? = null
    private var rawStopovers: List<StopStation> = emptyList()

    fun loadStatusDetail(statusId: Int) {
        currentStatusId = statusId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load status detail
            repo.getStatusDetail(statusId)
                .onSuccess { status ->
                    // Enrich status with manual times from the checkin right away
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

                    _uiState.update { it.copy(status = enrichedStatus) }
                    checkIfOwnStatus(enrichedStatus)

                    // Load stopovers using the trip ID from the checkin
                    val tripId = enrichedStatus.checkin?.trip
                    if (tripId != null) {
                        repo.getStopovers(tripId)
                            .onSuccess { stops ->
                                rawStopovers = stops
                                val enrichedStops = enrichStops(stops, origin, destination)
                                
                                val finalOrigin = enrichedStops.find { it.id == origin?.id } ?: origin
                                val finalDestination = enrichedStops.find { it.id == destination?.id } ?: destination
                                val finalStatus = enrichedStatus.copy(
                                    checkin = enrichedStatus.checkin?.copy(
                                        origin = finalOrigin,
                                        destination = finalDestination
                                    )
                                )

                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        status = finalStatus,
                                        stopovers = enrichedStops,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                }
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
            // Enrich status with manual times from the checkin right away
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

            _uiState.update { it.copy(status = enrichedStatus) }

            val tripId = enrichedStatus.checkin?.trip
            if (tripId != null) {
                repo.getStopovers(tripId).onSuccess { stops ->
                    rawStopovers = stops
                    val enrichedStops = enrichStops(stops, origin, destination)

                    val finalOrigin = enrichedStops.find { it.id == origin?.id } ?: origin
                    val finalDestination = enrichedStops.find { it.id == destination?.id } ?: destination
                    val finalStatus = enrichedStatus.copy(
                        checkin = enrichedStatus.checkin?.copy(
                            origin = finalOrigin,
                            destination = finalDestination
                        )
                    )

                    _uiState.update {
                        it.copy(
                            status = finalStatus,
                            stopovers = enrichedStops, 
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    private fun enrichStops(stops: List<StopStation>, origin: StopStation?, destination: StopStation?): List<StopStation> {
        val manualDepartures = _uiState.value.manualStopoverDepartures
        val mappedStops = stops.map { stop ->
            var finalStop = when (stop.id) {
                origin?.id -> if (origin?.id != null) origin else stop
                destination?.id -> if (destination?.id != null) destination else stop
                else -> stop
            }
            val manualDeparture = manualDepartures[finalStop.id]
            if (manualDeparture != null) {
                finalStop = finalStop.copy(departureReal = manualDeparture)
            }
            finalStop
        }
        return propagateDelays(mappedStops)
    }

    private fun propagateDelays(stops: List<StopStation>): List<StopStation> {
        var currentDelayMinutes: Long = 0
        var lastDeparturePlanned: ZonedDateTime? = null
        var fractionalRecovery = 0.0

        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        return stops.map { stop ->
            var updatedStop = stop
            var delayUpdated = false

            val plannedArrivalZdt = try { stop.arrivalPlanned?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                Log.w("StatusDetailViewModel", "Malformed arrivalPlanned time for stop ${stop.id}: ${stop.arrivalPlanned}", e)
                null
            }
            val realArrivalZdt = try { stop.arrivalReal?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                Log.w("StatusDetailViewModel", "Malformed arrivalReal time for stop ${stop.id}: ${stop.arrivalReal}", e)
                null
            }

            val plannedDepartureZdt = try { stop.departurePlanned?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                Log.w("StatusDetailViewModel", "Malformed departurePlanned time for stop ${stop.id}: ${stop.departurePlanned}", e)
                null
            }
            val realDepartureZdt = try { stop.departureReal?.let { ZonedDateTime.parse(it) } } catch (e: DateTimeParseException) {
                Log.w("StatusDetailViewModel", "Malformed departureReal time for stop ${stop.id}: ${stop.departureReal}", e)
                null
            }

            // 1. Process Travel Time Recovery (Arrival)
            if (plannedArrivalZdt != null) {
                var apiDelayMinutes: Long? = null
                if (realArrivalZdt != null && !realArrivalZdt.isEqual(plannedArrivalZdt)) {
                    apiDelayMinutes = ChronoUnit.MINUTES.between(plannedArrivalZdt, realArrivalZdt)
                }

                if (currentDelayMinutes > 0 && lastDeparturePlanned != null) {
                    val travelTime = ChronoUnit.MINUTES.between(lastDeparturePlanned, plannedArrivalZdt).coerceAtLeast(0)
                    fractionalRecovery += travelTime * 0.05

                    if (fractionalRecovery >= 1.0) {
                        val recoveredMins = fractionalRecovery.toLong()
                        currentDelayMinutes = (currentDelayMinutes - recoveredMins).coerceAtLeast(0)
                        fractionalRecovery -= recoveredMins
                    }
                }

                if (apiDelayMinutes != null && apiDelayMinutes > currentDelayMinutes) {
                    currentDelayMinutes = apiDelayMinutes
                    fractionalRecovery = 0.0
                }

                if (currentDelayMinutes != 0L) {
                    val newRealArrival = plannedArrivalZdt.plusMinutes(currentDelayMinutes).format(formatter)
                    if (updatedStop.arrivalReal != newRealArrival || updatedStop.isArrivalDelayed != (currentDelayMinutes > 0L)) {
                        updatedStop = updatedStop.copy(
                            arrivalReal = newRealArrival,
                            isArrivalDelayed = currentDelayMinutes > 0
                        )
                        delayUpdated = true
                    }
                }
            }

            // 2. Process Dwell Time Recovery (Departure)
            if (plannedDepartureZdt != null) {
                var apiDelayMinutes: Long? = null
                if (realDepartureZdt != null && !realDepartureZdt.isEqual(plannedDepartureZdt)) {
                    apiDelayMinutes = ChronoUnit.MINUTES.between(plannedDepartureZdt, realDepartureZdt)
                }

                if (currentDelayMinutes > 0 && plannedArrivalZdt != null && plannedDepartureZdt.isAfter(plannedArrivalZdt)) {
                    val dwellTime = ChronoUnit.MINUTES.between(plannedArrivalZdt, plannedDepartureZdt)
                    if (dwellTime > 1) {
                        val recoveryFromDwell = dwellTime - 1
                        fractionalRecovery += recoveryFromDwell * 0.05
                    }

                    if (fractionalRecovery >= 1.0) {
                        val recoveredMins = fractionalRecovery.toLong()
                        currentDelayMinutes = (currentDelayMinutes - recoveredMins).coerceAtLeast(0)
                        fractionalRecovery -= recoveredMins
                    }
                }

                if (apiDelayMinutes != null && apiDelayMinutes > currentDelayMinutes) {
                    currentDelayMinutes = apiDelayMinutes
                    fractionalRecovery = 0.0
                }

                if (currentDelayMinutes != 0L) {
                    val newRealDeparture = plannedDepartureZdt.plusMinutes(currentDelayMinutes).format(formatter)
                    if (updatedStop.departureReal != newRealDeparture || updatedStop.isDepartureDelayed != (currentDelayMinutes > 0L)) {
                        updatedStop = updatedStop.copy(
                            departureReal = newRealDeparture,
                            isDepartureDelayed = currentDelayMinutes > 0
                        )
                        delayUpdated = true
                    }
                }
                lastDeparturePlanned = plannedDepartureZdt
            } else if (plannedArrivalZdt != null) {
                // If it's a destination station (no departure), update last planned for completeness
                lastDeparturePlanned = plannedArrivalZdt
            }

            if (delayUpdated) updatedStop else stop
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

    fun startEditingStopover(stop: StopStation) {
        val id = stop.id ?: return
        val currentManual = _uiState.value.manualStopoverDepartures[id]
        val defaultTime = stop.departureReal ?: stop.departurePlanned ?: stop.departure ?: ""
        _uiState.update {
            it.copy(
                editingStopoverId = id,
                editingStopoverDeparture = currentManual ?: defaultTime
            )
        }
    }

    fun stopEditingStopover() {
        _uiState.update { it.copy(editingStopoverId = null, editingStopoverDeparture = "") }
    }

    fun updateEditingStopoverDeparture(time: String) {
        _uiState.update { it.copy(editingStopoverDeparture = time) }
    }

    fun modifyEditingStopoverDeparture(minutes: Long) {
        val current = _uiState.value.editingStopoverDeparture
        if (current.isBlank()) return
        try {
            val zdt = ZonedDateTime.parse(current)
            val newTime = zdt.plusMinutes(minutes).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            _uiState.update { it.copy(editingStopoverDeparture = newTime) }
        } catch (e: Exception) {
            Log.w("StatusDetailViewModel", "Could not parse time for modification: $current", e)
        }
    }

    fun saveStopoverDeparture() {
        val id = _uiState.value.editingStopoverId ?: return
        val time = _uiState.value.editingStopoverDeparture

        if (time.isNotBlank()) {
            try {
                ZonedDateTime.parse(time)
            } catch (e: DateTimeParseException) {
                Log.w("StatusDetailViewModel", "Validation failed for manual time: $time", e)
                return
            }
        }

        _uiState.update { state ->
            val newMap = state.manualStopoverDepartures.toMutableMap()
            if (time.isBlank()) {
                newMap.remove(id)
            } else {
                newMap[id] = time
            }
            state.copy(manualStopoverDepartures = newMap, editingStopoverId = null, editingStopoverDeparture = "")
        }

        val origin = _uiState.value.status?.checkin?.origin
        val destination = _uiState.value.status?.checkin?.destination
        val enrichedStops = enrichStops(rawStopovers, origin, destination)
        _uiState.update { it.copy(stopovers = enrichedStops) }
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
    }
}
