package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import de.velospot.domain.model.RecordedRide
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Localised text shown on the shareable ride card. Passed in from the composable
 * so the renderer itself stays free of any Android resource lookups (and can be
 * unit-tested on the JVM if needed).
 */
internal data class RideShareLabels(
    val headline: String,
    val durationLabel: String,
    val avgSpeedLabel: String,
    val elevationLabel: String,
    val maxSpeedLabel: String,
    val footer: String
)

/**
 * An optional real 2D map snapshot drawn behind the route line.
 *
 * @property mapBitmap the rendered map tile image (covers the route's bounding box).
 * @property routePointsImagePx the ride's track points already projected into the
 *  [mapBitmap]'s pixel space (via MapLibre's `pixelForLatLng`), so the polyline lines
 *  up exactly with the map underneath regardless of the projection details.
 */
internal class RideMapLayer(
    val mapBitmap: Bitmap,
    val routePointsImagePx: List<PointF>
)

/** Logical pixel size of the map panel on the card — also used to request the map snapshot. */
internal val RIDE_SHARE_PANEL_WIDTH: Int = (PANEL_RIGHT - PANEL_LEFT).toInt()
internal val RIDE_SHARE_PANEL_HEIGHT: Int = (PANEL_BOTTOM - PANEL_TOP).toInt()

/**
 * Renders a "VeloSpot Wrapped"-style shareable card for a recorded ride — a bold,
 * vertical (4:5) social-media tile with a vibrant gradient, the GPS track drawn as
 * a glowing route snippet over an optional real 2D map cutout, the headline distance
 * and the key ride statistics.
 *
 * Drawn directly onto an off-screen [Bitmap] with the platform [Canvas] (no Compose
 * lifecycle, no charting dependency) so it can be produced on a background thread
 * and is fully deterministic / reproducible.
 *
 * @param theme the colour theme selected in the share dialog.
 * @param mapLayer optional real map snapshot drawn behind the route; when `null`
 *  (e.g. offline or the snapshot failed) a clean translucent panel is used instead.
 */
internal fun renderRideShareCard(
    ride: RecordedRide,
    dateLabel: String,
    labels: RideShareLabels,
    theme: RideShareTheme = RideShareThemes.default,
    mapLayer: RideMapLayer? = null
): Bitmap {
    val w = CARD_WIDTH
    val h = CARD_HEIGHT
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Plain rectangular tile — no rounded corners. Social apps crop/round the
    // shared image themselves, so baking in rounded (transparent) corners only
    // looked odd (visible transparent notches on some backgrounds).
    drawBackground(canvas, w, h, theme)
    drawBrandRow(canvas, w, dateLabel, theme)
    drawRouteCard(canvas, ride, theme, mapLayer)
    drawHeadlineDistance(canvas, ride, labels.headline, theme)
    drawStatsRow(canvas, ride, w, labels)
    drawFooter(canvas, w, h, labels.footer)

    return bitmap
}

// ── Background ──────────────────────────────────────────────────────────────

private fun drawBackground(canvas: Canvas, w: Int, h: Int, theme: RideShareTheme) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(theme.gradientTop, theme.gradientMid, theme.gradientBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    // Soft vignette glow in the upper-right for a bit of depth.
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            w * 0.85f, h * 0.12f, w * 0.7f,
            0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
}

// ── Brand row ───────────────────────────────────────────────────────────────

private fun drawBrandRow(canvas: Canvas, w: Int, dateLabel: String, theme: RideShareTheme) {
    val y = 138f
    // Accent dot.
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
    canvas.drawCircle(MARGIN + 14f, y - 14f, 16f, dot)

    val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 46f
        letterSpacing = 0.22f
    }
    canvas.drawText("VELOSPOT", MARGIN + 48f, y, brand)

    val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 34f
        textAlign = Paint.Align.RIGHT
    }
    canvas.drawText(dateLabel, w - MARGIN, y, date)
}

