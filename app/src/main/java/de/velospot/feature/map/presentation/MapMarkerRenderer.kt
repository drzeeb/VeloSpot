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
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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

internal fun updateMarkers(
    map: MapView,
    spaces: List<BikeParkingSpace>,
    icons: MarkerIconSet,
    state: MarkerRenderState,
    display: MarkerDisplayConfig,
    route: RouteRenderData,
    onMarkerClick: (BikeParkingSpace) -> Unit
) {
    map.overlays.clear()

    if (route.points.size > 1) {
        val routePolyline = Polyline().apply {
            outlinePaint.color = route.color
            outlinePaint.strokeWidth = 10f
            setPoints(route.points.map { GeoPoint(it.latitude, it.longitude) })
        }
        map.overlays.add(routePolyline)
    }

    // Draw selected/active destination last so it stays visible on overlaps.
    val highlightedSpaceId = state.activeNavigationSpaceId ?: state.selectedSpaceId
    val (otherSpaces, highlightedSpaces) = spaces.partition { it.id != highlightedSpaceId }
    (otherSpaces + highlightedSpaces).forEach { space ->
        val isNavigationDestination = space.id == state.activeNavigationSpaceId
        val showMutedStyle = state.activeNavigationSpaceId != null && !isNavigationDestination
        val marker = Marker(map).apply {
            position = GeoPoint(space.latitude, space.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = resolveMarkerIcon(space = space, icons = icons, state = state, showMutedStyle = showMutedStyle)
            title = space.name ?: space.type.label(display.context)
            snippet = buildSnippet(space, display.labels.snippetSpacesFormat)
            setOnMarkerClickListener { _, _ ->
                onMarkerClick(space)
                true
            }
        }
        map.overlays.add(marker)
    }

    state.userLocation?.let { location ->
        val locationMarker = Marker(map).apply {
            position = GeoPoint(location.latitude, location.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = icons.location.constantState?.newDrawable()?.mutate() ?: icons.location
            title = display.labels.myLocationTitle
        }
        map.overlays.add(locationMarker)
    }

    map.invalidate()
}

private fun resolveMarkerIcon(
    space: BikeParkingSpace,
    icons: MarkerIconSet,
    state: MarkerRenderState,
    showMutedStyle: Boolean
): Drawable {
    val baseIcon = when {
        space.id == state.activeNavigationSpaceId -> icons.activeNavigation
        space.id == state.selectedSpaceId -> if (showMutedStyle) icons.mutedSelected else icons.selected
        state.favoriteIds.contains(space.id) -> if (showMutedStyle) icons.mutedFavorite else icons.favorite
        else -> if (showMutedStyle) icons.mutedNormal else icons.normal
    }
    return baseIcon.constantState?.newDrawable()?.mutate() ?: baseIcon
}

internal fun createBikeMarkerIcon(
    context: Context,
    zoomBucket: Int,
    pinColor: Int
): Drawable {
    val scale = when {
        zoomBucket <= 12 -> 0.42f
        zoomBucket == 13 -> 0.52f
        zoomBucket == 14 -> 0.62f
        zoomBucket == 15 -> 0.76f
        zoomBucket == 16 -> 0.90f
        zoomBucket == 17 -> 1.00f
        else -> 1.12f
    }

    val width = (120 * scale).roundToInt()
    val height = (148 * scale).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pinColor
        style = Paint.Style.FILL
    }

    val circleCenterX = width / 2f
    val circleCenterY = 52f * scale
    val circleRadius = 44f * scale
    canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, pinPaint)

    val tipPath = Path().apply {
        moveTo(width / 2f, 140f * scale)
        lineTo(30f * scale, 78f * scale)
        lineTo(90f * scale, 78f * scale)
        close()
    }
    canvas.drawPath(tipPath, pinPaint)

    val emoji = "🚲"
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f * scale
        textAlign = Paint.Align.CENTER
    }
    val baselineY = circleCenterY -
        ((textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f) -
        (2f * scale)
    canvas.drawText(emoji, circleCenterX, baselineY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

internal fun createLocationMarkerIcon(context: Context, isNavigationActive: Boolean): Drawable {
    val size = if (isNavigationActive) 46 else 34
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isNavigationActive) "#00695C".toColorInt() else "#2196F3".toColorInt()
        style = Paint.Style.FILL
    }
    val outerRadius = if (isNavigationActive) 21f else 15f
    canvas.drawCircle(size / 2f, size / 2f, outerRadius, outerPaint)

    if (isNavigationActive) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#A7FFEB".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, 15f, ringPaint)
    }

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, if (isNavigationActive) 8.5f else 7f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
}

internal fun createMutedMarkerIcon(
    context: Context,
    source: Drawable,
    scale: Float = 0.84f,
    alpha: Int = 130,
    brightenOffset: Float = 34f
): Drawable {
    val sourceWidth = source.intrinsicWidth.coerceAtLeast(1)
    val sourceHeight = source.intrinsicHeight.coerceAtLeast(1)
    val sourceBitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
    val sourceCanvas = Canvas(sourceBitmap)
    val drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
    drawable.setBounds(0, 0, sourceWidth, sourceHeight)
    drawable.draw(sourceCanvas)

    val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val targetBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val targetCanvas = Canvas(targetBitmap)
    val grayscaleAndBrightenMatrix = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, brightenOffset,
                    0f, 1f, 0f, 0f, brightenOffset,
                    0f, 0f, 1f, 0f, brightenOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(grayscaleAndBrightenMatrix)
        this.alpha = alpha
    }

    targetCanvas.drawBitmap(
        sourceBitmap,
        Rect(0, 0, sourceWidth, sourceHeight),
        Rect(0, 0, targetWidth, targetHeight),
        paint
    )

    return BitmapDrawable(context.resources, targetBitmap)
}

private fun buildSnippet(
    space: BikeParkingSpace,
    snippetSpacesFormat: String
): String = buildString {
    space.address?.let { append(it) }
    space.capacity?.let { cap ->
        if (isNotEmpty()) append(" · ")
        append(String.format(snippetSpacesFormat, cap))
    }
}

