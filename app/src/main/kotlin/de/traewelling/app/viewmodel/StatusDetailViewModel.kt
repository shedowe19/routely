package de.traewelling.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusVisibility
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.repository.TraewellingRepository
import de.traewelling.app.data.repository.StatusWriteAcceptedException
import de.traewelling.app.data.routing.TransitRouteRepository
import de.traewelling.app.data.sev.SevJourneyEnricher
import de.traewelling.app.data.sev.SevStopResolver
import de.traewelling.app.service.JourneyTimeResolver
import de.traewelling.app.service.TrackingLiveState
import de.traewelling.app.service.TransitRouteSelection
import de.traewelling.app.service.TripTrackingService
import de.traewelling.app.service.activeTripStopKey
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.util.runStorageAction
import de.traewelling.app.util.isPreferenceStorageFailure
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.time.Instant

private const val ROUTE_RETRY_INTERVAL_MILLIS = 2 * 60_000L

data class StatusDetailUiState(
    val isLoading: Boolean = false,
    val status: Status? = null,
    val stopovers: List<StopStation> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = 0,
    val realtimeRefreshFailed: Boolean = false,
    val isDeleting: Boolean = false,
    val deletedStatusId: Int? = null,
    val isOwnStatus: Boolean = false,
    val trackingState: TrackingLiveState? = null,
    val sevStops: Map<String, SevStopInfo> = emptyMap(),
    val isLoadingSevStops: Boolean = false,
    val routeGeometry: TransitRouteGeometry? = null,
    val nowTimeNotice: String? = null,

    // Editing state
    val isEditing: Boolean = false,
    val isUpdating: Boolean = false,
    val editBody: String = "",
    val editDeparture: String = "",
    val editArrival: String = "",
    val editArrivalManuallyChanged: Boolean? = null,
    val editArrivalNowRequested: Boolean = false,
    val editDestinationId: Int? = null,
    val editDestinationStop: StopStation? = null,
    val editInitialStatus: Status? = null,
    val editVisibility: Int? = null,
    val editVisibilityManuallyChanged: Boolean = false
)

class StatusDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repo  = TraewellingRepository(application, prefs)

    private val presentation = StatusDetailPresentation()
    private val _uiState = presentation.state
    val uiState: StateFlow<StatusDetailUiState> = _uiState.asStateFlow()

    private var autoRefreshJob: Job? = null
    private val currentStatusId: Int? get() = presentation.statusId
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var loadGeneration = 0L
    private val viewGeneration: Long get() = presentation.generation
    private var editInitialDeparture = ""
    private var editInitialArrival = ""
    private var sevEnrichmentJob: Job? = null
    private var sevGeneration = 0L
    private var sevSnapshot: SevDetailSnapshot? = null
    private var statusSession: AuthSession? = null
    private var routeGeometryJob: Job? = null
    private var routeRepository: TransitRouteRepository? = null
    private var routeRepositorySession: AuthSession? = null
    private var routeRequest: TransitRouteRequest? = null
    private var routeAttemptedAtMillis = 0L

    private fun launchStorageAction(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        runStorageAction(onFailure = { failure ->
            _uiState.update { it.copy(isLoading = false, isUpdating = false, isDeleting = false,
                isLoadingSevStops = false, error = failure.message) }
        }) { block(this) }
    }

    init {
        launchStorageAction {
            prefs.storageState.filter { it.isReady }.map { it.revision }.distinctUntilChanged().drop(1).collect {
                if (presentation.isObserved && !_uiState.value.isEditing && !_uiState.value.isUpdating &&
                    !_uiState.value.isDeleting && _uiState.value.deletedStatusId == null) refresh()
            }
        }
        launchStorageAction {
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

    /** A screen owns only its observation lease, never the retained draft or submitted write. */
    fun observeStatusDetail(statusId: Int): StatusDetailAttachment {
        val attachment = presentation.attach(statusId)
        if (attachment.changedStatus) {
            mutationJob?.cancel()
            statusSession = null
            cancelSevEnrichment()
            clearRouteGeometry()
        }
        loadStatusDetail(statusId)
        return attachment
    }

    fun stopObservingStatusDetail(attachment: StatusDetailAttachment) {
        if (!presentation.detach(attachment)) return
        ++loadGeneration
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        cancelSevEnrichment()
        clearRouteGeometry()
        _uiState.update { it.copy(isLoading = false, isLoadingSevStops = false) }
    }

    private fun loadStatusDetail(statusId: Int) {
        if (currentStatusId != statusId || !presentation.isObserved ||
            _uiState.value.isUpdating || _uiState.value.isDeleting || _uiState.value.deletedStatusId != null) return
        autoRefreshJob?.cancel()
        loadJob?.cancel()
        val request = ++loadGeneration
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = launchStorageAction {
            loadSnapshot(statusId, request, showLoading = true)
            if (currentStatusId == statusId && request == loadGeneration) startAutoRefresh(statusId)
        }
    }

    private fun startAutoRefresh(statusId: Int) {
        autoRefreshJob?.cancel()
        if (!presentation.isObserved || currentStatusId != statusId) return
        autoRefreshJob = launchStorageAction {
            while (isActive) {
                delay(30_000)
                if (currentStatusId != statusId || loadJob?.isActive == true ||
                    _uiState.value.isUpdating || _uiState.value.isDeleting) continue
                loadSnapshot(statusId, ++loadGeneration, showLoading = false)
            }
        }
    }

    private suspend fun loadSnapshot(statusId: Int, request: Long, showLoading: Boolean) {
        val session = prefs.getAuthSession()
        suspend fun ensureCurrentRead() {
            coroutineContext.ensureActive()
            val currentSession = prefs.getAuthSession()
            coroutineContext.ensureActive()
            if (currentStatusId != statusId || request != loadGeneration ||
                currentSession != session) throw CancellationException("Detailabruf ist nicht mehr aktuell")
        }
        var currentCheckin: CheckinInfo? = null
        val result = readConsistentStatusDetail(
            readStatus = {
                ensureCurrentRead()
                repo.getStatusDetailSnapshot(statusId).also {
                    ensureCurrentRead()
                    currentCheckin = it.getOrNull()?.status?.checkin
                }
            },
            readStopovers = { tripId ->
                val stops = repo.getStopovers(tripId)
                ensureCurrentRead()
                val checkin = currentCheckin
                if (stops.isSuccess && checkin != null) {
                    val enriched = repo.enrichStopovers(stops.getOrThrow(), checkin)
                    ensureCurrentRead()
                    Result.success(enriched)
                } else stops
            },
            isCurrentRevision = { revision ->
                val current = repo.getStatusMutationSnapshot(statusId, session).revision == revision
                ensureCurrentRead()
                current
            }
        )
        ensureCurrentRead()
        val snapshot = result.getOrElse { error ->
            _uiState.update {
                it.copy(isLoading = false, realtimeRefreshFailed = true,
                    error = "Aktualisierung fehlgeschlagen: ${error.message}")
            }
            return
        }
        val status = snapshot.status
        val stopsResult = snapshot.stopovers
        coroutineContext.ensureActive()
        if (currentStatusId != statusId || request != loadGeneration) return
        statusSession = session
        _uiState.update { state ->
            val stops = stopsResult?.getOrNull() ?: if (stopsResult != null)
                compatibleExistingStopovers(status, state) else emptyList()
            state.copy(
                isLoading = false,
                status = statusWithStopoverBoundaries(status, stops),
                stopovers = stops,
                lastUpdated = when {
                    stopsResult?.isSuccess == true -> System.currentTimeMillis()
                    stops.isEmpty() -> 0
                    else -> state.lastUpdated
                },
                realtimeRefreshFailed = stopsResult?.isFailure == true,
                error = if (stopsResult?.isFailure == true)
                    "Halte konnten nicht aktualisiert werden: ${stopsResult.exceptionOrNull()?.message}"
                    else if (state.realtimeRefreshFailed) null else state.error
            )
        }
        enrichSevStops(status, _uiState.value.stopovers)
        loadRouteGeometry(statusId, _uiState.value.status, session, _uiState.value.stopovers)
        if (showLoading) checkIfOwnStatus(status)
    }

    /**
     * Loads the server-provided route geometry after the timetable is visible.
     * A failure only leaves the schematic stop-to-stop line in the map.
     */
    private fun loadRouteGeometry(
        statusId: Int,
        status: Status?,
        session: AuthSession,
        stops: List<StopStation>
    ) {
        val checkin = status?.checkin
        if (checkin == null || stops.isEmpty()) {
            clearRouteGeometry()
            return
        }
        val originIndex = stops.indexOfFirst { it.matchesStopover(checkin.origin) }
        val destinationIndex = stops.indexOfFirst { it.matchesStopover(checkin.destination) }
        if (originIndex < 0 || destinationIndex <= originIndex) {
            clearRouteGeometry()
            return
        }
        val checkedStops = stops.subList(originIndex, destinationIndex + 1)
        val keys = checkedStops.mapIndexed { index, stop ->
            activeTripStopKey(stop, originIndex + index)
        }
        val request = TransitRouteSelection.request(statusId, checkin, stops, checkedStops, keys)
        if (request == null) {
            clearRouteGeometry()
            return
        }
        val retryDue = System.currentTimeMillis() - routeAttemptedAtMillis >= ROUTE_RETRY_INTERVAL_MILLIS
        if (request == routeRequest && (_uiState.value.routeGeometry != null || !retryDue)) return
        routeRequest = request
        routeAttemptedAtMillis = System.currentTimeMillis()
        routeGeometryJob?.cancel()
        _uiState.update { if (it.routeGeometry == null) it else it.copy(routeGeometry = null) }
        routeGeometryJob = viewModelScope.launch {
            val repository = transitRouteRepository(session) ?: return@launch
            val geometry = try {
                repository.getRoute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (currentStatusId != statusId || statusSession != session || routeRequest != request) return@launch
            _uiState.update { it.copy(routeGeometry = geometry) }
        }
    }

    private fun transitRouteRepository(session: AuthSession): TransitRouteRepository? {
        if (routeRepositorySession != session) {
            routeRepository?.close()
            routeRepository = runCatching { TransitRouteRepository(session) }.getOrNull()
            routeRepositorySession = session
        }
        return routeRepository
    }

    private fun clearRouteGeometry() {
        routeGeometryJob?.cancel()
        routeGeometryJob = null
        routeRequest = null
        routeAttemptedAtMillis = 0L
        routeRepository?.close()
        routeRepository = null
        routeRepositorySession = null
        if (_uiState.value.routeGeometry != null) {
            _uiState.update { it.copy(routeGeometry = null) }
        }
    }

    /** Public map requests run after the API snapshot is visible and never block its refresh. */
    private fun enrichSevStops(status: Status, stops: List<StopStation>) {
        if (currentStatusId != status.id || !presentation.isObserved) return
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
        sevEnrichmentJob = launchStorageAction {
            val session = prefs.getAuthSession()
            val resolved = try {
                SevJourneyEnricher.enrich(checkin, stops)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            val currentSession = prefs.getAuthSession()
            coroutineContext.ensureActive()
            _uiState.update { state ->
                if (currentSession == session && currentStatusId == status.id && generation == sevGeneration &&
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

    fun setEditTimeNow(event: NowTimeEvent) {
        _uiState.update { it.withEditTimeNow(event, Instant.now()) }
    }

    fun deleteStatus() {
        val statusId = currentStatusId ?: return
        if (!_uiState.value.isOwnStatus || _uiState.value.isDeleting || _uiState.value.isUpdating) return
        val view = viewGeneration
        ++loadGeneration
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        _uiState.update { it.copy(isDeleting = true, isLoading = false) }
        mutationJob = launchStorageAction {
            val session = prefs.getAuthSession()
            coroutineContext.ensureActive()
            if (view != viewGeneration || currentStatusId != statusId) return@launchStorageAction
            repo.deleteStatus(statusId, expectedSession = session, onCommitted = { committedSession ->
                completeDeletedStatus(
                    cleanup = { prefs.clearActiveTracking(statusId, committedSession) },
                    completion = { localFailure ->
                        if (localFailure != null) {
                            if (isPreferenceStorageFailure(localFailure)) {
                                Log.w("StatusDetailViewModel", "Server deletion completed; local storage is unavailable")
                            } else {
                                Log.w("StatusDetailViewModel", "Server deletion completed; local tracking cleanup failed", localFailure)
                            }
                            val context = getApplication<Application>()
                            val live = TripTrackingService.trackingLiveState.value
                            val matchingTracking = live?.statusId == statusId && live.sessionRevision == committedSession.revision
                            if (matchingTracking && !isPreferenceStorageFailure(localFailure)) {
                                runCatching {
                                    context.startService(Intent(context, TripTrackingService::class.java).apply {
                                        action = TripTrackingService.ACTION_STOP
                                        putExtra(TripTrackingService.EXTRA_STATUS_ID, statusId)
                                        putExtra(TripTrackingService.EXTRA_AUTH_SESSION_REVISION, committedSession.revision)
                                    })
                                }.onFailure { Log.w("StatusDetailViewModel", "Could not request tracking stop", it) }
                            }
                            if (matchingTracking || view == viewGeneration && currentStatusId == statusId) {
                                runCatching {
                                    Toast.makeText(context,
                                        "Fahrt gelöscht; lokale Begleitung konnte nicht beendet werden. Bitte Begleitung stoppen.",
                                        Toast.LENGTH_LONG).show()
                                }.onFailure { Log.w("StatusDetailViewModel", "Could not show cleanup warning", it) }
                            }
                        }
                    }
                )
            })
                .onSuccess {
                    coroutineContext.ensureActive()
                    if (view != viewGeneration || currentStatusId != statusId) return@onSuccess
                    _uiState.update { it.copy(isDeleting = false, deletedStatusId = statusId) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (view != viewGeneration || currentStatusId != statusId) return@onFailure
                    _uiState.update {
                        if (e is StatusWriteAcceptedException && e.deleted) it.copy(isDeleting = false, deletedStatusId = statusId)
                        else it.copy(isDeleting = false, error = "Löschen fehlgeschlagen: ${e.message}")
                    }
                    if (e !is StatusWriteAcceptedException || !e.deleted) startAutoRefresh(statusId)
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
                editArrivalNowRequested = false,
                editVisibility = status.visibility,
                editVisibilityManuallyChanged = false
            )
        }
        editInitialDeparture = _uiState.value.editDeparture
        editInitialArrival = _uiState.value.editArrival
    }

    fun stopEditing() {
        if (_uiState.value.isUpdating) return
        _uiState.update { it.copy(isEditing = false, editArrivalNowRequested = false) }
    }

    fun updateEditBody(body: String) {
        _uiState.update { it.copy(editBody = body) }
    }

    fun updateEditDeparture(time: String) {
        _uiState.update { it.copy(editDeparture = time) }
    }

    fun updateEditArrival(time: String) {
        _uiState.update { it.copy(editArrival = time, editArrivalManuallyChanged = time != editInitialArrival, editArrivalNowRequested = false) }
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
        val state = _uiState.value
        if (!state.isEditing || !state.isOwnStatus || state.isUpdating || state.isEditVisibilityLocked ||
            StatusVisibility.fromApiValue(visibility) == null) return
        _uiState.update { it.copy(editVisibility = visibility, editVisibilityManuallyChanged = true) }
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

        val request = try {
            buildStatusEditRequest(state, editInitialDeparture, editInitialArrival).also {
                validateStatusEditTimes(state, it)
            }
        } catch (invalid: IllegalArgumentException) {
            _uiState.update { it.copy(error = invalid.message) }
            return
        }
        val session = statusSession ?: return
        val completeArrival = state.editArrivalNowRequested && request.arrival?.let {
            runCatching { !Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false)
        } == true
        val view = viewGeneration
        ++loadGeneration
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        _uiState.update { it.copy(isUpdating = true, isLoading = false, error = null) }
        mutationJob = launchStorageAction {
            var completionWarning: String? = null
            repo.updateStatus(statusId, request, expectedSession = session, onCommitted = { owner ->
                if (completeArrival) completionWarning = finishAcceptedArrival {
                    prefs.clearActiveTracking(statusId, owner)
                }
            })
                .onSuccess { updatedStatus ->
                    coroutineContext.ensureActive()
                    if (view != viewGeneration || currentStatusId != statusId) return@onSuccess
                    _uiState.update { 
                        val stops = compatibleExistingStopovers(updatedStatus, it)
                        it.copy(
                            isUpdating = false, 
                            isEditing = false,
                            editArrivalNowRequested = false,
                            nowTimeNotice = completionWarning ?: "Änderungen gespeichert.",
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
                        if (e is StatusWriteAcceptedException) {
                            val accepted = e.status
                            val stops = accepted?.let { value -> compatibleExistingStopovers(value, it) } ?: it.stopovers
                            it.copy(isUpdating = false, isEditing = false, editArrivalNowRequested = false,
                                status = accepted?.let { value -> statusWithStopoverBoundaries(value, stops) } ?: it.status,
                                stopovers = stops, nowTimeNotice = e.message, error = null)
                        } else it.copy(isUpdating = false, error = "Änderung fehlgeschlagen: ${e.message}")
                    }
                    if (e is StatusWriteAcceptedException && prefs.storageState.value.isReady) refresh()
                    else startAutoRefresh(statusId)
                }
        }
    }

    private fun checkIfOwnStatus(status: Status) {
        val view = viewGeneration
        launchStorageAction {
            val session = prefs.getAuthSession()
            repo.getCurrentUser().onSuccess { currentUser ->
                coroutineContext.ensureActive()
                val currentSession = prefs.getAuthSession()
                coroutineContext.ensureActive()
                _uiState.update {
                    if (currentSession == session && view == viewGeneration &&
                        currentStatusId == status.id && it.status?.id == status.id) {
                        it.copy(isOwnStatus = status.user?.id != null && status.user.id == currentUser.id)
                    } else it
                }
            }
        }
    }

    fun reset(statusId: Int? = null): Boolean {
        if (!presentation.leave(statusId)) return false
        ++loadGeneration
        loadJob?.cancel()
        mutationJob?.cancel()
        statusSession = null
        autoRefreshJob?.cancel()
        cancelSevEnrichment()
        return true
    }

    /** Consume deletion with the current navigation owner, including after Activity recreation. */
    fun consumeDeletedStatus(statusId: Int): Boolean {
        if (currentStatusId != statusId || _uiState.value.deletedStatusId != statusId) return false
        return reset(statusId)
    }

    override fun onCleared() {
        presentation.clear()
        ++loadGeneration
        super.onCleared()
        autoRefreshJob?.cancel()
        cancelSevEnrichment()
        clearRouteGeometry()
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
