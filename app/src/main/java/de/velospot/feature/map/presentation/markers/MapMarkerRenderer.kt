package de.velospot.feature.map.presentation.markers

import android.content.Context
import android.graphics.drawable.Drawable
import de.velospot.core.map.LayerVisibility
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.model.AddressSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
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
 * in `MainMapScreen` queries [LAYER_PARKING] / [LAYER_SAVED_PIN] for hit-testing.
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

/** Colours used for the native parking cluster bubbles and their count labels. */
internal data class ClusterRenderStyle(
    val circleColor: Int,
    val textColor: Int
)

// ── Render cache (diff-gate) ──────────────────────────────────────────────────

/**
 * Retained per-map holder that lets [updateMarkers] skip re-serialising a GeoJSON
 * source whose feature-determining inputs didn't change since the last render.
 *
 * [updateMarkers] fires on many unrelated state changes (selecting a spot, toggling
 * a layer, a search / custom pin appearing, entering/leaving minimal-nav mode, …).
 * Previously each call rebuilt **and** `setGeoJson`'d every source — most expensively
 * the bulk parking [FeatureCollection] of potentially thousands of spots. This cache
 * stores a cheap identity key per source and only rebuilds the ones whose key moved.
 *
 * A style reload recreates every source empty, so the cache keys off the [Style]
 * identity: the first [onStyle] with a fresh style clears all keys, forcing every
 * source to be re-serialised exactly once so the reloaded style is fully repainted.
 *
 * Instances are `remember`ed in `MainMapScreen` and passed into [updateMarkers]; the
 * default per-call instance (see [updateMarkers]) simply disables the cache, keeping
 * the old always-rebuild behaviour for any other caller.
 */
internal class MarkerRenderCache {
    private var lastStyle: Style? = null
    private val keys = HashMap<String, String>()

    /** Call once per render; drops all keys when [style] is a freshly reloaded one. */
    fun onStyle(style: Style) {
        if (style !== lastStyle) {
            keys.clear()
            lastStyle = style
        }
    }

    /**
     * Returns `true` (and remembers [key]) when [sourceId]'s inputs changed since the
     * last render, i.e. when its GeoJSON must be rebuilt; `false` to skip it.
     */
    fun changed(sourceId: String, key: String): Boolean {
        if (keys[sourceId] == key) return false
        keys[sourceId] = key
        return true
    }

    /**
     * Forgets a single source's cached key so its GeoJSON is rebuilt unconditionally
     * on the next render. Use when another component wrote to that shared source
     * behind this cache's back — e.g. `NavigationManager` owns [SOURCE_ROUTE] while
     * navigating, so on navigation stop this cache would otherwise still believe the
     * source holds whatever the renderer last drew and wrongly skip the redraw.
     */
    fun invalidate(sourceId: String) {
        keys.remove(sourceId)
    }

    /** Forget everything (new style detection + all keys). Mainly for tests. */
    fun reset() {
        keys.clear()
        lastStyle = null
    }
}

// ── Main update function ──────────────────────────────────────────────────────

/**
 * Syncs MapLibre GeoJSON sources / layers with the current app state.
 *
 * `suspend` so the heavy GeoJSON serialisation (the potentially huge bulk-parking
 * collection, and large saved-places sets — e.g. right after a full-backup restore)
 * runs off the main thread via [Dispatchers.Default]. Only the actual MapLibre
 * source/layer mutations stay on the caller's (main) dispatcher, as MapLibre's
 * sources are not thread-safe.
 */
