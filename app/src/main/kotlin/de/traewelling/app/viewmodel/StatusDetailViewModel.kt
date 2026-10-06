package de.traewelling.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    val editArrivalManuallyChanged: Boolean? = null,
    val editDestinationId: Int? = null,
    val editDestinationStop: StopStation? = null,
    val editInitialStatus: Status? = null,
    val editVisibility: Int = 0
)

class StatusDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val _uiState = MutableStateFlow(StatusDetailUiState())
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var currentStatusId: Int? = null
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var loadGeneration = 0L
    private var viewGeneration = 0L
    private var editInitialDeparture = ""
    private var editInitialArrival = ""
    private var sevEnrichmentJob: Job? = null
    private var sevGeneration = 0L
    private var sevSnapshot: SevDetailSnapshot? = null

    init {
        viewModelScope.launch {
            combine(
                TripTrackingService.trackingLiveState,
                prefs.activeStatusId,
                prefs.authSession,
                _uiState.map { it.status?.id to it.isOwnStatus }.distinctUntilChanged()
            ) { tracking, activeStatusId, session, viewedStatus ->
                tracking?.takeIf {
                    session.accessToken != null && it.sessionRevision == session.revision &&
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
        if (statusId <= 0) return
        if (currentStatusId != statusId) {
            ++viewGeneration
            mutationJob?.cancel()
            cancelSevEnrichment()
            _uiState.value = StatusDetailUiState()
        } else if (_uiState.value.isUpdating || _uiState.value.isDeleting) return
        currentStatusId = statusId
        autoRefreshJob?.cancel()
        loadJob?.cancel()
        val request = ++loadGeneration
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            loadSnapshot(statusId, request, showLoading = true)
            if (currentStatusId == statusId && request == loadGeneration) startAutoRefresh(statusId)
        }
    }

    private fun startAutoRefresh(statusId: Int) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (currentStatusId != statusId || loadJob?.isActive == true ||
                    _uiState.value.isUpdating || _uiState.value.isDeleting) continue
                loadSnapshot(statusId, ++loadGeneration, showLoading = false)
            }
        }
    }

    private suspend fun loadSnapshot(statusId: Int, request: Long, showLoading: Boolean) {
        val result = repo.getStatusDetail(statusId)
        coroutineContext.ensureActive()
        if (currentStatusId != statusId || request != loadGeneration) return
        val status = result.getOrElse { error ->
            if (showLoading) _uiState.update {
                it.copy(isLoading = false, error = "Status nicht gefunden: ${error.message}")
            }
            return
        }
        val stopsResult = status.checkin?.trip?.let { repo.getStopovers(it) }
        coroutineContext.ensureActive()
        if (currentStatusId != statusId || request != loadGeneration) return
        _uiState.update { state ->
            val stops = stopsResult?.getOrNull() ?: if (stopsResult != null)
                compatibleExistingStopovers(status, state) else emptyList()
            state.copy(
                isLoading = false,
                status = statusWithStopoverBoundaries(status, stops),
                stopovers = stops,
                lastUpdated = System.currentTimeMillis(),
                error = if (showLoading && stopsResult?.isFailure == true)
                    "Halte konnten nicht geladen werden: ${stopsResult.exceptionOrNull()?.message}" else state.error
            )
        }
        enrichSevStops(status, _uiState.value.stopovers)
        if (showLoading) checkIfOwnStatus(status)
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
        if (!_uiState.value.isOwnStatus || _uiState.value.isDeleting || _uiState.value.isUpdating) return
        val view = viewGeneration
        ++loadGeneration
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        _uiState.update { it.copy(isDeleting = true, isLoading = false) }
        mutationJob = viewModelScope.launch {
            val session = prefs.getAuthSession()
            coroutineContext.ensureActive()
            if (view != viewGeneration || currentStatusId != statusId) return@launch
            repo.deleteStatus(statusId)
                .onSuccess {
                    coroutineContext.ensureActive()
                    if (view != viewGeneration || currentStatusId != statusId) return@onSuccess
                    completeDeletedStatus(
                        cleanup = { prefs.clearActiveTracking(statusId, session) },
                        completion = finished@{ localFailure ->
                            if (view != viewGeneration || currentStatusId != statusId) return@finished
                            _uiState.update { it.copy(isDeleting = false) }
                            if (localFailure != null) {
                                Log.w("StatusDetailViewModel", "Server deletion completed; local tracking cleanup failed", localFailure)
                                val context = getApplication<Application>()
                                val live = TripTrackingService.trackingLiveState.value
                                if (live?.statusId == statusId && live.sessionRevision == session.revision) {
                                    runCatching {
                                        context.startService(Intent(context, TripTrackingService::class.java).apply {
                                            action = TripTrackingService.ACTION_STOP
                                            putExtra(TripTrackingService.EXTRA_STATUS_ID, statusId)
                                            putExtra(TripTrackingService.EXTRA_AUTH_SESSION_REVISION, session.revision)
                                        })
                                    }.onFailure { Log.w("StatusDetailViewModel", "Could not request tracking stop", it) }
                                }
                                runCatching {
                                    Toast.makeText(context,
                                        "Fahrt gelöscht; lokale Begleitung konnte nicht beendet werden. Bitte Begleitung stoppen.",
                                        Toast.LENGTH_LONG).show()
                                }.onFailure { Log.w("StatusDetailViewModel", "Could not show cleanup warning", it) }
                            }
                            onSuccess()
                        }
                    )
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (view != viewGeneration || currentStatusId != statusId) return@onFailure
                    _uiState.update { it.copy(isDeleting = false, error = "Löschen fehlgeschlagen: ${e.message}") }
                    startAutoRefresh(statusId)
                }
        }
    }

    // ─── Editing ──────────────────────────────────────────────────────────────

    fun startEditing() {
        if (!_uiState.value.isOwnStatus || _uiState.value.isUpdating || _uiState.value.isDeleting) return
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
                editInitialStatus = status,
                editArrivalManuallyChanged = false,
                editVisibility = status.visibility ?: 0
            )
        }
        editInitialDeparture = _uiState.value.editDeparture
        editInitialArrival = _uiState.value.editArrival
    }

    fun stopEditing() {
        if (_uiState.value.isUpdating) return
        _uiState.update { it.copy(isEditing = false) }
    }

    fun updateEditBody(body: String) {
        _uiState.update { it.copy(editBody = body) }
    }

    fun updateEditDeparture(time: String) {
        _uiState.update { it.copy(editDeparture = time) }
    }

    fun updateEditArrival(time: String) {
        _uiState.update { it.copy(editArrival = time, editArrivalManuallyChanged = time != editInitialArrival) }
    }

    fun updateEditDestination(stop: StopStation) {
        if (_uiState.value.isUpdating || stop.cancelled == true || stop.stationId == null) return
        val state = _uiState.value
        val origin = state.status?.checkin?.origin ?: return
        val originIndex = state.stopovers.indices.filter { state.stopovers[it].matchesStopover(origin) }.singleOrNull() ?: return
        if (state.stopovers.drop(originIndex + 1).none { it.matchesStopover(stop) }) return
        val hadManualEdit = state.editArrivalManuallyChanged ?: (state.editArrival != editInitialArrival)
        val originalCheckin = (state.editInitialStatus ?: state.status)?.checkin
        val manualArrival = originalCheckin?.manualArrival.takeIf {
            stop.matchesStopover(originalCheckin?.destination)
        }
        val newArrival = JourneyTimeResolver.arrival(stop, null, 0, manualArrival)?.millis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
        _uiState.update {
            it.copy(
                editDestinationId = stop.stationId,
                editDestinationStop = stop,
                editArrivalManuallyChanged = hadManualEdit,
                editArrival = if (hadManualEdit) it.editArrival else newArrival
            )
        }
        editInitialArrival = newArrival
    }

    fun updateEditVisibility(visibility: Int) {
        _uiState.update { it.copy(editVisibility = visibility) }
    }

    fun saveStatusEdit() {
        val statusId = currentStatusId ?: return
        val state = _uiState.value
        if (!state.isOwnStatus || !state.isEditing || state.isUpdating || state.isDeleting) return
        val destination = state.editDestinationStop
        val originalDestination = (state.editInitialStatus ?: state.status)?.checkin?.destination
        val destinationChanged = destination != originalDestination &&
            destination?.matchesStopover(originalDestination) != true
        if (destinationChanged && (destination?.stationId == null || destination?.arrivalPlanned.isNullOrBlank())) {
            _uiState.update {
                it.copy(error = "Für das neue Ziel fehlen eine gültige Stations-ID oder die geplante Ankunftszeit.")
            }
            return
        }

        val view = viewGeneration
        ++loadGeneration
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        _uiState.update { it.copy(isUpdating = true, isLoading = false, error = null) }
        mutationJob = viewModelScope.launch {
            val request = buildStatusEditRequest(state, editInitialDeparture, editInitialArrival)
            
            repo.updateStatus(statusId, request)
                .onSuccess { updatedStatus ->
                    coroutineContext.ensureActive()
                    if (view != viewGeneration || currentStatusId != statusId) return@onSuccess
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
                    if (e is CancellationException) throw e
                    if (view != viewGeneration || currentStatusId != statusId) return@onFailure
                    _uiState.update { 
                        it.copy(isUpdating = false, error = "Änderung fehlgeschlagen: ${e.message}") 
                    }
                    startAutoRefresh(statusId)
                }
        }
    }

    private fun checkIfOwnStatus(status: Status) {
        val view = viewGeneration
        viewModelScope.launch {
            repo.getCurrentUser().onSuccess { currentUser ->
                coroutineContext.ensureActive()
                _uiState.update {
                    if (view == viewGeneration && currentStatusId == status.id && it.status?.id == status.id) {
                        it.copy(isOwnStatus = status.user?.id != null && status.user.id == currentUser.id)
                    } else it
                }
            }
        }
    }

    fun reset(statusId: Int? = null) {
        if (statusId != null && currentStatusId != statusId) return
        ++viewGeneration
        ++loadGeneration
        loadJob?.cancel()
        mutationJob?.cancel()
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
