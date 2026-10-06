package de.traewelling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.data.sev.SevJourneyEnricher
import de.traewelling.app.data.sev.SevStopResolver
import de.traewelling.app.service.JourneyTimeResolver
import de.traewelling.app.service.TrackingLiveState
import de.traewelling.app.service.TripTrackingService
import de.traewelling.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

data class StatusDetailUiState(
    val isLoading: Boolean = false,
    val status: Status? = null,
    val stopovers: List<StopStation> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = 0,
    val isDeleting: Boolean = false,
    val isOwnStatus: Boolean = false,
    val trackingState: TrackingLiveState? = null,
    val sevStops: Map<String, SevStopInfo> = emptyMap(),
    val isLoadingSevStops: Boolean = false,

    // Editing state
    val isEditing: Boolean = false,
    val isUpdating: Boolean = false,
    val editBody: String = "",
    val editDeparture: String = "",
    val editArrival: String = "",
    val editDestinationId: Int? = null,
    val editDestinationStop: StopStation? = null,
    val editVisibility: Int = 0
)

class StatusDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(StatusDetailUiState())
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var currentStatusId: Int? = null
    private var sevEnrichmentJob: Job? = null
    private var sevGeneration = 0L
    private var sevSnapshot: SevDetailSnapshot? = null

    init {
        viewModelScope.launch {
            combine(
                TripTrackingService.trackingLiveState,
                prefs.activeStatusId,
                _uiState.map { it.status?.id to it.isOwnStatus }.distinctUntilChanged()
            ) { tracking, activeStatusId, viewedStatus ->
                tracking?.takeIf {
                    viewedStatus.second && it.statusId == viewedStatus.first && it.statusId == activeStatusId
                }
            }.collect { tracking ->
                _uiState.update { state ->
                    state.copy(trackingState = tracking?.takeIf {
                        state.isOwnStatus && state.status?.id == it.statusId && currentStatusId == it.statusId
                    })
                }
            }
        }
    }

    fun loadStatusDetail(statusId: Int) {
        if (currentStatusId != statusId) {
            autoRefreshJob?.cancel()
            cancelSevEnrichment()
            _uiState.value = StatusDetailUiState()
        }
        currentStatusId = statusId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load status detail
            repo.getStatusDetail(statusId)
                .onSuccess statusLoaded@ { status ->
                    if (currentStatusId != statusId) return@statusLoaded
                    // Keep API stopovers intact. Manual and GPS times are resolved only for display.
                    // Load stopovers using the trip ID from the checkin
                    val tripId = status.checkin?.trip
                    if (tripId != null) {
                        repo.getStopovers(tripId)
                            .onSuccess stopsLoaded@ { stops ->
                                if (currentStatusId != statusId) return@stopsLoaded
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        status = statusWithStopoverBoundaries(status, stops),
                                        stopovers = stops,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                }
                                enrichSevStops(status, stops)
                            }
                            .onFailure { e ->
                                if (currentStatusId != statusId) return@onFailure
                                _uiState.update {
                                    val stops = compatibleExistingStopovers(status, it)
                                    it.copy(
                                        isLoading = false,
                                        status = statusWithStopoverBoundaries(status, stops),
                                        stopovers = stops,
                                        lastUpdated = System.currentTimeMillis(),
                                        error = "Halte konnten nicht geladen werden: ${e.message}"
                                    )
                                }
                                enrichSevStops(status, _uiState.value.stopovers)
                            }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                status = status,
                                stopovers = emptyList(),
                                lastUpdated = System.currentTimeMillis()
                            )
                        }
                        enrichSevStops(status, emptyList())
                    }
                    if (currentStatusId == statusId) checkIfOwnStatus(status)
                }
                .onFailure { e ->
                    if (currentStatusId != statusId) return@onFailure
                    _uiState.update {
                        it.copy(isLoading = false, error = "Status nicht gefunden: ${e.message}")
                    }
                }

            // Start auto-refresh for live delay data
            if (currentStatusId == statusId) startAutoRefresh(statusId)
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
        if (currentStatusId != statusId) return
        // Silently update — no loading spinner
        repo.getStatusDetail(statusId).onSuccess statusRefreshed@ { status ->
            if (currentStatusId != statusId) return@statusRefreshed
            val tripId = status.checkin?.trip
            if (tripId != null) {
                repo.getStopovers(tripId).onSuccess stopsRefreshed@ { stops ->
                    if (currentStatusId != statusId) return@stopsRefreshed
                    _uiState.update {
                        it.copy(
                            status = statusWithStopoverBoundaries(status, stops),
                            stopovers = stops,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    enrichSevStops(status, _uiState.value.stopovers)
                }.onFailure {
                    if (currentStatusId != statusId) return@onFailure
                    _uiState.update {
                        val stops = compatibleExistingStopovers(status, it)
                        it.copy(
                            status = statusWithStopoverBoundaries(status, stops),
                            stopovers = stops,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    enrichSevStops(status, _uiState.value.stopovers)
                }
            } else {
                _uiState.update {
                    it.copy(status = status, stopovers = emptyList(), lastUpdated = System.currentTimeMillis())
                }
                enrichSevStops(status, emptyList())
            }
        }
    }

    /** Public map requests run after the API snapshot is visible and never block its refresh. */
    private fun enrichSevStops(status: Status, stops: List<StopStation>) {
        if (currentStatusId != status.id) return
        val publishedStatus = _uiState.value.status?.takeIf { it.id == status.id } ?: return
        val checkin = publishedStatus.checkin
        if (checkin == null || stops.isEmpty() || !SevStopResolver.isReplacementBus(checkin)) {
            cancelSevEnrichment()
            _uiState.update { it.copy(sevStops = emptyMap(), isLoadingSevStops = false) }
            return
        }
        val snapshot = SevDetailSnapshot.from(publishedStatus, stops)
        val previousSnapshot = sevSnapshot
        if (previousSnapshot == snapshot && sevEnrichmentJob?.isActive == true) return
        sevEnrichmentJob?.cancel()
        val generation = ++sevGeneration
        sevSnapshot = snapshot
        _uiState.update {
            it.copy(
                sevStops = it.sevStops.takeIf { previousSnapshot == snapshot } ?: emptyMap(),
                isLoadingSevStops = previousSnapshot != snapshot || it.isLoadingSevStops
            )
        }
        sevEnrichmentJob = viewModelScope.launch {
            val resolved = try {
                SevJourneyEnricher.enrich(checkin, stops)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            _uiState.update { state ->
                if (currentStatusId == status.id && generation == sevGeneration &&
                    state.status?.let { SevDetailSnapshot.from(it, state.stopovers) } == snapshot) {
                    state.copy(sevStops = resolved, isLoadingSevStops = false)
                } else state
            }
        }
    }

    private fun cancelSevEnrichment() {
        ++sevGeneration
        sevEnrichmentJob?.cancel()
        sevEnrichmentJob = null
        sevSnapshot = null
    }

    private fun statusWithStopoverBoundaries(status: Status, stops: List<StopStation>): Status {
        val checkin = status.checkin ?: return status
        return status.copy(checkin = checkin.copy(
            origin = stops.singleOrNull { it.matchesStopover(checkin.origin) } ?: checkin.origin,
            destination = stops.singleOrNull { it.matchesStopover(checkin.destination) } ?: checkin.destination
        ))
    }

    private fun compatibleExistingStopovers(status: Status, state: StatusDetailUiState): List<StopStation> {
        val checkin = status.checkin ?: return emptyList()
        val previousCheckin = state.status?.checkin ?: return emptyList()
        if (state.status?.id != status.id || checkin.trip == null || checkin.trip != previousCheckin.trip ||
            (checkin.tripUuid != null && previousCheckin.tripUuid != null &&
                checkin.tripUuid != previousCheckin.tripUuid)) return emptyList()

        // A failed stopover refresh may keep the old snapshot, but never a changed visit or timetable.
        val boundaries = listOfNotNull(checkin.origin, checkin.destination)
        val compatible = boundaries.all { boundary ->
            state.stopovers.singleOrNull { stop ->
                stop.matchesStopover(boundary) &&
                    (boundary.stationId == null || stop.stationId == boundary.stationId) &&
                    plannedTimesCompatible(stop.arrivalPlanned, boundary.arrivalPlanned) &&
                    plannedTimesCompatible(stop.departurePlanned, boundary.departurePlanned)
            } != null
        }
        return state.stopovers.takeIf { compatible } ?: emptyList()
    }

    private fun plannedTimesCompatible(previous: String?, incoming: String?): Boolean =
        incoming == null || previous == incoming ||
            runCatching { Instant.parse(previous) == Instant.parse(incoming) }.getOrDefault(false)

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
                editDeparture = JourneyTimeResolver.departure(status.checkin?.origin, null, 0,
                    status.checkin?.manualDeparture)?.millis?.let { millis -> Instant.ofEpochMilli(millis).toString() } ?: "",
                editArrival = JourneyTimeResolver.arrival(status.checkin?.destination, null, 0,
                    status.checkin?.manualArrival)?.millis?.let { millis -> Instant.ofEpochMilli(millis).toString() } ?: "",
                editDestinationId = status.checkin?.destination?.stationId,
                editDestinationStop = status.checkin?.destination,
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

    fun updateEditDestination(stop: StopStation) {
        _uiState.update {
            it.copy(
                editDestinationId = stop.stationId,
                editDestinationStop = stop,
                editArrival = JourneyTimeResolver.arrival(stop, null, 0)?.millis?.let { millis -> Instant.ofEpochMilli(millis).toString() } ?: ""
            )
        }
    }

    fun updateEditVisibility(visibility: Int) {
        _uiState.update { it.copy(editVisibility = visibility) }
    }

    fun saveStatusEdit() {
        val statusId = currentStatusId ?: return
        val state = _uiState.value
        val destination = state.editDestinationStop
        val originalDestination = state.status?.checkin?.destination
        val destinationChanged = destination != originalDestination &&
            destination?.matchesStopover(originalDestination) != true
        if (destinationChanged && (destination?.stationId == null || destination?.arrivalPlanned.isNullOrBlank())) {
            _uiState.update {
                it.copy(error = "Für das neue Ziel fehlen eine gültige Stations-ID oder die geplante Ankunftszeit.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            
            val request = de.traewelling.app.data.model.UpdateStatusRequest(
                body = state.editBody,
                visibility = state.editVisibility,
                destination = if (destinationChanged) destination?.stationId else null,
                destinationArrivalPlanned = if (destinationChanged) destination?.arrivalPlanned else null,
                departure = state.editDeparture,
                arrival = state.editArrival
            )
            
            repo.updateStatus(statusId, request)
                .onSuccess { updatedStatus ->
                    _uiState.update { 
                        val stops = compatibleExistingStopovers(updatedStatus, it)
                        it.copy(
                            isUpdating = false, 
                            isEditing = false,
                            status = statusWithStopoverBoundaries(updatedStatus, stops),
                            stopovers = stops,
                            sevStops = emptyMap(),
                            isLoadingSevStops = false
                        )
                    }
                    cancelSevEnrichment()
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
                _uiState.update {
                    if (currentStatusId == status.id && it.status?.id == status.id) {
                        it.copy(isOwnStatus = status.user?.id == currentUser.id)
                    } else it
                }
            }
        }
    }

    fun reset() {
        autoRefreshJob?.cancel()
        cancelSevEnrichment()
        currentStatusId = null
        _uiState.value = StatusDetailUiState()
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        cancelSevEnrichment()
    }
}

/** API real-time changes do not change the physical replacement-stop assignment. */
private data class SevDetailSnapshot(
    val statusId: Int,
    val tripId: Int?,
    val tripUuid: String?,
    val category: String?,
    val mode: String?,
    val lineName: String?,
    val originVisit: String?,
    val destinationVisit: String?,
    val stops: List<SevDetailVisit>
) {
    companion object {
        fun from(status: Status, stops: List<StopStation>): SevDetailSnapshot {
            val checkin = status.checkin
            return SevDetailSnapshot(
                status.id, checkin?.trip, checkin?.tripUuid, checkin?.category, checkin?.mode,
                checkin?.lineName, checkin?.origin?.let(SevStopResolver::visitKey),
                checkin?.destination?.let(SevStopResolver::visitKey),
                stops.map { stop -> SevDetailVisit(
                    SevStopResolver.visitKey(stop), stop.station, stop.arrivalPlanned,
                    stop.departurePlanned, stop.cancelled
                ) }
            )
        }
    }
}

private data class SevDetailVisit(
    val key: String,
    val station: TrainStation?,
    val arrivalPlanned: String?,
    val departurePlanned: String?,
    val cancelled: Boolean?
)