internal suspend fun updateMarkers(
    map: MapLibreMap,
    spaces: List<BikeParkingSpace>,
    icons: MarkerIconSet,
    state: MarkerRenderState,
    display: MarkerDisplayConfig,
    route: RouteRenderData,
    clusterStyle: ClusterRenderStyle,
    searchPin: AddressSearchResult? = null,
    customMapPin: GeoCoordinate? = null,
    savedPlaces: List<SavedPlace> = emptyList(),
    parkedBike: ParkedBike? = null,
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
    suppressRoute: Boolean = false,
    /**
     * Minimal navigation mode: while actively navigating, hide every marker that
     * isn't part of the trip (all other parking spots, saved places, search pins)
     * so the map shows just the route, the destination and the live position.
     */
    minimalNavMode: Boolean = false,
    /**
     * Diff-gate cache that skips re-serialising a source whose inputs didn't change.
     * Defaults to a throw-away instance (no caching → always rebuild), so callers that
     * don't retain one keep the original always-rebuild behaviour.
     */
    cache: MarkerRenderCache = MarkerRenderCache()
) {
    val style = map.style ?: return

    // Drop all cached keys when the style was reloaded (its sources were recreated
    // empty), forcing every source below to be re-serialised exactly once.
    cache.onStyle(style)

    registerIcons(style, icons)

    // Route polyline — skipped while NavigationManager renders the travelled /
    // remaining split.
    if (!suppressRoute) {
        val routeKey = if (route.points.size > 1) {
            route.points.joinToString(";") { "${it.latitude},${it.longitude}" }
        } else {
            ""
        }
        if (cache.changed(SOURCE_ROUTE, routeKey)) {
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
        }
        ensureRouteLayer(style, route.color)
    }

    // Parking markers — bulk spots are clustered natively; the highlighted spot
    // (selection / active navigation destination) is rendered un-clustered on top.
    //
    // The two sources are gated independently: the (heavy) bulk source is keyed on
    // the spot set / visibility / nav flags — deliberately NOT on the selection — so
    // selecting a spot leaves the bulk untouched and only refreshes the (cheap)
    // highlight overlay, which keys on the selection and must update immediately.
    val bulkKey = parkingBulkKey(
        spaces, state.favoriteIds, state.activeNavigationSpaceId,
        layerVisibility, minimalNavMode, parkedBike
    )
    if (cache.changed(SOURCE_PARKING, bulkKey)) {
        // Serialising the (potentially many-thousand-spot) bulk collection is the
        // dominant per-pass cost; build it off the main thread, then apply on it.
        val bulkCollection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                buildBulkParkingFeatures(
                    spaces, state.favoriteIds, state.activeNavigationSpaceId,
                    layerVisibility, minimalNavMode, parkedBike
                )
            )
        }
        upsertParkingSource(style, bulkCollection)
    }
    ensureParkingLayer(style)
    ensureParkingClusterLayers(style, clusterStyle.circleColor, clusterStyle.textColor)
    val highlightKey = parkingHighlightKey(spaces, state, parkedBike)
    if (cache.changed(SOURCE_PARKING_HIGHLIGHT, highlightKey)) {
        val highlightFeatures = buildHighlightParkingFeatures(spaces, state, parkedBike)
        upsertSource(style, SOURCE_PARKING_HIGHLIGHT, FeatureCollection.fromFeatures(highlightFeatures))
    }
    ensureParkingHighlightLayer(style)

    // Location dot — skipped while NavigationManager animates the heading arrow.
    if (!suppressLocationDot) {
        val locKey = state.userLocation?.let { "${it.latitude},${it.longitude}" } ?: ""
        if (cache.changed(SOURCE_LOCATION, locKey)) {
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
        }
        ensureLocationLayer(style)
    }

    // Search pin (address result)
    if (style.getImage(IMG_SEARCH_PIN) == null) {
        style.addImage(IMG_SEARCH_PIN, drawableToBitmap(createSearchPinIcon()))
    }
    val searchKey = searchPin?.let { "${it.latitude},${it.longitude}" } ?: ""
    if (cache.changed(SOURCE_SEARCH_PIN, searchKey)) {
        val searchPinGeoJson = if (searchPin != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(searchPin.longitude, searchPin.latitude))
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        upsertSource(style, SOURCE_SEARCH_PIN, searchPinGeoJson)
    }
    ensureSearchPinLayer(style)

    // Custom map pin (tapped by user on empty map area)
    if (style.getImage(IMG_CUSTOM_PIN) == null) {
        style.addImage(IMG_CUSTOM_PIN, drawableToBitmap(createCustomPinIcon()))
    }
    val customKey = customMapPin?.let { "${it.latitude},${it.longitude}" } ?: ""
    if (cache.changed(SOURCE_CUSTOM_PIN, customKey)) {
        val customPinGeoJson = if (customMapPin != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(customMapPin.longitude, customMapPin.latitude))
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        upsertSource(style, SOURCE_CUSTOM_PIN, customPinGeoJson)
    }
    ensureCustomPinLayer(style)

    // Saved places (custom pins saved as named favourites) — persistent markers
    if (style.getImage(IMG_SAVED_PIN) == null) {
        style.addImage(IMG_SAVED_PIN, drawableToBitmap(createSavedPlaceIcon()))
    }
    val savedKey = savedPlacesKey(savedPlaces, layerVisibility.showSavedPlaces)
    if (cache.changed(SOURCE_SAVED_PIN, savedKey)) {
        // Restored backups can carry many saved places; serialise them off-thread.
        val savedGeoJson = withContext(Dispatchers.Default) {
            if (layerVisibility.showSavedPlaces) {
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
        }
        upsertSource(style, SOURCE_SAVED_PIN, savedGeoJson)
    }
    ensureSavedPinLayer(style)

    // Parked bike — a single persistent amber marker until the user picks it up.
    if (style.getImage(IMG_PARKED_BIKE) == null) {
        style.addImage(IMG_PARKED_BIKE, drawableToBitmap(createParkedBikeIcon(display.context)))
    }
    val parkedKey = parkedBike?.let { "${it.latitude},${it.longitude}" } ?: ""
    if (cache.changed(SOURCE_PARKED_BIKE, parkedKey)) {
        val parkedBikeGeoJson = if (parkedBike != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(parkedBike.longitude, parkedBike.latitude)).also {
                    it.addStringProperty(PROP_PARKED_BIKE_ID, PARKED_BIKE_FEATURE_ID)
                }
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        upsertSource(style, SOURCE_PARKED_BIKE, parkedBikeGeoJson)
    }
    ensureParkedBikeLayer(style)
}

