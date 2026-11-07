package de.traewelling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE = "routely-journey-route"
private const val ROUTE_CASING_LAYER = "routely-journey-route-casing"
private const val ROUTE_LAYER = "routely-journey-route-line"
private const val STOP_SOURCE = "routely-journey-stops"
private const val STOP_LAYER = "routely-journey-stop-dots"
private const val ENDPOINT_SOURCE = "routely-journey-endpoints"
private const val ENDPOINT_LAYER = "routely-journey-endpoint-dots"
private const val VEHICLE_SOURCE = "routely-journey-vehicle"
private const val VEHICLE_HALO_LAYER = "routely-journey-vehicle-halo"
private const val VEHICLE_LAYER = "routely-journey-vehicle-dot"

/** Inline route preview; tapping it opens the interactive full-screen map. */
@Composable
internal fun JourneyMapCard(
    model: JourneyMapModel,
    lineName: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var fullScreen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    if (fullScreen) {
        JourneyMapDialog(
            model = model,
            lineName = lineName,
            accent = accent,
            onDismiss = { fullScreen = false },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Live-Karte",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        model.sourceLabel + " · " + routeLabel(model.routeKind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = { fullScreen = true }) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "Karte vergrößern",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(238.dp)
                    .semantics {
                        contentDescription = "Kartenvorschau. ${model.position?.label ?: "Position wird ermittelt"}. Tippen zum Vergrößern."
                    }
            ) {
                if (!fullScreen) {
                    JourneyMapCanvas(
                        model = model,
                        accent = accent,
                        modifier = Modifier.fillMaxSize(),
                        interactive = false,
                    )
                    Box(
                        Modifier.matchParentSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { fullScreen = true }
                    )
                } else {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer))
                }
                MapOverlays(model, lineName, accent, uriHandler)
            }
        }
    }
}

