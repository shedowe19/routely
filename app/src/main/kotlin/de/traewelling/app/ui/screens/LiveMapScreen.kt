package de.traewelling.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.traewelling.app.data.transitous.TransitousBounds
import de.traewelling.app.data.transitous.TransitousVehicleMarker
import de.traewelling.app.viewmodel.LiveMapViewModel
import de.traewelling.app.viewmodel.VehicleCategory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val VEHICLE_SOURCE = "routely-transitous-vehicles"
private const val VEHICLE_LAYER = "routely-transitous-vehicle-dots"
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Native map. Marker positions are inferred from timetables and route geometry, not GPS fixes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMapScreen(viewModel: LiveMapViewModel, isActive: Boolean) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val visibleMarkers = remember(state.markers, state.category) {
        state.markers.filter { state.category == VehicleCategory.ALL || VehicleCategory.forMode(it.mode) == state.category }
    }
    val currentMarkers by rememberUpdatedState(visibleMarkers)
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    val selected = visibleMarkers.firstOrNull { it.tripId == selectedTripId }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }

    val centerOnLocation: () -> Unit = {
        val currentMap = map
        if (currentMap != null) {
            try {
                val locations = LocationServices.getFusedLocationProviderClient(context)
                locations.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { position ->
                        if (position != null && map === currentMap && isActive && resumed) {
                            currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), 12.0))
                            locationError = null
                        } else if (map === currentMap && isActive && resumed) {
                            locationError = "Standort derzeit nicht verfügbar"
                        }
                    }
                    .addOnFailureListener { locationError = "Standort derzeit nicht verfügbar" }
            } catch (_: SecurityException) {
                locationError = "Standortfreigabe erforderlich"
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) centerOnLocation() else locationError = "Standortfreigabe abgelehnt"
    }

    // The pager can retain adjacent tabs. Only poll while this tab is actually selected.
    LaunchedEffect(isActive, resumed) {
        viewModel.setActive(isActive && resumed)
        if (isActive && resumed) {
            viewModel.refresh()
            while (true) {
                delay(60_000)
                viewModel.refresh()
            }
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setActive(false) }
    }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Live-Karte") },
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Fahrzeuge aktualisieren")
                }
                IconButton(onClick = {
                    val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) centerOnLocation() else permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Zum eigenen Standort")
                }
            }
        )

        Box(Modifier.fillMaxSize()) {
            if (isActive) {
                val mapView = remember(context) {
                    MapLibre.getInstance(context)
                    MapView(context).apply { onCreate(null) }
                }
                DisposableEffect(mapView, lifecycle) {
                    var started = false
                    var resumed = false
                    fun start() { if (!started) { mapView.onStart(); started = true } }
                    fun resume() { start(); if (!resumed) { mapView.onResume(); resumed = true } }
                    fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
                    fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> start()
                            Lifecycle.Event.ON_RESUME -> resume()
                            Lifecycle.Event.ON_PAUSE -> pause()
                            Lifecycle.Event.ON_STOP -> stop()
                            Lifecycle.Event.ON_DESTROY -> stop()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    when {
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> resume()
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> start()
                    }
                    onDispose {
                        lifecycle.removeObserver(observer)
                        stop()
                        mapView.onDestroy()
                        map = null
                    }
                }
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

                DisposableEffect(mapView) {
                    var mapInstance: MapLibreMap? = null
                    var idleListener: MapLibreMap.OnCameraIdleListener? = null
                    var clickListener: MapLibreMap.OnMapClickListener? = null
                    var disposed = false
                    mapView.getMapAsync { readyMap ->
                        if (!disposed) {
                            mapInstance = readyMap
                            readyMap.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(viewModel.cameraLatitude, viewModel.cameraLongitude))
                                .zoom(viewModel.cameraZoom)
                                .build()
                            idleListener = MapLibreMap.OnCameraIdleListener {
                                val camera = readyMap.cameraPosition
                                viewModel.cameraLatitude = camera.target?.latitude ?: viewModel.cameraLatitude
                                viewModel.cameraLongitude = camera.target?.longitude ?: viewModel.cameraLongitude
                                viewModel.cameraZoom = camera.zoom
                                val region = readyMap.projection.visibleRegion.latLngBounds
                                viewModel.onViewportChanged(
                                    TransitousBounds(region.latitudeSouth, region.longitudeWest, region.latitudeNorth, region.longitudeEast),
                                    camera.zoom,
                                )
                            }.also(readyMap::addOnCameraIdleListener)
                            clickListener = MapLibreMap.OnMapClickListener { point ->
                                val found = readyMap.queryRenderedFeatures(readyMap.projection.toScreenLocation(point), VEHICLE_LAYER)
                                    .firstOrNull()?.getStringProperty("tripId")
                                selectedTripId = currentMarkers.firstOrNull { it.tripId == found }?.tripId
                                found != null
                            }.also(readyMap::addOnMapClickListener)
                            readyMap.setStyle(MAP_STYLE_URL) { style ->
                                if (!disposed) {
                                    style.addSource(GeoJsonSource(VEHICLE_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
                                    style.addLayer(
                                        CircleLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
                                            circleRadius(8f),
                                            circleColor(get("color")),
                                            circleStrokeColor(android.graphics.Color.WHITE),
                                            circleStrokeWidth(2f),
                                            circleOpacity(0.95f),
                                        )
                                    )
                                    map = readyMap
                                    idleListener?.onCameraIdle()
                                }
                            }
                        }
                    }
                    onDispose {
                        disposed = true
                        idleListener?.let { mapInstance?.removeOnCameraIdleListener(it) }
                        clickListener?.let { mapInstance?.removeOnMapClickListener(it) }
                    }
                }

                LaunchedEffect(map, visibleMarkers) {
                    val features = visibleMarkers.map { vehicle ->
                        Feature.fromGeometry(Point.fromLngLat(vehicle.position.longitude, vehicle.position.latitude)).apply {
                            addStringProperty("tripId", vehicle.tripId)
                            addStringProperty("color", vehicleColor(vehicle))
                        }
                    }
                    map?.style?.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE)
                        ?.setGeoJson(FeatureCollection.fromFeatures(features))
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VehicleCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { selectedTripId = null; viewModel.selectCategory(category) },
                        label = { Text(category.label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.zoomRequired || state.error != null || locationError != null) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
                        Text(
                            state.error ?: locationError ?: "Für Fahrzeuge näher heranzoomen",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (selected != null) {
                            Text("${selected.displayName} · ${VehicleCategory.forMode(selected.mode).label}", style = MaterialTheme.typography.titleMedium)
                            Text("${selected.fromName} → ${selected.toName}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Geschätzte Position · ${if (selected.realTime) "mit Echtzeitzeiten" else "nach Fahrplan"}" +
                                    (selected.delayMinutes?.let { " · ${if (it > 0) "+" else ""}$it Min." } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                if (state.loading && state.lastUpdated == null) "Fahrzeuge werden geladen"
                                else "${visibleMarkers.size} Fahrzeuge nahe der Kartenmitte",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("Geschätzte Positionen · ${state.lastUpdated?.let { "Stand ${timeFormat.format(it)}" } ?: "Karte wird geladen"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { uriHandler.openUri("https://transitous.org/sources/") }, contentPadding = PaddingValues(0.dp)) {
                                Text("Transitous Quellen", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { uriHandler.openUri("https://openfreemap.org/") }, contentPadding = PaddingValues(0.dp)) {
                                Text("OpenFreeMap", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }, contentPadding = PaddingValues(0.dp)) {
                            Text("© OpenMapTiles · © OpenStreetMap-Mitwirkende", style = MaterialTheme.typography.labelSmall)
                            }
                    }
                }
            }
        }
    }
}

private fun vehicleColor(vehicle: TransitousVehicleMarker): String {
    val hex = vehicle.routeColor?.let { if (it.startsWith('#')) it else "#$it" }
    if (hex != null && (hex.length == 7 || hex.length == 9) && hex.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return hex
    }
    return when (VehicleCategory.forMode(vehicle.mode)) {
        VehicleCategory.RAIL -> "#2956B2"
        VehicleCategory.BUS -> "#1E8A64"
        VehicleCategory.METRO -> "#8051BE"
        VehicleCategory.TRAM -> "#D27227"
        VehicleCategory.FERRY -> "#2889AB"
        else -> "#667085"
    }
}