/**
 * Updates **only** the live-location dot ([SOURCE_LOCATION]) — split out of
 * [updateMarkers] so a fresh GPS fix can move the dot without re-serialising the
 * whole parking / favourites / saved-places GeoJSON on every fix (the dominant
 * per-fix cost while recording / browsing). [updateMarkers] is therefore always
 * called with `suppressLocationDot = true`; this function owns the dot.
 *
 * No-op while [suppress] is `true` (active navigation), where `NavigationManager`
 * owns the puck and animates the rotating heading arrow. The location image is
 * (re)registered when missing so the dot survives a style reload independently of
 * the marker pass.
 */
internal fun updateLocationDot(
    map: MapLibreMap,
    location: GeoCoordinate?,
    locationIcon: Drawable,
    suppress: Boolean
) {
    val style = map.style ?: return
    if (suppress) return

    if (style.getImage(IMG_LOCATION) == null) {
        style.addImage(IMG_LOCATION, drawableToBitmap(locationIcon))
    }

    val locFeature = location?.let { loc ->
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


// ── Feature building ──────────────────────────────────────────────────────────

/**
 * Whether a spot is shown in the **bulk** (clustered) parking source, driven purely
 * by category visibility — deliberately independent of the current selection, which
 * is drawn by the separate highlight overlay. Returns `false` for every spot in
 * minimal-nav mode (only the trip destination survives, via the highlight source)
 * and for the spot the bike is parked at (the amber parked-bike pin stands in).
 */
private fun isBulkVisible(
    space: BikeParkingSpace,
    favoriteIds: List<String>,
    layerVisibility: LayerVisibility,
    minimalNavMode: Boolean,
    parkedBike: ParkedBike?
): Boolean {
    if (minimalNavMode) return false
    if (parkedBike != null && isParkedAt(space, parkedBike)) return false
    return if (favoriteIds.contains(space.id)) layerVisibility.showFavorites
           else layerVisibility.showParking
}

/**
 * Bulk (clustered) parking features. Selection is intentionally not consulted here:
 * the selected spot keeps its base icon in the bulk source and the highlight overlay
 * paints the selected pin on top, so selecting/deselecting never re-serialises this
 * (potentially huge) collection. See [buildHighlightParkingFeatures].
 */
private fun buildBulkParkingFeatures(
    spaces: List<BikeParkingSpace>,
    favoriteIds: List<String>,
    activeNavigationSpaceId: String?,
    layerVisibility: LayerVisibility,
    minimalNavMode: Boolean,
    parkedBike: ParkedBike?
): List<Feature> {
    if (minimalNavMode) return emptyList()
    val out = ArrayList<Feature>(spaces.size)
    spaces.forEach { space ->
        if (!isBulkVisible(space, favoriteIds, layerVisibility, minimalNavMode, parkedBike)) return@forEach
        out += Feature.fromGeometry(Point.fromLngLat(space.longitude, space.latitude)).also {
            it.addStringProperty(PROP_SPACE_ID, space.id)
            it.addStringProperty(PROP_ICON, bulkIconKey(space, favoriteIds, activeNavigationSpaceId))
        }
    }
    return out
}

/**
 * Highlight (un-clustered, on-top) features for the selected spot and the active
 * navigation destination, so they never vanish into a cluster bubble. This is the
 * cheap source that must refresh immediately on a selection change.
 */
private fun buildHighlightParkingFeatures(
    spaces: List<BikeParkingSpace>,
    state: MarkerRenderState,
    parkedBike: ParkedBike?
): List<Feature> {
    val highlightIds = setOfNotNull(state.selectedSpaceId, state.activeNavigationSpaceId)
    if (highlightIds.isEmpty()) return emptyList()
    val out = ArrayList<Feature>(highlightIds.size)
    spaces.forEach { space ->
        if (space.id !in highlightIds) return@forEach
        // Yield to the amber parked-bike pin, exactly like the bulk source.
        if (parkedBike != null && isParkedAt(space, parkedBike)) return@forEach
        out += Feature.fromGeometry(Point.fromLngLat(space.longitude, space.latitude)).also {
            it.addStringProperty(PROP_SPACE_ID, space.id)
            it.addStringProperty(PROP_ICON, resolveIconKey(space, state))
        }
    }
    return out
}

// ── Diff-gate keys (pure, unit-tested) ────────────────────────────────────────

/**
 * Cheap identity key for the **bulk** parking source. Equal inputs → equal key;
 * a changed spot set / position, favourite state, layer visibility, minimal-nav
 * mode, active-navigation destination or parked-bike location produces a different
 * key. It is intentionally *insensitive to the selection*, so selecting a spot does
 * not invalidate (and re-serialise) the bulk source.
 */
internal fun parkingBulkKey(
    spaces: List<BikeParkingSpace>,
    favoriteIds: List<String>,
    activeNavigationSpaceId: String?,
    layerVisibility: LayerVisibility,
    minimalNavMode: Boolean,
    parkedBike: ParkedBike?
): String {
    if (minimalNavMode) return "minimal"
    return buildString {
        spaces.forEach { space ->
            if (!isBulkVisible(space, favoriteIds, layerVisibility, minimalNavMode, parkedBike)) return@forEach
            append(space.id).append('|')
                .append(bulkIconKey(space, favoriteIds, activeNavigationSpaceId)).append('|')
                .append(space.latitude).append(',').append(space.longitude).append(';')
        }
    }
}

/**
 * Cheap identity key for the parking **highlight** overlay. Keyed on the selection
 * and active-navigation destination (and their resolved icons / positions), so it
 * refreshes the instant the selection changes — its whole purpose.
 */
internal fun parkingHighlightKey(
    spaces: List<BikeParkingSpace>,
    state: MarkerRenderState,
    parkedBike: ParkedBike?
): String {
    val highlightIds = setOfNotNull(state.selectedSpaceId, state.activeNavigationSpaceId)
    if (highlightIds.isEmpty()) return ""
    return buildString {
        spaces.forEach { space ->
            if (space.id !in highlightIds) return@forEach
            if (parkedBike != null && isParkedAt(space, parkedBike)) return@forEach
            append(space.id).append('|')
                .append(resolveIconKey(space, state)).append('|')
                .append(space.latitude).append(',').append(space.longitude).append(';')
        }
    }
}

/** Cheap identity key for the saved-places source (ids + positions + visibility). */
internal fun savedPlacesKey(savedPlaces: List<SavedPlace>, visible: Boolean): String {
    if (!visible) return ""
    return buildString {
        savedPlaces.forEach { place ->
            append(place.id).append('|')
                .append(place.latitude).append(',').append(place.longitude).append(';')
        }
    }
}

/**
 * Icon key used for a spot in the **bulk** source. Mirrors [resolveIconKey] for
 * non-selected spots but never reports the *selected* variants, so the bulk icon —
 * and therefore [parkingBulkKey] — is independent of the current selection (the
 * selected pin is painted by the highlight overlay on top).
 */
private fun bulkIconKey(
    space: BikeParkingSpace,
    favoriteIds: List<String>,
    activeNavigationSpaceId: String?
): String {
    val isNavDest  = space.id == activeNavigationSpaceId
    val showMuted  = activeNavigationSpaceId != null && !isNavDest
    val isFavorite = favoriteIds.contains(space.id)
    return when {
        isNavDest               -> IMG_SELECTED
        showMuted && isFavorite -> IMG_MUTED_FAVORITE
        showMuted               -> IMG_MUTED_NORMAL
        isFavorite              -> IMG_FAVORITE
        else                    -> IMG_NORMAL
    }
}

/** Distance (m) within which a parking spot counts as "the spot the bike is parked at". */
private const val PARKED_BIKE_MATCH_METERS = 12.0

/**
 * Whether [space] is (essentially) the same location as the [parkedBike], so the
 * spot marker should yield to the dedicated parked-bike pin. Auto-parking copies
 * the spot's exact coordinates (distance ≈ 0); the small radius also absorbs GPS
 * jitter when parking manually right at a rack.
 */
private fun isParkedAt(space: BikeParkingSpace, parkedBike: ParkedBike): Boolean =
    GeoMath.distanceMeters(
        space.latitude, space.longitude, parkedBike.latitude, parkedBike.longitude
    ) < PARKED_BIKE_MATCH_METERS

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


