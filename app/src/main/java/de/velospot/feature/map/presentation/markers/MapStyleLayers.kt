package de.velospot.feature.map.presentation.markers

import android.graphics.drawable.Drawable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/**
 * MapLibre style plumbing: source / layer / image IDs and the idempotent helpers
 * that register them on a [Style]. Drawing of the icons lives in
 * `MarkerIconFactory.kt`; state→feature orchestration in `MapMarkerRenderer.kt`.
 */

// ── Source / Layer IDs ─────────────────────────────────────────────────────────

internal const val SOURCE_ROUTE      = "velospot-route-source"
internal const val SOURCE_PARKING    = "velospot-parking-source"
internal const val SOURCE_PARKING_HIGHLIGHT = "velospot-parking-highlight-source"
internal const val SOURCE_LOCATION   = "velospot-location-source"
internal const val SOURCE_SEARCH_PIN = "velospot-search-pin-source"
internal const val SOURCE_CUSTOM_PIN = "velospot-custom-pin-source"
internal const val SOURCE_SAVED_PIN  = "velospot-saved-pin-source"

internal const val LAYER_ROUTE      = "velospot-route-layer"
internal const val LAYER_PARKING    = "velospot-parking-layer"
internal const val LAYER_PARKING_CLUSTER       = "velospot-parking-cluster-layer"
internal const val LAYER_PARKING_CLUSTER_COUNT = "velospot-parking-cluster-count-layer"
internal const val LAYER_PARKING_HIGHLIGHT     = "velospot-parking-highlight-layer"
internal const val LAYER_LOCATION   = "velospot-location-layer"
internal const val LAYER_SEARCH_PIN = "velospot-search-pin-layer"
internal const val LAYER_CUSTOM_PIN = "velospot-custom-pin-layer"
internal const val LAYER_SAVED_PIN  = "velospot-saved-pin-layer"

/**
 * Minimum zoom level at which bike parking markers are displayed.
 * Below this value MapLibre hides the layer natively (GPU-side, no state update needed).
 * This roughly corresponds to city level (~50 km visible area).
 */
internal const val MIN_ZOOM_PARKING_VISIBLE = 11f

/**
 * Native GeoJSON clustering tuning for the parking source.
 * Below/at [CLUSTER_MAX_ZOOM] nearby markers are merged into a single cluster bubble
 * (drastically fewer rendered symbols); above it every spot is shown individually so
 * the user can tap a specific parking space. [CLUSTER_RADIUS] is the cluster pixel radius.
 */
internal const val CLUSTER_MAX_ZOOM = 13
internal const val CLUSTER_RADIUS   = 60

/** MapLibre-injected property present only on aggregated cluster features. */
internal const val PROP_POINT_COUNT = "point_count"
private const val PROP_POINT_COUNT_ABBREVIATED = "point_count_abbreviated"

internal const val IMG_SEARCH_PIN    = "vs-search-pin"
internal const val IMG_CUSTOM_PIN    = "vs-custom-pin"
internal const val IMG_SAVED_PIN     = "vs-saved-pin"

/** Feature property key used for click-to-space lookup in the parking layer. */
internal const val PROP_SPACE_ID = "spaceId"
/** Feature property key used for click-to-saved-place lookup in the saved-pin layer. */
internal const val PROP_SAVED_ID = "savedId"
internal const val PROP_ICON     = "iconImage"

internal const val IMG_NORMAL         = "vs-marker-normal"
internal const val IMG_FAVORITE       = "vs-marker-favorite"
internal const val IMG_SELECTED       = "vs-marker-selected"
internal const val IMG_MUTED_NORMAL   = "vs-marker-muted-normal"
internal const val IMG_MUTED_FAVORITE = "vs-marker-muted-favorite"
internal const val IMG_MUTED_SELECTED = "vs-marker-muted-selected"
internal const val IMG_LOCATION       = "vs-location"
internal const val IMG_LOCATION_NAV   = "vs-location-nav"

// ── GeoJSON source upsert ─────────────────────────────────────────────────────

internal fun upsertSource(style: Style, id: String, data: FeatureCollection) {
    (style.getSource(id) as? GeoJsonSource)?.setGeoJson(data)
        ?: style.addSource(GeoJsonSource(id, data))
}

/**
 * Upserts the parking GeoJSON source with native clustering enabled. The cluster
 * options can only be set at creation time, so the source is created clustered the
 * first time and only its data is replaced afterwards.
 */
internal fun upsertParkingSource(style: Style, data: FeatureCollection) {
    val existing = style.getSource(SOURCE_PARKING) as? GeoJsonSource
    if (existing != null) {
        existing.setGeoJson(data)
    } else {
        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
            .withClusterRadius(CLUSTER_RADIUS)
        style.addSource(GeoJsonSource(SOURCE_PARKING, data, options))
    }
}

private fun colorToHex(colorInt: Int): String = "#%06X".format(0xFFFFFF and colorInt)

// ── Layer creation (idempotent) ───────────────────────────────────────────────

