package de.velospot.feature.map.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.core.graphics.toColorInt
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.AddressSearchResult
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// ── Source / Layer IDs (internal so MainMapScreen can use them for hit-testing) ─

private const val SOURCE_ROUTE      = "velospot-route-source"
private const val SOURCE_PARKING    = "velospot-parking-source"
private const val SOURCE_LOCATION   = "velospot-location-source"
private const val SOURCE_SEARCH_PIN = "velospot-search-pin-source"
private const val SOURCE_CUSTOM_PIN = "velospot-custom-pin-source"

internal const val LAYER_ROUTE      = "velospot-route-layer"
internal const val LAYER_PARKING    = "velospot-parking-layer"
internal const val LAYER_LOCATION   = "velospot-location-layer"
internal const val LAYER_SEARCH_PIN = "velospot-search-pin-layer"
internal const val LAYER_CUSTOM_PIN = "velospot-custom-pin-layer"

private const val IMG_SEARCH_PIN    = "vs-search-pin"
private const val IMG_CUSTOM_PIN    = "vs-custom-pin"

/** Feature property key used for click-to-space lookup in the parking layer. */
internal const val PROP_SPACE_ID = "spaceId"
private  const val PROP_ICON     = "iconImage"

private const val IMG_NORMAL         = "vs-marker-normal"
private const val IMG_FAVORITE       = "vs-marker-favorite"
private const val IMG_SELECTED       = "vs-marker-selected"
private const val IMG_MUTED_NORMAL   = "vs-marker-muted-normal"
private const val IMG_MUTED_FAVORITE = "vs-marker-muted-favorite"
private const val IMG_MUTED_SELECTED = "vs-marker-muted-selected"
internal const val IMG_LOCATION      = "vs-location"
internal const val IMG_LOCATION_NAV  = "vs-location-nav"

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
 * Click handling is NOT managed here – set up a single [MapLibreMap.addOnMapClickListener]
 * in [MainMapScreen] that queries [LAYER_PARKING] and reads [PROP_SPACE_ID].
 */
internal fun updateMarkers(
    map: MapLibreMap,
    spaces: List<BikeParkingSpace>,
    icons: MarkerIconSet,
    state: MarkerRenderState,
    display: MarkerDisplayConfig,
    route: RouteRenderData,
    searchPin: AddressSearchResult? = null,
    customMapPin: GeoCoordinate? = null
) {
    val style = map.style ?: return

    registerIcons(style, icons, state.activeNavigationSpaceId != null)

    // Route polyline
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

    // Parking markers
    upsertSource(style, SOURCE_PARKING, FeatureCollection.fromFeatures(buildParkingFeatures(spaces, state)))
    ensureParkingLayer(style)

    // Location dot
    val locFeature = state.userLocation?.let { loc ->
        Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude)).also {
            it.addStringProperty(PROP_ICON, if (state.activeNavigationSpaceId != null) IMG_LOCATION_NAV else IMG_LOCATION)
        }
    }
    upsertSource(
        style, SOURCE_LOCATION,
        if (locFeature != null) FeatureCollection.fromFeature(locFeature)
        else FeatureCollection.fromFeatures(emptyList())
    )
    ensureLocationLayer(style)

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
}

// ── GeoJSON source upsert ─────────────────────────────────────────────────────

private fun upsertSource(style: Style, id: String, data: FeatureCollection) {
    (style.getSource(id) as? GeoJsonSource)?.setGeoJson(data)
        ?: style.addSource(GeoJsonSource(id, data))
}

// ── Layer creation (idempotent) ───────────────────────────────────────────────

