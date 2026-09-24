package de.traewelling.app.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.traewelling.app.data.transitous.TransitousBounds
import de.traewelling.app.data.transitous.TransitousLiveMapClient
import de.traewelling.app.data.transitous.TransitousVehicleMarker
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VehicleCategory(val label: String) {
    ALL("Alle"), RAIL("Züge"), BUS("Bus"), METRO("U-Bahn"), TRAM("Tram"), FERRY("Fähren"), OTHER("Weitere");

    companion object {
        fun forMode(mode: String): VehicleCategory {
            val name = mode.uppercase()
            return when {
                "BUS" in name || "COACH" in name -> BUS
                "SUBWAY" in name || "METRO" in name || "UNDERGROUND" in name -> METRO
                "TRAM" in name || "STREETCAR" in name -> TRAM
                "FERRY" in name || "BOAT" in name -> FERRY
                "RAIL" in name || "TRAIN" in name || "SUBURBAN" in name ||
                    "LONG_DISTANCE" in name || "HIGH_SPEED" in name ||
                    "REGIONAL" in name || "INTERCITY" in name -> RAIL
                else -> OTHER
            }
        }
    }
}

data class LiveMapUiState(
    val markers: List<TransitousVehicleMarker> = emptyList(),
    val category: VehicleCategory = VehicleCategory.ALL,
    val loading: Boolean = false,
    val zoomRequired: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,
)

/** Limits Transitous traffic to a visible viewport, with a debounce for camera gestures. */
class LiveMapViewModel : ViewModel() {
    private val client = TransitousLiveMapClient()
    private val _uiState = MutableStateFlow(LiveMapUiState())
    val uiState = _uiState.asStateFlow()

    private var viewport: TransitousBounds? = null
    private var zoom: Double = 0.0
    private var lastRequestedBounds: TransitousBounds? = null
    private var lastRequestedZoom: Double = 0.0
    private var lastRequestMs: Long = -MIN_REQUEST_INTERVAL_MS
    private var pendingRequest: Job? = null
    private var currentRequest: Job? = null
    private var active = false

    // Preserved when the pager disposes its native map while another tab is selected.
    var cameraLatitude: Double = 52.52
    var cameraLongitude: Double = 13.405
    var cameraZoom: Double = 10.5

    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            pendingRequest?.cancel()
            currentRequest?.cancel()
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun selectCategory(category: VehicleCategory) {
        _uiState.update { it.copy(category = category) }
    }

    fun onViewportChanged(bounds: TransitousBounds, zoom: Double) {
        if (!active) return
        viewport = bounds
        this.zoom = zoom
        pendingRequest?.cancel()
        if (!sameViewport(bounds, zoom, lastRequestedBounds, lastRequestedZoom)) {
            currentRequest?.cancel()
            _uiState.update { it.copy(loading = false) }
        }
        if (!bounds.isValid() || zoom < MIN_ZOOM) {
            currentRequest?.cancel()
            _uiState.update {
                it.copy(
                    markers = emptyList(), loading = false, zoomRequired = zoom < MIN_ZOOM,
                    error = if (zoom >= MIN_ZOOM) "Kartenausschnitt über dem 180. Längengrad derzeit nicht verfügbar" else null,
                )
            }
            return
        }
        _uiState.update { it.copy(zoomRequired = false) }
        pendingRequest = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            requestIfDue(force = false)
        }
    }

    /** Also called by the screen's minute timer while the map tab is visible. */
    fun refresh() {
        if (!active) return
        pendingRequest?.cancel()
        pendingRequest = viewModelScope.launch { requestIfDue(force = true) }
    }

    private suspend fun requestIfDue(force: Boolean) {
        if (!active) return
        val bounds = viewport ?: return
        val currentZoom = zoom
        if (!bounds.isValid() || currentZoom < MIN_ZOOM) return

        val sameArea = sameViewport(bounds, currentZoom, lastRequestedBounds, lastRequestedZoom)
        val elapsed = SystemClock.elapsedRealtime() - lastRequestMs
        if (!force && sameArea && (
                currentRequest?.isActive == true ||
                    (_uiState.value.lastUpdated != null && elapsed < AUTO_REFRESH_INTERVAL_MS)
                )) return
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            // A later pan may have replaced this request while it was waiting.
            if (!active || bounds != viewport || currentZoom != zoom) return
        }

        currentRequest?.cancel()
        lastRequestMs = SystemClock.elapsedRealtime()
        lastRequestedBounds = bounds
        lastRequestedZoom = currentZoom
        _uiState.update { it.copy(loading = true, error = null) }
        currentRequest = viewModelScope.launch {
            try {
                client.fetchMarkers(bounds, currentZoom).fold(
                    onSuccess = { vehicles ->
                        if (sameViewport(bounds, currentZoom, viewport, zoom)) {
                            _uiState.update { it.copy(markers = vehicles, lastUpdated = Instant.now(), loading = false) }
                        }
                    },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        _uiState.update {
                            it.copy(loading = false, error = "Transitous ist derzeit nicht erreichbar. Bitte erneut versuchen.")
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, error = "Transitous ist derzeit nicht erreichbar. Bitte erneut versuchen.") }
            }
        }
    }

    private fun sameViewport(a: TransitousBounds, aZoom: Double, b: TransitousBounds?, bZoom: Double): Boolean =
        b != null && kotlin.math.abs(aZoom - bZoom) < 0.05 &&
            kotlin.math.abs(a.south - b.south) < 0.001 &&
            kotlin.math.abs(a.west - b.west) < 0.001 &&
            kotlin.math.abs(a.north - b.north) < 0.001 &&
            kotlin.math.abs(a.east - b.east) < 0.001

    companion object {
        private const val MIN_ZOOM = 9.0
        private const val VIEWPORT_DEBOUNCE_MS = 900L
        private const val MIN_REQUEST_INTERVAL_MS = 10_000L
        private const val AUTO_REFRESH_INTERVAL_MS = 60_000L
    }
}
