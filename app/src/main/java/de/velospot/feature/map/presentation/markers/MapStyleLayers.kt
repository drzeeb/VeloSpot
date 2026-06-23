package de.velospot.feature.map.presentation.markers

import android.graphics.drawable.Drawable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre style plumbing: source / layer / image IDs and the idempotent helpers
 * that register them on a [Style]. Drawing of the icons lives in
 * `MarkerIconFactory.kt`; stateâ†’feature orchestration in `MapMarkerRenderer.kt`.
 */

// â”€â”€ Source / Layer IDs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

internal const val SOURCE_ROUTE      = "velospot-route-source"
internal const val SOURCE_PARKING    = "velospot-parking-source"
internal const val SOURCE_PARKING_HIGHLIGHT = "velospot-parking-highlight-source"
internal const val SOURCE_LOCATION   = "velospot-location-source"
internal const val SOURCE_SEARCH_PIN = "velospot-search-pin-source"
internal const val SOURCE_CUSTOM_PIN = "velospot-custom-pin-source"
internal const val SOURCE_SAVED_PIN  = "velospot-saved-pin-source"
internal const val SOURCE_PARKED_BIKE = "velospot-parked-bike-source"

/** Geometry of the already-travelled part of the route (drawn greyed-out). */
internal const val SOURCE_ROUTE_TRAVELED = "velospot-route-traveled-source"

/** Geometry of the live recorded ride track (or a reopened ride's track). */
internal const val SOURCE_TRACK = "velospot-track-source"

/** Aggregated weighted points of all recorded rides, feeding the heatmap overlay. */
internal const val SOURCE_HEATMAP = "velospot-heatmap-source"

/** Simplified polylines of every recorded ride, feeding the thin "ridden tracks" overlay. */
internal const val SOURCE_TRACKS_HISTORY = "velospot-tracks-history-source"

internal const val LAYER_ROUTE      = "velospot-route-layer"
internal const val LAYER_PARKING    = "velospot-parking-layer"
internal const val LAYER_PARKING_CLUSTER       = "velospot-parking-cluster-layer"
internal const val LAYER_PARKING_CLUSTER_COUNT = "velospot-parking-cluster-count-layer"
internal const val LAYER_PARKING_HIGHLIGHT     = "velospot-parking-highlight-layer"
internal const val LAYER_LOCATION   = "velospot-location-layer"
internal const val LAYER_SEARCH_PIN = "velospot-search-pin-layer"
internal const val LAYER_CUSTOM_PIN = "velospot-custom-pin-layer"
internal const val LAYER_SAVED_PIN  = "velospot-saved-pin-layer"
internal const val LAYER_PARKED_BIKE = "velospot-parked-bike-layer"

/** Greyed-out "already travelled" portion of the route, drawn beneath [LAYER_ROUTE]. */
internal const val LAYER_ROUTE_TRAVELED = "velospot-route-traveled-layer"

/** Recorded ride track (live recording or a reopened ride). */
internal const val LAYER_TRACK = "velospot-track-layer"

/** Heatmap of all recorded-ride GPS tracks (where you ride most). */
internal const val LAYER_HEATMAP = "velospot-heatmap-layer"

/** Thin per-ride polylines of all recorded rides (everywhere you have been). */
internal const val LAYER_TRACKS_HISTORY = "velospot-tracks-history-layer"

/** Feature property carrying a [0..1] heat weight for the heatmap points. */
internal const val PROP_HEAT_WEIGHT = "weight"

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
internal const val IMG_PARKED_BIKE   = "vs-parked-bike"

/** Feature property key used for click-to-space lookup in the parking layer. */
internal const val PROP_SPACE_ID = "spaceId"
/** Feature property key used for click-to-saved-place lookup in the saved-pin layer. */
internal const val PROP_SAVED_ID = "savedId"
/** Feature property key used for click-to-parked-bike lookup in the parked-bike layer. */
internal const val PROP_PARKED_BIKE_ID = "parkedBikeId"
/** The single feature id carried by the parked-bike marker (there is only ever one). */
internal const val PARKED_BIKE_FEATURE_ID = "parked-bike"
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

// â”€â”€ GeoJSON source upsert â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€ Layer creation (idempotent) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
    // never paints over a pin â€” relevant when NavigationManager re-adds it after
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
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            // Render the cyclist avatar as an upright billboard that always faces
            // the camera, so the 3D map tilt during navigation never flattens /
            // squishes it onto the ground plane. The navigation camera keeps the
            // heading pointing "up", so the rider naturally appears from behind
            // (true 3rd-person view) without any extra per-feature rotation.
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)
        )
    )
}