// ── Route snippet ───────────────────────────────────────────────────────────

private fun drawRouteCard(canvas: Canvas, ride: RecordedRide, theme: RideShareTheme, mapLayer: RideMapLayer?) {
    val left = PANEL_LEFT
    val right = PANEL_RIGHT
    val top = PANEL_TOP
    val bottom = PANEL_BOTTOM
    val rect = RectF(left, top, right, bottom)
    val radius = 48f
    val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

    // ── Panel background: a real 2D map cutout when available, else a clean panel ──
    if (mapLayer != null && mapLayer.mapBitmap.width > 0) {
        canvas.save()
        canvas.clipPath(clip)
        val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(mapLayer.mapBitmap, null, rect, mapPaint)
        // Brand-tinted scrim so the map blends with the card and the route pops.
        // A vertical theme gradient + a gentle darkening keep the bright route and
        // markers readable on top of busy, light map tiles.
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left, top, left, bottom,
                intArrayOf(withAlpha(theme.gradientTop, 0x70), withAlpha(theme.gradientBottom, 0xA6)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, scrim)
        // A subtle overall darken unifies the map's contrast with the route halo.
        canvas.drawColor(0x1A0B1020)
        canvas.restore()
    } else {
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        canvas.drawRoundRect(rect, radius, radius, panel)
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x33FFFFFF
    }
    canvas.drawRoundRect(rect, radius, radius, border)

    val points = ride.points
    if (points.size < 2) return

    // ── Project the track into panel pixels ──────────────────────────────────
    val projected: List<PointF> = if (mapLayer != null && mapLayer.routePointsImagePx.size >= 2) {
        // Reuse MapLibre's own projection: scale the snapshot-pixel coordinates into
        // the panel rect so the polyline aligns perfectly with the map underneath.
        val sx = (right - left) / mapLayer.mapBitmap.width
        val sy = (bottom - top) / mapLayer.mapBitmap.height
        mapLayer.routePointsImagePx.map { PointF(left + it.x * sx, top + it.y * sy) }
    } else {
        // Fallback: equirectangular projection fitted into the panel.
        val pad = 70f
        val regionLeft = left + pad
        val regionRight = right - pad
        val regionTop = top + pad
        val regionBottom = bottom - pad
        val regionW = regionRight - regionLeft
        val regionH = regionBottom - regionTop

        val lats = points.map { it.latitude }
        val lons = points.map { it.longitude }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()
        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0
        val cosLat = cos(Math.toRadians(centerLat))
        val geoW = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-9)
        val geoH = (maxLat - minLat).coerceAtLeast(1e-9)
        val scale = minOf(regionW / geoW, regionH / geoH)
        val regionCenterX = (regionLeft + regionRight) / 2f
        val regionCenterY = (regionTop + regionBottom) / 2f
        points.map {
            PointF(
                regionCenterX + ((it.longitude - centerLon) * cosLat * scale).toFloat(),
                regionCenterY - ((it.latitude - centerLat) * scale).toFloat()
            )
        }
    }

    // ── Route polyline (clipped to the rounded panel) ────────────────────────
    canvas.save()
    canvas.clipPath(clip)

    val path = Path().apply {
        moveTo(projected.first().x, projected.first().y)
        for (i in 1 until projected.size) lineTo(projected[i].x, projected[i].y)
    }

    // 1) Dark contrast halo — keeps the line legible on light AND dark areas of the
    //    map so the route and basemap finally harmonise on every background.
    val contrast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x592A0E45.toInt()
        strokeWidth = 34f
    }
    canvas.drawPath(path, contrast)

    // 2) Coloured glow in the theme's route colour for a vivid, cohesive look.
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = withAlpha(theme.routeColor, 0x73)
        strokeWidth = 24f
    }
    canvas.drawPath(path, glow)

    // 3) Bright core line.
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.routeColor
        strokeWidth = 12f
    }
    canvas.drawPath(path, line)

    // Start / end markers.
    val start = projected.first()
    val end = projected.last()
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.startDot }
    val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.endDot }
    canvas.drawCircle(start.x, start.y, 20f, haloPaint)
    canvas.drawCircle(start.x, start.y, 13f, startPaint)
    canvas.drawCircle(end.x, end.y, 20f, haloPaint)
    canvas.drawCircle(end.x, end.y, 13f, endPaint)

    canvas.restore()

    // ── Map attribution (required for OSM/OpenFreeMap basemap) ────────────────
    if (mapLayer != null && mapLayer.mapBitmap.width > 0) {
        val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            typeface = Typeface.SANS_SERIF
            textSize = 22f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        canvas.drawText("© OpenStreetMap contributors", right - 24f, bottom - 22f, attribution)
    }
}