private fun ensureRouteLayer(style: Style, colorInt: Int) {
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

private fun ensureParkingLayer(style: Style) {
    if (style.getLayer(LAYER_PARKING) != null) return
    style.addLayer(
        SymbolLayer(LAYER_PARKING, SOURCE_PARKING).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

private fun ensureLocationLayer(style: Style) {
    if (style.getLayer(LAYER_LOCATION) != null) return
    style.addLayer(
        SymbolLayer(LAYER_LOCATION, SOURCE_LOCATION).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
        )
    )
}

private fun ensureSearchPinLayer(style: Style) {
    if (style.getLayer(LAYER_SEARCH_PIN) != null) return
    style.addLayer(
        SymbolLayer(LAYER_SEARCH_PIN, SOURCE_SEARCH_PIN).withProperties(
            PropertyFactory.iconImage(IMG_SEARCH_PIN),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

private fun ensureCustomPinLayer(style: Style) {
    if (style.getLayer(LAYER_CUSTOM_PIN) != null) return
    style.addLayer(
        SymbolLayer(LAYER_CUSTOM_PIN, SOURCE_CUSTOM_PIN).withProperties(
            PropertyFactory.iconImage(IMG_CUSTOM_PIN),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
        )
    )
}

// ── Icon registration ─────────────────────────────────────────────────────────

private fun registerIcons(style: Style, icons: MarkerIconSet, navigationActive: Boolean) {
    fun add(id: String, d: Drawable) {
        if (style.getImage(id) == null) style.addImage(id, drawableToBitmap(d))
    }
    add(IMG_NORMAL,         icons.normal)
    add(IMG_FAVORITE,       icons.favorite)
    add(IMG_SELECTED,       icons.selected)
    add(IMG_MUTED_NORMAL,   icons.mutedNormal)
    add(IMG_MUTED_FAVORITE, icons.mutedFavorite)
    add(IMG_MUTED_SELECTED, icons.mutedSelected)
    // Location icons – re-add when navigation state changes (different drawable)
    style.removeImage(IMG_LOCATION)
    style.removeImage(IMG_LOCATION_NAV)
    add(IMG_LOCATION,     icons.location)
    add(IMG_LOCATION_NAV, icons.location)
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bmp
}

// ── Feature building ──────────────────────────────────────────────────────────

private fun buildParkingFeatures(spaces: List<BikeParkingSpace>, state: MarkerRenderState): List<Feature> {
    val highlightedId = state.activeNavigationSpaceId ?: state.selectedSpaceId
    val (others, highlighted) = spaces.partition { it.id != highlightedId }
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

// ── Icon rendering (drawing logic unchanged from osmdroid version) ─────────────

internal fun createBikeMarkerIcon(context: Context, zoomBucket: Int, pinColor: Int): Drawable {
    val scale = when {
        zoomBucket <= 12 -> 0.42f
        zoomBucket == 13 -> 0.52f
        zoomBucket == 14 -> 0.62f
        zoomBucket == 15 -> 0.76f
        zoomBucket == 16 -> 0.90f
        zoomBucket == 17 -> 1.00f
        else             -> 1.12f
    }
    val width  = (120 * scale).roundToInt()
    val height = (148 * scale).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColor; style = Paint.Style.FILL }
    canvas.drawCircle(width / 2f, 52f * scale, 44f * scale, pinPaint)
    canvas.drawPath(Path().apply {
        moveTo(width / 2f, 140f * scale); lineTo(30f * scale, 78f * scale); lineTo(90f * scale, 78f * scale); close()
    }, pinPaint)

    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 46f * scale; textAlign = Paint.Align.CENTER
    }
    val baselineY = 52f * scale - ((textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f) - (2f * scale)
    canvas.drawText("🚲", width / 2f, baselineY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

internal fun createLocationMarkerIcon(context: Context, isNavigationActive: Boolean): Drawable {
    val size = if (isNavigationActive) 46 else 34
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawCircle(size / 2f, size / 2f, if (isNavigationActive) 21f else 15f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNavigationActive) "#00695C".toColorInt() else "#2196F3".toColorInt()
            style = Paint.Style.FILL
        })
    if (isNavigationActive) {
        canvas.drawCircle(size / 2f, size / 2f, 15f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#A7FFEB".toColorInt(); style = Paint.Style.STROKE; strokeWidth = 4f
            })
    }
    canvas.drawCircle(size / 2f, size / 2f, if (isNavigationActive) 8.5f else 7f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })

    return BitmapDrawable(context.resources, bitmap)
}

internal fun createMutedMarkerIcon(
    context: Context, source: Drawable,
    scale: Float = 0.84f, alpha: Int = 130, brightenOffset: Float = 34f
): Drawable {
    val sw = source.intrinsicWidth.coerceAtLeast(1)
    val sh = source.intrinsicHeight.coerceAtLeast(1)
    val srcBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    val srcCvs = Canvas(srcBmp)
    (source.constantState?.newDrawable()?.mutate() ?: source.mutate()).also {
        it.setBounds(0, 0, sw, sh); it.draw(srcCvs)
    }
    val tw = (sw * scale).roundToInt().coerceAtLeast(1)
    val th = (sh * scale).roundToInt().coerceAtLeast(1)
    val tgtBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val tgtCvs = Canvas(tgtBmp)
    val matrix = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            1f,0f,0f,0f,brightenOffset, 0f,1f,0f,0f,brightenOffset,
            0f,0f,1f,0f,brightenOffset, 0f,0f,0f,1f,0f
        )))
    }
    tgtCvs.drawBitmap(srcBmp, Rect(0,0,sw,sh), Rect(0,0,tw,th),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix); this.alpha = alpha })
    return BitmapDrawable(context.resources, tgtBmp)
}

/**
 * A bold red dropped-pin icon used for address search results.
 * Larger and more prominent than the bike parking markers.
 */
internal fun createSearchPinIcon(): Drawable {    val width  = 72
    val height = 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color   = 0x33000000
        style   = Paint.Style.FILL
    }
    canvas.drawCircle(width / 2f + 3f, 76f, 14f, shadowPaint)

    // Pin body (red circle)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E53935".toColorInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(width / 2f, 32f, 28f, pinPaint)

    // Pin tip
    val tipPath = Path().apply {
        moveTo(width / 2f, 90f)
        lineTo(width / 2f - 16f, 50f)
        lineTo(width / 2f + 16f, 50f)
        close()
    }
    canvas.drawPath(tipPath, pinPaint)

    // White inner circle
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(width / 2f, 32f, 14f, innerPaint)

    return BitmapDrawable(null, bitmap)
}

/**
 * A blue dropped-pin icon used for freely placed custom map pins
 * (tapped by the user on an empty map location).
 */
internal fun createCustomPinIcon(): Drawable {
    val width  = 72
    val height = 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Shadow
    canvas.drawCircle(width / 2f + 3f, 76f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    })

    // Pin body (blue circle)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1565C0".toColorInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(width / 2f, 32f, 28f, pinPaint)

    // Pin tip
    val tipPath = Path().apply {
        moveTo(width / 2f, 90f)
        lineTo(width / 2f - 16f, 50f)
        lineTo(width / 2f + 16f, 50f)
        close()
    }
    canvas.drawPath(tipPath, pinPaint)

    // White inner circle
    canvas.drawCircle(width / 2f, 32f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    })

    return BitmapDrawable(null, bitmap)
}

