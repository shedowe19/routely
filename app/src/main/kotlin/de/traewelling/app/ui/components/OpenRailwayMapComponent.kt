package de.traewelling.app.ui.components

import android.content.Context
import android.graphics.Color
import android.preference.PreferenceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.traewelling.app.data.model.StopStation
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun OpenRailwayMapComponent(
    modifier: Modifier = Modifier,
    stopovers: List<StopStation>,
    stationCoordinates: Map<Int, Pair<Double, Double>>,
    currentLocation: GeoPoint? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize osmdroid configuration
    remember {
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = "Traewelling-App"
    }

    // Create MapView and attach lifecycle observers
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

            // Base map: Standard OSM
            setTileSource(TileSourceFactory.MAPNIK)

            // OpenRailwayMap overlay
            val ormSource = object : OnlineTileSourceBase(
                "OpenRailwayMap",
                0,
                19,
                256,
                ".png",
                arrayOf("https://a.tiles.openrailwaymap.org/standard/")
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
                }
            }

            val mapTileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(context)
            mapTileProvider.tileSource = ormSource
            val tilesOverlay = TilesOverlay(mapTileProvider, context)
            tilesOverlay.loadingBackgroundColor = Color.TRANSPARENT
            overlays.add(tilesOverlay)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bind data to MapView
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            // Clear previous overlays except the base tiles
            map.overlays.removeAll { it !is TilesOverlay }

            val geoPoints = mutableListOf<GeoPoint>()

            // Draw route and markers
            stopovers.forEach { stop ->
                val coords = stationCoordinates[stop.id]
                if (coords != null) {
                    val pt = GeoPoint(coords.first, coords.second)
                    geoPoints.add(pt)

                    val marker = Marker(map)
                    marker.position = pt
                    marker.title = stop.name
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // We can use a simple icon or keep default
                    map.overlays.add(marker)
                }
            }

            if (geoPoints.isNotEmpty()) {
                val polyline = Polyline()
                polyline.setPoints(geoPoints)
                polyline.color = Color.BLUE
                polyline.width = 5f
                map.overlays.add(0, polyline) // Add below markers

                // Adjust zoom to fit bounds if this is the first load
                if (map.zoomLevelDouble == 0.0) {
                    map.post {
                        val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
                        map.zoomToBoundingBox(boundingBox, true)
                        // Adjust zoom slightly out for padding
                        map.controller.zoomOut()
                    }
                }
            }

            // Draw current location marker if available
            if (currentLocation != null) {
                val locMarker = Marker(map)
                locMarker.position = currentLocation
                locMarker.title = "Dein Standort"
                locMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Use a different color or icon for current location
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
                if (drawable != null) {
                    drawable.setTint(Color.RED)
                    locMarker.icon = drawable
                }
                map.overlays.add(locMarker)
            }

            map.invalidate()
        }
    )
}