internal fun ensureRouteLayer(style: Style, colorInt: Int) {
    if (style.getLayer(LAYER_ROUTE) != null) return
    val hex = "#%06X".format(0xFFFFFF and colorInt)
    style.addLayer(
        LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(hex),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
}

internal fun ensureParkingLayer(style: Style) {
    if (style.getLayer(LAYER_PARKING) != null) return
    val layer = SymbolLayer(LAYER_PARKING, SOURCE_PARKING).withProperties(
        PropertyFactory.iconImage(Expression.get(PROP_ICON)),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(false),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
    )
    // Only render individual (non-clustered) spots; aggregated points are drawn
    // by the cluster layers below.
    layer.setFilter(Expression.not(Expression.has(PROP_POINT_COUNT)))
    layer.minZoom = MIN_ZOOM_PARKING_VISIBLE
    style.addLayer(layer)
}

/**
 * Cluster bubble + count label for the aggregated parking points. The circle grows
 * in steps with the number of contained spots; the abbreviated count is drawn on top.
 */
internal fun ensureParkingClusterLayers(style: Style, circleColor: Int, textColor: Int) {
    if (style.getLayer(LAYER_PARKING_CLUSTER) == null) {
        val circle = CircleLayer(LAYER_PARKING_CLUSTER, SOURCE_PARKING).withProperties(
            PropertyFactory.circleColor(colorToHex(circleColor)),
            PropertyFactory.circleOpacity(0.92f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleRadius(
                Expression.step(
                    Expression.get(PROP_POINT_COUNT),
                    Expression.literal(15f),
                    Expression.stop(50, 20f),
                    Expression.stop(200, 26f),
                    Expression.stop(1000, 32f)
                )
            )
        )
        circle.setFilter(Expression.has(PROP_POINT_COUNT))
        circle.minZoom = MIN_ZOOM_PARKING_VISIBLE
        style.addLayer(circle)
    }
    if (style.getLayer(LAYER_PARKING_CLUSTER_COUNT) == null) {
        val count = SymbolLayer(LAYER_PARKING_CLUSTER_COUNT, SOURCE_PARKING).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get(PROP_POINT_COUNT_ABBREVIATED))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(colorToHex(textColor)),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true)
        )
        count.setFilter(Expression.has(PROP_POINT_COUNT))
        count.minZoom = MIN_ZOOM_PARKING_VISIBLE
        style.addLayer(count)
    }
}

/**
 * Non-clustered overlay for the highlighted spot (current selection / active
 * navigation destination) so it always stays visible on top of cluster bubbles.
 */
internal fun ensureParkingHighlightLayer(style: Style) {
    if (style.getLayer(LAYER_PARKING_HIGHLIGHT) != null) return
    val layer = SymbolLayer(LAYER_PARKING_HIGHLIGHT, SOURCE_PARKING_HIGHLIGHT).withProperties(
        PropertyFactory.iconImage(Expression.get(PROP_ICON)),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
    )
    layer.minZoom = MIN_ZOOM_PARKING_VISIBLE
    style.addLayer(layer)
}

internal fun ensureLocationLayer(style: Style) {
    if (style.getLayer(LAYER_LOCATION) != null) return
    style.addLayer(
        SymbolLayer(LAYER_LOCATION, SOURCE_LOCATION).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
        )
    )
}

internal fun ensureSearchPinLayer(style: Style) {
    if (style.getLayer(LAYER_SEARCH_PIN) != null) return
    style.addLayer(
        SymbolLayer(LAYER_SEARCH_PIN, SOURCE_SEARCH_PIN).withProperties(
            PropertyFactory.iconImage(IMG_SEARCH_PIN),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

internal fun ensureCustomPinLayer(style: Style) {
    if (style.getLayer(LAYER_CUSTOM_PIN) != null) return
    style.addLayer(
        SymbolLayer(LAYER_CUSTOM_PIN, SOURCE_CUSTOM_PIN).withProperties(
            PropertyFactory.iconImage(IMG_CUSTOM_PIN),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

internal fun ensureSavedPinLayer(style: Style) {
    if (style.getLayer(LAYER_SAVED_PIN) != null) return
    style.addLayer(
        SymbolLayer(LAYER_SAVED_PIN, SOURCE_SAVED_PIN).withProperties(
            PropertyFactory.iconImage(IMG_SAVED_PIN),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

// ── Icon registration ─────────────────────────────────────────────────────────

internal fun registerIcons(style: Style, icons: MarkerIconSet) {
    fun add(id: String, d: Drawable) {
        if (style.getImage(id) == null) style.addImage(id, drawableToBitmap(d))
    }
    add(IMG_NORMAL,         icons.normal)
    add(IMG_FAVORITE,       icons.favorite)
    add(IMG_SELECTED,       icons.selected)
    add(IMG_MUTED_NORMAL,   icons.mutedNormal)
    add(IMG_MUTED_FAVORITE, icons.mutedFavorite)
    add(IMG_MUTED_SELECTED, icons.mutedSelected)
    // Location icons change when navigation state changes (different size/colour).
    // Use addImage directly – MapLibre replaces an existing image with the same ID in-place,
    // avoiding an unnecessary remove+add on every GPS position or zoom update.
    style.addImage(IMG_LOCATION,     drawableToBitmap(icons.location))
    style.addImage(IMG_LOCATION_NAV, drawableToBitmap(icons.location))
}

