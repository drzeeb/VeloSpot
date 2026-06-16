package de.velospot.feature.map.presentation.markers

import android.content.Context
import android.graphics.drawable.Drawable
import de.velospot.core.map.LayerVisibility
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.model.AddressSearchResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Orchestrates the MapLibre marker layer: maps the current app state into GeoJSON
 * sources and ensures the matching layers/images exist. Icon drawing lives in
 * `MarkerIconFactory.kt`, the source/layer/ID plumbing in `MapStyleLayers.kt`.
 *
 * Click handling is NOT managed here – a single [MapLibreMap.addOnMapClickListener]
 * in [MainMapScreen] queries [LAYER_PARKING] / [LAYER_SAVED_PIN] for hit-testing.
 */

// ── Public data classes ───────────────────────────────────────────────────────

internal data class MarkerIconSet(
    val normal: Drawable,
    val favorite: Drawable,
    val selected: Drawable,
    val activeNavigation: Drawable,
    val mutedNormal: Drawable,
    val mutedFavorite: Drawable,
    val mutedSelected: Drawable,
    val location: Drawable
)

internal data class MarkerRenderState(
    val favoriteIds: List<String>,
    val selectedSpaceId: String?,
    val activeNavigationSpaceId: String?,
    val userLocation: GeoCoordinate?
)

internal data class MarkerRenderLabels(
    val myLocationTitle: String,
    val snippetSpacesFormat: String
)

internal data class MarkerDisplayConfig(
    val context: Context,
    val labels: MarkerRenderLabels
)

internal data class RouteRenderData(
    val color: Int,
    val points: List<RoutePoint>
)

// ── Main update function ──────────────────────────────────────────────────────

/**
 * Syncs MapLibre GeoJSON sources / layers with the current app state.
 */
internal fun updateMarkers(
    map: MapLibreMap,
    spaces: List<BikeParkingSpace>,
    icons: MarkerIconSet,
    state: MarkerRenderState,
    display: MarkerDisplayConfig,
    route: RouteRenderData,
    searchPin: AddressSearchResult? = null,
    customMapPin: GeoCoordinate? = null,
    savedPlaces: List<SavedPlace> = emptyList(),
    layerVisibility: LayerVisibility = LayerVisibility(),
    /**
     * When `true` the live location puck is owned by `NavigationManager`
     * (it animates the rotating heading arrow every frame), so this renderer
     * must not write [SOURCE_LOCATION] to avoid fighting / flicker.
     */
    suppressLocationDot: Boolean = false,
    /**
     * When `true` the route polyline is owned by `NavigationManager`, which
     * renders the split travelled/remaining geometry. This renderer then leaves
     * [SOURCE_ROUTE] / [LAYER_ROUTE] untouched.
     */
    suppressRoute: Boolean = false
) {
    val style = map.style ?: return

    registerIcons(style, icons)

    // Route polyline — skipped while NavigationManager renders the travelled /
    // remaining split.
    if (!suppressRoute) {
        val routeGeoJson = if (route.points.size > 1) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(
                    LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })
                )
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        upsertSource(style, SOURCE_ROUTE, routeGeoJson)
        ensureRouteLayer(style, route.color)
    }

    // Parking markers
    upsertSource(style, SOURCE_PARKING, FeatureCollection.fromFeatures(buildParkingFeatures(spaces, state, layerVisibility)))
    ensureParkingLayer(style)

    // Location dot — skipped while NavigationManager animates the heading arrow.
    if (!suppressLocationDot) {
        val locFeature = state.userLocation?.let { loc ->
            Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude)).also {
                it.addStringProperty(PROP_ICON, IMG_LOCATION)
            }
        }
        upsertSource(
            style, SOURCE_LOCATION,
            if (locFeature != null) FeatureCollection.fromFeature(locFeature)
            else FeatureCollection.fromFeatures(emptyList())
        )
        ensureLocationLayer(style)
    }

    // Search pin (address result)
    val searchPinGeoJson = if (searchPin != null) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(searchPin.longitude, searchPin.latitude))
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    if (style.getImage(IMG_SEARCH_PIN) == null) {
        style.addImage(IMG_SEARCH_PIN, drawableToBitmap(createSearchPinIcon()))
    }
    upsertSource(style, SOURCE_SEARCH_PIN, searchPinGeoJson)
    ensureSearchPinLayer(style)

    // Custom map pin (tapped by user on empty map area)
    val customPinGeoJson = if (customMapPin != null) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(customMapPin.longitude, customMapPin.latitude))
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    if (style.getImage(IMG_CUSTOM_PIN) == null) {
        style.addImage(IMG_CUSTOM_PIN, drawableToBitmap(createCustomPinIcon()))
    }
    upsertSource(style, SOURCE_CUSTOM_PIN, customPinGeoJson)
    ensureCustomPinLayer(style)

    // Saved places (custom pins saved as named favourites) — persistent markers
    val savedGeoJson = if (layerVisibility.showSavedPlaces) {
        FeatureCollection.fromFeatures(
            savedPlaces.map { place ->
                Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).also {
                    it.addStringProperty(PROP_SAVED_ID, place.id)
                }
            }
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    if (style.getImage(IMG_SAVED_PIN) == null) {
        style.addImage(IMG_SAVED_PIN, drawableToBitmap(createSavedPlaceIcon()))
    }
    upsertSource(style, SOURCE_SAVED_PIN, savedGeoJson)
    ensureSavedPinLayer(style)
}


// ── Feature building ──────────────────────────────────────────────────────────

private fun buildParkingFeatures(
    spaces: List<BikeParkingSpace>,
    state: MarkerRenderState,
    layerVisibility: LayerVisibility
): List<Feature> {
    val highlightedId = state.activeNavigationSpaceId ?: state.selectedSpaceId
    // Filter by layer visibility. The selected spot and the active navigation
    // destination are always kept visible so they don't vanish from under the user.
    val visibleSpaces = spaces.filter { space ->
        val isFavorite  = state.favoriteIds.contains(space.id)
        val alwaysShow  = space.id == state.activeNavigationSpaceId || space.id == state.selectedSpaceId
        val categoryShow = if (isFavorite) layerVisibility.showFavorites else layerVisibility.showParking
        alwaysShow || categoryShow
    }
    val (others, highlighted) = visibleSpaces.partition { it.id != highlightedId }
    return (others + highlighted).map { space ->
        Feature.fromGeometry(Point.fromLngLat(space.longitude, space.latitude)).also {
            it.addStringProperty(PROP_SPACE_ID, space.id)
            it.addStringProperty(PROP_ICON, resolveIconKey(space, state))
        }
    }
}

private fun resolveIconKey(space: BikeParkingSpace, state: MarkerRenderState): String {
    val isNavDest  = space.id == state.activeNavigationSpaceId
    val showMuted  = state.activeNavigationSpaceId != null && !isNavDest
    val isFavorite = state.favoriteIds.contains(space.id)
    val isSelected = space.id == state.selectedSpaceId
    return when {
        isNavDest  -> IMG_SELECTED
        showMuted && isSelected  -> IMG_MUTED_SELECTED
        showMuted && isFavorite  -> IMG_MUTED_FAVORITE
        showMuted                -> IMG_MUTED_NORMAL
        isSelected -> IMG_SELECTED
        isFavorite -> IMG_FAVORITE
        else       -> IMG_NORMAL
    }
}


