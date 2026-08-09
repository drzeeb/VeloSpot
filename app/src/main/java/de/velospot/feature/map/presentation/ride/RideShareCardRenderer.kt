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
    val footer: String,
    /**
     * Optional, pre-formatted weather chip for the ride. Only supplied when the
     * opt-in weather feature is enabled AND the ride carries a stored snapshot;
     * when `null` no weather element is drawn and the card is byte-identical to
     * one rendered without weather.
     */
    val weather: RideShareWeatherLabel? = null
)

/**
 * The optional weather element for the share card, shown right next to the headline
 * distance: a flat weather icon and the temperature (with the wind on a second line).
 * All are supplied by the composable (mirroring the detail & analysis views) so the
 * renderer stays free of Android resources.
 *
 * @property icon a pre-rasterised, flat weather icon bitmap (the same Material
 *  `WeatherCondition.icon` used by the map chip, tinted white) sized ready to draw.
 * @property temperature e.g. `12 °C`.
 * @property wind optional pre-formatted wind text shown below the temperature, e.g.
 *  `Wind 15 km/h`; omitted when the ride has no wind reading.
 */
internal data class RideShareWeatherLabel(
    val icon: Bitmap,
    val temperature: String,
    val wind: String? = null
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
): Bitmap = renderShareCard(
    theme = theme,
    // The per-ride card shows the ride date on the right of the brand row.
    trailing = ShareCardBrandTrailing(text = dateLabel, textSize = 34f, bold = false, letterSpacing = 0f),
    footer = labels.footer
) {
    // Plain rectangular tile — no rounded corners. Social apps crop/round the
    // shared image themselves, so baking in rounded (transparent) corners only
    // looked odd (visible transparent notches on some backgrounds).
    drawRouteCard(this, ride, theme, mapLayer)
    // The headline distance carries the optional weather symbol + temperature just
    // to its right; drawn only when a weather label was supplied, so the layout is
    // unchanged when it's absent.
    drawHeadlineDistance(canvas, ride, labels.headline, labels.weather, theme)
    drawStatsRow(canvas, ride, width, labels)
}

// ── Route snippet ───────────────────────────────────────────────────────────

private fun drawRouteCard(
    scaffold: ShareCardCanvas,
    ride: RecordedRide,
    theme: RideShareTheme,
    mapLayer: RideMapLayer?
) {
    val canvas = scaffold.canvas
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
                intArrayOf(shareCardWithAlpha(theme.gradientTop, 0x70), shareCardWithAlpha(theme.gradientBottom, 0xA6)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, scrim)
        // A subtle overall darken unifies the map's contrast with the route halo.
        canvas.drawColor(0x1A0B1020)
        canvas.restore()
        // The map replaces the glass fill, but the same hairline frame still sits on top.
        scaffold.glassPanelBorder(rect, radius)
    } else {
        scaffold.glassPanel(rect, radius)
    }

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
        color = shareCardWithAlpha(theme.routeColor, 0x73)
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

private fun drawHeadlineDistance(
    canvas: Canvas,
    ride: RecordedRide,
    headline: String,
    weather: RideShareWeatherLabel?,
    theme: RideShareTheme
) {
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
    val unitX = MARGIN + numberWidth + 20f
    canvas.drawText(unit, unitX, baseline, unitPaint)

    // Optional weather (symbol + temperature) right next to the distance, vertically
    // centred on the big number. Drawn only when a weather label was supplied.
    weather?.let { drawHeadlineWeather(canvas, it, unitX + unitPaint.measureText(unit)) }
}

/**
 * Draws the ride's weather — a flat icon plus the temperature (and, when present,
 * the wind on a second line) — starting at [startX], right of the headline distance
 * and vertically centred on the big number.
 */
private fun drawHeadlineWeather(canvas: Canvas, weather: RideShareWeatherLabel, startX: Float) {
    // Optical centre of the 168px headline number (baseline 1040): cap-height top
    // ≈ 922, so its middle sits around y ≈ 980.
    val centerY = 980f
    val x = startX + 56f

    val iconSize = 84f
    val iconRect = RectF(x, centerY - iconSize / 2f, x + iconSize, centerY + iconSize / 2f)
    canvas.drawBitmap(weather.icon, null, iconRect, Paint(Paint.FILTER_BITMAP_FLAG))

    val textX = x + iconSize + 20f

    val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 64f
    }

    if (weather.wind == null) {
        // Temperature only — vertically centred on the number.
        val fm = tempPaint.fontMetrics
        canvas.drawText(weather.temperature, textX, centerY - (fm.ascent + fm.descent) / 2f, tempPaint)
        return
    }

    // Temperature on top, wind underneath — the two-line block centred on the number.
    val windPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 34f
    }
    val tempBaseline = centerY - 8f
    val windBaseline = centerY + 46f
    canvas.drawText(weather.temperature, textX, tempBaseline, tempPaint)
    canvas.drawText(weather.wind, textX, windBaseline, windPaint)
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

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Splits the headline distance into a big number and its unit, e.g. `12.34` + `km`. */
private fun headlineDistanceParts(meters: Double): Pair<String, String> =
    if (meters < 1_000) meters.roundToInt().toString() to "m"
    else "%.2f".format(meters / 1_000.0) to "km"

// Shared card constants live in ShareCardScaffold; aliased here so the ride-specific
// panel geometry stays readable while keeping a single source of truth.
private const val MARGIN = SHARE_CARD_MARGIN
private const val WHITE = SHARE_CARD_WHITE
private const val WHITE_70 = SHARE_CARD_WHITE_70

// Route/map panel rectangle on the card.
private const val PANEL_LEFT = MARGIN
private const val PANEL_TOP = 210f
private const val PANEL_RIGHT = SHARE_CARD_WIDTH - MARGIN
private const val PANEL_BOTTOM = 820f