/**
 * Adds (idempotently) the [LAYER_BUILDINGS_3D] `fill-extrusion` layer that pulls
 * the flat OpenMapTiles building footprints up into 3D using their
 * `render_height` / `render_min_height` attributes. No-op when the active style
 * carries no `openmaptiles` vector source. Starts hidden â€” toggle with
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
    // Insert beneath all of our own overlays so the extruded volumes never paint
    // over a marker (the live-location cyclist, parking pins) or the route line.
    // MapLibre draws symbol/line layers without a depth test against the 3D
    // fill-extrusion, so visibility is purely a question of paint order: adding
    // the buildings on top (the previous `style.addLayer`) made them occlude the
    // cyclist avatar wherever a footprint overlapped it on screen — the "marker
    // disappears behind buildings" bug. Slotting the layer below the lowest of our
    // overlays (preferring the route line, falling back through the markers) keeps
    // every overlay reliably in the foreground while the volumes still rise above
    // the flat base building fill of the underlying vector style.
    val overlayLayersBottomUp = listOf(
        LAYER_ROUTE_TRAVELED, LAYER_ROUTE, LAYER_TRACK,
        LAYER_PARKING, LAYER_PARKING_CLUSTER, LAYER_PARKING_CLUSTER_COUNT,
        LAYER_PARKING_HIGHLIGHT, LAYER_LOCATION,
        LAYER_SEARCH_PIN, LAYER_CUSTOM_PIN, LAYER_SAVED_PIN, LAYER_PARKED_BIKE
    )
    val anchorBelow = overlayLayersBottomUp.firstOrNull { style.getLayer(it) != null }
    if (anchorBelow != null) style.addLayerBelow(layer, anchorBelow) else style.addLayer(layer)
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

internal fun ensureParkedBikeLayer(style: Style) {
    if (style.getLayer(LAYER_PARKED_BIKE) != null) return
    style.addLayer(
        SymbolLayer(LAYER_PARKED_BIKE, SOURCE_PARKED_BIKE).withProperties(
            PropertyFactory.iconImage(IMG_PARKED_BIKE),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

/**
 * Idempotently registers the recorded-ride track line and replaces its geometry.
 * Drawn as a dashed line in the supplied [colorInt] beneath the parking markers,
 * so it never paints over a pin. Pass an empty list to clear it.
 */
internal fun updateTrackLayer(style: Style, points: List<Pair<Double, Double>>, colorInt: Int) {
    val data = if (points.size > 1) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(
                LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })
            )
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    upsertSource(style, SOURCE_TRACK, data)
    if (style.getLayer(LAYER_TRACK) == null) {
        val hex = "#%06X".format(0xFFFFFF and colorInt)
        val layer = LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
            PropertyFactory.lineColor(hex),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        if (style.getLayer(LAYER_PARKING) != null) style.addLayerBelow(layer, LAYER_PARKING)
        else style.addLayer(layer)
    }
}

/**
 * Idempotently registers and updates the **"ridden tracks"** overlay: every
 * recorded ride drawn as its own thin, translucent line. Where lines overlap the
 * colour builds up, so frequently used streets read stronger — a lightweight
 * personal-heatmap effect that still shows individual routes.
 *
 * Inserted beneath the parking markers so pins stay tappable on top, and
 * shown/hidden via [visible] (also hidden when there are no polylines). Pass an
 * empty list / `visible = false` to clear it.
 */