// ── Headline distance ───────────────────────────────────────────────────────

private fun drawHeadlineDistance(canvas: Canvas, ride: RecordedRide, headline: String, theme: RideShareTheme) {
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accent
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 36f
        letterSpacing = 0.18f
    }
    canvas.drawText(headline.uppercase(), MARGIN, 910f, label)

    val (number, unit) = headlineDistanceParts(ride.distanceMeters)

    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 168f
    }
    val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE_70
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 64f
    }
    val baseline = 1040f
    canvas.drawText(number, MARGIN, baseline, numberPaint)
    val numberWidth = numberPaint.measureText(number)
    canvas.drawText(unit, MARGIN + numberWidth + 20f, baseline, unitPaint)
}

// ── Stats row ───────────────────────────────────────────────────────────────

private fun drawStatsRow(canvas: Canvas, ride: RecordedRide, w: Int, labels: RideShareLabels) {
    val stats = listOf(
        labels.durationLabel to formatRideDuration(ride.elapsedSeconds),
        labels.avgSpeedLabel to formatRideSpeed(ride.avgSpeedMps),
        labels.elevationLabel to "↑ " + formatRideElevation(ride.elevationGainMeters),
        labels.maxSpeedLabel to formatRideSpeed(ride.maxSpeedMps)
    )

    val areaLeft = MARGIN
    val areaRight = w - MARGIN
    val colWidth = (areaRight - areaLeft) / stats.size
    val valueY = 1200f
    val labelY = 1248f

    // Divider above the stats.
    val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 2f
    }
    canvas.drawLine(MARGIN, 1108f, w - MARGIN, 1108f, divider)

    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 46f
        textAlign = Paint.Align.CENTER
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    stats.forEachIndexed { i, (label, value) ->
        val cx = areaLeft + colWidth * i + colWidth / 2f
        canvas.drawText(value, cx, valueY, valuePaint)
        canvas.drawText(label, cx, labelY, labelPaint)
    }
}

// ── Footer ──────────────────────────────────────────────────────────────────

private fun drawFooter(canvas: Canvas, w: Int, h: Int, footer: String) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 30f
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    canvas.drawText(footer, w / 2f, h - 70f, paint)
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Splits the headline distance into a big number and its unit, e.g. `12.34` + `km`. */
private fun headlineDistanceParts(meters: Double): Pair<String, String> =
    if (meters < 1_000) meters.roundToInt().toString() to "m"
    else "%.2f".format(meters / 1_000.0) to "km"

/** Returns [color] with its alpha channel replaced by [alpha] (0..255). */
private fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (alpha shl 24)

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1350
private const val MARGIN = 80f

// Route/map panel rectangle on the card.
private const val PANEL_LEFT = MARGIN
private const val PANEL_TOP = 210f
private const val PANEL_RIGHT = CARD_WIDTH - MARGIN
private const val PANEL_BOTTOM = 820f

private const val WHITE = 0xFFFFFFFF.toInt()
private const val WHITE_70 = 0xB3FFFFFF.toInt()




