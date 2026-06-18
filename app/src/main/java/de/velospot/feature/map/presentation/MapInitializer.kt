package de.velospot.feature.map.presentation

import android.view.Gravity
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.SavedPlace
import de.velospot.feature.map.presentation.markers.LAYER_PARKING
import de.velospot.feature.map.presentation.markers.LAYER_PARKING_CLUSTER
import de.velospot.feature.map.presentation.markers.LAYER_PARKING_HIGHLIGHT
import de.velospot.feature.map.presentation.markers.LAYER_SAVED_PIN
import de.velospot.feature.map.presentation.markers.PROP_SAVED_ID
import de.velospot.feature.map.presentation.markers.PROP_SPACE_ID
import de.velospot.feature.map.presentation.markers.SOURCE_PARKING
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

// ── Map style URLs ─────────────────────────────────────────────────────────────
// Free vector tile styles from OpenFreeMap – no API key required. The dark style
// is bundled in assets and reuses the very same OpenFreeMap vector tiles
// (OpenMapTiles schema) as the light style, so no extra tile provider is needed.
internal const val MAP_STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
internal const val MAP_STYLE_URL_DARK  = "asset://map_style_dark.json"

internal fun mapStyleUrl(isDarkTheme: Boolean): String =
    if (isDarkTheme) MAP_STYLE_URL_DARK else MAP_STYLE_URL_LIGHT

// ── Initial camera ─────────────────────────────────────────────────────────────
internal const val TRIER_LAT   = 49.7596
internal const val TRIER_LON   = 6.6441
internal const val DEFAULT_ZOOM = 14.0

/**
 * Performs the one-time imperative MapLibre setup (independent of the active
 * style): compass placement, initial camera, and the viewport / zoom / click
 * listeners. Style loading is handled separately in [MainMapScreen] so it can
 * react to dark-mode changes.
 *
 * @param currentSpaces       provider for the parking spots currently loaded (for click hit-testing)
 * @param currentSavedPlaces  provider for the saved places currently shown (for click hit-testing)
 * @param onZoomBucketChanged invoked with the rounded zoom level whenever the camera moves
 * @param onMapReady          invoked once the [MapLibreMap] is available
 */
internal fun MapView.initVeloSpotMap(
    viewModel: MapViewModel,
    currentSpaces: () -> List<BikeParkingSpace>,
    currentSavedPlaces: () -> List<SavedPlace>,
    onZoomBucketChanged: (Int) -> Unit,
    onMapReady: (MapLibreMap) -> Unit
) {
    getMapAsync { map ->
        // Compass bottom-left – clear of the search bar (top) and menu card (top-right).
        map.uiSettings.compassGravity = Gravity.BOTTOM or Gravity.START
        map.uiSettings.setCompassMargins(16, 0, 0, 120)

        // Initial camera position
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(TRIER_LAT, TRIER_LON))
                    .zoom(DEFAULT_ZOOM)
                    .build()
            )
        )

        // Viewport → load nearby spots when camera comes to rest
        map.addOnCameraIdleListener {
            val bounds = map.projection.visibleRegion.latLngBounds
            val sw = bounds.southWest
            val ne = bounds.northEast
            viewModel.onViewportChanged(
                BoundingBox(
                    minLat = sw.latitude,
                    minLon = sw.longitude,
                    maxLat = ne.latitude,
                    maxLon = ne.longitude
                )
            )
        }

        // Zoom bucket tracking (for icon size)
        map.addOnCameraMoveListener {
            onZoomBucketChanged(map.cameraPosition.zoom.roundToInt())
        }

        // Click → parking spot first, then cluster (zoom in), then saved place;
        // otherwise drop a custom pin.
        map.addOnMapClickListener { latLng ->
            val screenPoint = map.projection.toScreenLocation(latLng)

            val spaceId = map.queryRenderedFeatures(screenPoint, LAYER_PARKING_HIGHLIGHT, LAYER_PARKING)
                .firstOrNull()?.getStringProperty(PROP_SPACE_ID)
            val clicked = currentSpaces().find { it.id == spaceId }
            if (clicked != null) {
                viewModel.selectSpace(clicked)
                return@addOnMapClickListener true
            }

            // Tapped a cluster bubble → zoom in to its expansion level so it breaks apart.
            val cluster = map.queryRenderedFeatures(screenPoint, LAYER_PARKING_CLUSTER).firstOrNull()
            if (cluster != null) {
                val source = map.style?.getSource(SOURCE_PARKING) as? GeoJsonSource
                val center = cluster.geometry() as? Point
                if (source != null && center != null) {
                    val expansionZoom = source.getClusterExpansionZoom(cluster)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(center.latitude(), center.longitude()),
                            expansionZoom.toDouble()
                        )
                    )
                }
                return@addOnMapClickListener true
            }

            val savedId = map.queryRenderedFeatures(screenPoint, LAYER_SAVED_PIN)
                .firstOrNull()?.getStringProperty(PROP_SAVED_ID)
            val savedPlace = currentSavedPlaces().find { it.id == savedId }
            if (savedPlace != null) {
                viewModel.selectSavedPlace(savedPlace)
            } else {
                viewModel.onMapTapped(latLng.latitude, latLng.longitude)
            }
            true
        }

        onMapReady(map)
    }
}

