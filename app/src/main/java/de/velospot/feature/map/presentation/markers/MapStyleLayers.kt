package de.velospot.feature.map.presentation.markers

import android.graphics.drawable.Drawable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
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

/** Geometry of the already-travelled part of the route (drawn greyed-out). */
internal const val SOURCE_ROUTE_TRAVELED = "velospot-route-traveled-source"

internal const val LAYER_ROUTE      = "velospot-route-layer"
internal const val LAYER_PARKING    = "velospot-parking-layer"
internal const val LAYER_LOCATION   = "velospot-location-layer"
internal const val LAYER_SEARCH_PIN = "velospot-search-pin-layer"
internal const val LAYER_CUSTOM_PIN = "velospot-custom-pin-layer"
internal const val LAYER_SAVED_PIN  = "velospot-saved-pin-layer"

/** Greyed-out "already travelled" portion of the route, drawn beneath [LAYER_ROUTE]. */
internal const val LAYER_ROUTE_TRAVELED = "velospot-route-traveled-layer"

/** 3D building extrusion layer (added on top of the vector style's flat buildings). */
internal const val LAYER_BUILDINGS_3D = "velospot-buildings-3d-layer"

/**
 * Vector source / source-layer holding the building footprints. Both the light
 * (OpenFreeMap "liberty") and the bundled dark style use the OpenMapTiles schema,
 * exposing `render_height` / `render_min_height` for extrusion.
 */
private const val VECTOR_SOURCE_OPENMAPTILES = "openmaptiles"
private const val VECTOR_LAYER_BUILDING      = "building"

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
/** Feature property holding the heading (degrees) used to rotate the navigation arrow. */
internal const val PROP_BEARING  = "bearing"

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
    val layer = LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
        PropertyFactory.lineColor(hex),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    )
    // Keep the route line beneath the markers (parking / location puck) so it
    // never paints over a pin — relevant when NavigationManager re-adds it after
    // the marker layers already exist.
    if (style.getLayer(LAYER_PARKING) != null) style.addLayerBelow(layer, LAYER_PARKING)
    else style.addLayer(layer)
}

/**
 * Greyed-out line for the part of the route already travelled, inserted directly
 * beneath the active [LAYER_ROUTE] so the coloured "remaining" line stays on top.
 */
internal fun ensureTraveledRouteLayer(style: Style) {
    if (style.getLayer(LAYER_ROUTE_TRAVELED) != null) return
    val layer = LineLayer(LAYER_ROUTE_TRAVELED, SOURCE_ROUTE_TRAVELED).withProperties(
        PropertyFactory.lineColor("#9E9E9E"),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineOpacity(0.55f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    )
    when {
        style.getLayer(LAYER_ROUTE) != null   -> style.addLayerBelow(layer, LAYER_ROUTE)
        style.getLayer(LAYER_PARKING) != null -> style.addLayerBelow(layer, LAYER_PARKING)
        else                                  -> style.addLayer(layer)
    }
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
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            // Rotate the heading arrow by the per-feature bearing. Defaults to 0
            // for the plain location dot (which is rotation-invariant anyway).
            PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
            // Keep the arrow flat on the (tilted) map and rotating with it so it
            // points along the road during 3D navigation.
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP)
        )
    )
}

/**
 * Adds (idempotently) the [LAYER_BUILDINGS_3D] `fill-extrusion` layer that pulls
 * the flat OpenMapTiles building footprints up into 3D using their
 * `render_height` / `render_min_height` attributes. No-op when the active style
 * carries no `openmaptiles` vector source. Starts hidden — toggle with
 * [setBuildingExtrusionVisible] (e.g. only during navigation).
 */
internal fun ensureBuildingExtrusionLayer(style: Style) {
    if (style.getLayer(LAYER_BUILDINGS_3D) != null) return
    if (style.getSource(VECTOR_SOURCE_OPENMAPTILES) == null) return

    val layer = FillExtrusionLayer(LAYER_BUILDINGS_3D, VECTOR_SOURCE_OPENMAPTILES).apply {
        sourceLayer = VECTOR_LAYER_BUILDING
        minZoom = 14f
        setProperties(
            PropertyFactory.fillExtrusionColor("#c9ccd6"),
            // Height/base read straight from the tile attributes.
            PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
            PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
            // Fade the volumes in between z14 and z15.5 so they don't pop.
            PropertyFactory.fillExtrusionOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(14f, 0f),
                    Expression.stop(15.5f, 0.85f)
                )
            )
        )
    }
    // Insert beneath our own symbol overlays so markers stay on top, but above
    // the flat base building fill.
    style.addLayer(layer)
    layer.setProperties(PropertyFactory.visibility(Property.NONE))
}

/** Shows or hides the 3D building extrusion layer (added by [ensureBuildingExtrusionLayer]). */
internal fun setBuildingExtrusionVisible(style: Style, visible: Boolean) {
    val layer = style.getLayer(LAYER_BUILDINGS_3D) ?: return
    layer.setProperties(
        PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
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
    // Plain location dot (idle / browsing). MapLibre replaces an existing image
    // with the same ID in-place, so re-adding on every GPS or zoom update is cheap.
    // IMG_LOCATION_NAV (the rotating heading arrow) is owned by NavigationManager
    // and intentionally NOT registered here so it is never clobbered.
    style.addImage(IMG_LOCATION, drawableToBitmap(icons.location))
}

