package de.velospot.feature.map.presentation.markers

import android.graphics.drawable.Drawable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
internal const val SOURCE_LOCATION   = "velospot-location-source"
internal const val SOURCE_SEARCH_PIN = "velospot-search-pin-source"
internal const val SOURCE_CUSTOM_PIN = "velospot-custom-pin-source"
internal const val SOURCE_SAVED_PIN  = "velospot-saved-pin-source"

internal const val LAYER_ROUTE      = "velospot-route-layer"
internal const val LAYER_PARKING    = "velospot-parking-layer"
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