internal fun updateTracksHistoryLayer(
    style: Style,
    polylines: List<List<Pair<Double, Double>>>,
    colorInt: Int,
    visible: Boolean
) {
    val show = visible && polylines.any { it.size > 1 }
    val data = if (show) {
        FeatureCollection.fromFeatures(
            polylines.filter { it.size > 1 }.map { line ->
                Feature.fromGeometry(
                    LineString.fromLngLats(line.map { Point.fromLngLat(it.second, it.first) })
                )
            }
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    upsertSource(style, SOURCE_TRACKS_HISTORY, data)

    if (style.getLayer(LAYER_TRACKS_HISTORY) == null) {
        val hex = "#%06X".format(0xFFFFFF and colorInt)
        val layer = LineLayer(LAYER_TRACKS_HISTORY, SOURCE_TRACKS_HISTORY).withProperties(
            PropertyFactory.lineColor(hex),
            // Hairline that thickens slightly as you zoom in so it stays visible
            // city-wide but never dominates the map.
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(8, 0.5f),
                    Expression.stop(13, 1.0f),
                    Expression.stop(17, 1.8f)
                )
            ),
            // Translucent so overlapping passes accumulate into a heat-like effect.
            PropertyFactory.lineOpacity(0.35f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        if (style.getLayer(LAYER_PARKING) != null) style.addLayerBelow(layer, LAYER_PARKING)
        else style.addLayer(layer)
    }
    style.getLayer(LAYER_TRACKS_HISTORY)?.setProperties(
        PropertyFactory.visibility(if (show) Property.VISIBLE else Property.NONE)
    )
}

/**
 * Idempotently registers and updates the recorded-ride **heatmap** overlay from a
 * set of pre-aggregated, weighted [cells] (lat, lon, intensity in `0..1`). Each
 * cell becomes a heatmap point carrying its weight via [PROP_HEAT_WEIGHT].
 *
 * The layer is inserted beneath the parking markers so pins stay tappable on top,
 * and is shown/hidden via [visible] (also hidden when there are no cells, e.g. no
 * rides recorded yet). Pass an empty list / `visible = false` to clear it.
 */
internal fun updateHeatmapLayer(
    style: Style,
    cells: List<Triple<Double, Double, Double>>,
    visible: Boolean
) {
    val show = visible && cells.isNotEmpty()
    val data = if (show) {
        FeatureCollection.fromFeatures(
            cells.map { (lat, lon, weight) ->
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                    addNumberProperty(PROP_HEAT_WEIGHT, weight)
                }
            }
        )
    } else {
        FeatureCollection.fromFeatures(emptyList())
    }
    upsertSource(style, SOURCE_HEATMAP, data)

    if (style.getLayer(LAYER_HEATMAP) == null) {
        val layer = HeatmapLayer(LAYER_HEATMAP, SOURCE_HEATMAP).withProperties(
            // Per-point weight straight from the aggregated cell intensity (0..1).
            PropertyFactory.heatmapWeight(
                Expression.interpolate(
                    Expression.linear(), Expression.get(PROP_HEAT_WEIGHT),
                    Expression.stop(0.0, 0.1),
                    Expression.stop(1.0, 1.0)
                )
            ),
            // Grow the overall heat as you zoom in so streets read clearly.
            PropertyFactory.heatmapIntensity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(8, 0.6),
                    Expression.stop(16, 2.2)
                )
            ),
            // Cool → warm ramp, transparent at zero so the base map shows through.
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0.0, Expression.rgba(0, 0, 0, 0)),
                    Expression.stop(0.2, Expression.rgba(0, 137, 255, 0.5)),
                    Expression.stop(0.4, Expression.rgba(0, 230, 161, 0.7)),
                    Expression.stop(0.6, Expression.rgba(255, 221, 0, 0.8)),
                    Expression.stop(0.8, Expression.rgba(255, 138, 0, 0.9)),
                    Expression.stop(1.0, Expression.rgba(255, 40, 40, 0.95))
                )
            ),
            // Radius grows with zoom so cells blend into continuous lines up close.
            PropertyFactory.heatmapRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(8, 8f),
                    Expression.stop(14, 18f),
                    Expression.stop(17, 30f)
                )
            ),
            PropertyFactory.heatmapOpacity(0.75f)
        )
        if (style.getLayer(LAYER_PARKING) != null) style.addLayerBelow(layer, LAYER_PARKING)
        else style.addLayer(layer)
    }
    style.getLayer(LAYER_HEATMAP)?.setProperties(
        PropertyFactory.visibility(if (show) Property.VISIBLE else Property.NONE)
    )
}

// ── Icon registration ──────────────────────────────────────────────────────

internal fun registerIcons(style: Style, icons: MarkerIconSet) {
    // These pin icons are zoom-bucket dependent (their pixel size scales with the
    // zoom level), so they MUST be replaced whenever the icon set changes — a
    // guarded `getImage(id) == null` add would freeze the pins at the size of the
    // zoom level they were first registered at, only updating on a style reload
    // (e.g. dark-mode toggle). That left pins looking too big/small until the next
    // reload. MapLibre replaces a same-ID image in place, so this is cheap.
    fun add(id: String, d: Drawable) {
        style.addImage(id, drawableToBitmap(d))
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