@Composable
private fun JourneyMapDialog(
    model: JourneyMapModel,
    lineName: String?,
    accent: Color,
    onDismiss: () -> Unit,
) {
    var recenterRequest by remember { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live-Karte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            model.sourceLabel + " · " + routeLabel(model.routeKind),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { recenterRequest++ }) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Route zentrieren")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Karte schließen")
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    JourneyMapCanvas(
                        model = model,
                        accent = accent,
                        modifier = Modifier.fillMaxSize(),
                        interactive = true,
                        recenterRequest = recenterRequest,
                    )
                    MapOverlays(model, lineName, accent, uriHandler)
                }
                Text(
                    "Position geschätzt · keine Fahrzeug- oder Geräte-GPS-Position",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun MapOverlays(
    model: JourneyMapModel,
    lineName: String?,
    accent: Color,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 2.dp,
        ) {
            Text(
                lineName ?: "Fahrt",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 2.dp,
            ) {
                Text(
                    model.position?.label ?: "Position wird ermittelt",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap",
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") }
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun JourneyMapCanvas(
    model: JourneyMapModel,
    accent: Color,
    modifier: Modifier,
    interactive: Boolean,
    recenterRequest: Int = 0,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val styleUrl = if (dark) DARK_STYLE else LIGHT_STYLE
    val accentHex = accent.toHex()
    val originHex = MaterialTheme.colorScheme.secondary.toHex()
    val destinationHex = MaterialTheme.colorScheme.tertiary.toHex()
    val stopHex = MaterialTheme.colorScheme.onSurface.toHex()
    val casingHex = if (dark) "#0B0B10" else "#FFFFFF"

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        fun start() {
            if (!started) {
                mapView.onStart()
                started = true
            }
        }
        fun resume() {
            start()
            if (!resumed) {
                mapView.onResume()
                resumed = true
            }
        }
        fun pause() {
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
        }
        fun stop() {
            pause()
            if (started) {
                mapView.onStop()
                started = false
            }
        }
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
            styleReady = false
        }
    }

    DisposableEffect(mapView, styleUrl, interactive) {
        var disposed = false
        mapView.getMapAsync { readyMap ->
            if (disposed) return@getMapAsync
            readyMap.uiSettings.apply {
                setScrollGesturesEnabled(interactive)
                setZoomGesturesEnabled(interactive)
                setRotateGesturesEnabled(interactive)
                setTiltGesturesEnabled(interactive)
                setDoubleTapGesturesEnabled(interactive)
                setQuickZoomGesturesEnabled(interactive)
                setCompassEnabled(interactive)
                setAttributionEnabled(false)
                setLogoEnabled(false)
            }
            readyMap.setStyle(styleUrl) { style ->
                if (disposed) return@setStyle
                addJourneyLayers(style, accentHex, stopHex, originHex, destinationHex, casingHex)
                map = readyMap
                styleReady = true
            }
        }
        onDispose {
            disposed = true
            map = null
            styleReady = false
        }
    }

    LaunchedEffect(map, styleReady, model.route, model.stops, accentHex, originHex, destinationHex) {
        if (!styleReady) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(model.route.map { Point.fromLngLat(it.longitude, it.latitude) })
                    )
                )
            )
        )
        style.getSourceAs<GeoJsonSource>(STOP_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                model.stops.filter { it.role == JourneyMapStopRole.STOP }.map { stop ->
                    Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude))
                }
            )
        )
        style.getSourceAs<GeoJsonSource>(ENDPOINT_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                model.stops.filter { it.role != JourneyMapStopRole.STOP }.map { stop ->
                    Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                        addStringProperty(
                            "color",
                            if (stop.role == JourneyMapStopRole.ORIGIN) originHex else destinationHex
                        )
                    }
                }
            )
        )
    }

    LaunchedEffect(map, styleReady, model.position) {
        if (!styleReady) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(
                    model.position?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
                )
            )
        )
    }

    LaunchedEffect(map, styleReady, model.route, recenterRequest) {
        if (!styleReady || model.route.isEmpty()) return@LaunchedEffect
        val readyMap = map ?: return@LaunchedEffect
        val bounds = LatLngBounds.Builder().apply {
            model.route.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        val camera = readyMap.getCameraForLatLngBounds(bounds, intArrayOf(56, 56, 56, 160)) ?: return@LaunchedEffect
        readyMap.cameraPosition = camera
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!styleReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun addJourneyLayers(
    style: Style,
    accentHex: String,
    stopHex: String,
    originHex: String,
    destinationHex: String,
    casingHex: String,
) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    style.addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            lineColor(casingHex),
            lineWidth(7f),
            lineOpacity(0.9f),
        )
    )
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            lineColor(accentHex),
            lineWidth(4f),
            lineOpacity(0.95f),
        )
    )

    style.addSource(GeoJsonSource(STOP_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    style.addLayer(
        CircleLayer(STOP_LAYER, STOP_SOURCE).withProperties(
            circleRadius(3.5f),
            circleColor(stopHex),
            circleOpacity(0.55f),
            circleStrokeColor(casingHex),
            circleStrokeWidth(1f),
        )
    )

    style.addSource(GeoJsonSource(ENDPOINT_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    style.addLayer(
        CircleLayer(ENDPOINT_LAYER, ENDPOINT_SOURCE).withProperties(
            circleRadius(6.5f),
            circleColor(get("color")),
            circleStrokeColor(casingHex),
            circleStrokeWidth(2f),
        )
    )

    style.addSource(GeoJsonSource(VEHICLE_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    style.addLayer(
        CircleLayer(VEHICLE_HALO_LAYER, VEHICLE_SOURCE).withProperties(
            circleRadius(15f),
            circleColor(accentHex),
            circleOpacity(0.18f),
        )
    )
    style.addLayer(
        CircleLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
            circleRadius(8f),
            circleColor(accentHex),
            circleStrokeColor(casingHex),
            circleStrokeWidth(3f),
        )
    )
}

private fun routeLabel(kind: JourneyMapRouteKind): String = when (kind) {
    JourneyMapRouteKind.DETAILED -> "Strecke aus Träwelling"
    JourneyMapRouteKind.PARTIAL -> "Strecke teilweise aus Träwelling"
    JourneyMapRouteKind.SCHEMATIC -> "Strecke schematisch"
}

private fun Color.toHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
