package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * A single "glass" stat cell on the bike Sharepic: an emoji glyph, a big bold value
 * and a small caption. All strings are pre-formatted by the caller so the renderer
 * stays free of Android resource lookups (mirrors [StatsShareCell]).
 */
internal data class BikeShareCell(
    val emoji: String,
    val value: String,
    val label: String
)

/**
 * Localised / pre-formatted text for the shareable bike "Sharepic". Passed in from
 * the composable so the renderer itself stays free of any Android resource lookups
 * (and its layout logic can be reasoned about in isolation).
 *
 * @property bikeName the bike's rider-facing name (the hero title).
 * @property subtitle the brand / model / type line under the name (may be blank).
 * @property cells the meaningful stat-grid cells (already filtered & ordered; zero /
 *  empty stats are dropped by the caller so only sensible cells are drawn).
 * @property periodLabel the top-right brand-row trailing, e.g. "SINCE JUN 2024" or a
 *  fallback like the bike type when there are no rides yet.
 * @property footer the centred footer line ("Recorded with VeloSpot").
 */
internal data class BikeShareLabels(
    val bikeName: String,
    val subtitle: String,
    val cells: List<BikeShareCell>,
    val periodLabel: String,
    val footer: String
)

/**
 * Renders a shareable bike "Sharepic" — a bold, vertical (4:5) social tile that puts
 * the rider's uploaded bike [photo] front and centre, with the bike's name /
 * brand-model line and a tidy grid of meaningful ride stats (total distance, ride
 * count, elevation, moving time, longest ride, top speed). It reuses the shared
 * [renderShareCard] chrome (gradient, brand row, glass panels, footer) so it matches
 * the per-ride and all-time cards pixel-for-pixel.
 *
 * When [photo] is `null` a themed placeholder with a bike glyph is drawn instead, so
 * a bike without an uploaded photo still produces a tidy card.
 *
 * Drawn on an off-screen [Bitmap] via the platform [Canvas] with no Compose lifecycle
 * so it can be produced on a background thread and is fully deterministic.
 */
internal fun renderBikeShareCard(
    photo: Bitmap?,
    labels: BikeShareLabels,
    theme: RideShareTheme = RideShareThemes.default
): Bitmap = renderShareCard(
    theme = theme,
    trailing = ShareCardBrandTrailing(text = labels.periodLabel, textSize = 32f, bold = true, letterSpacing = 0.14f),
    footer = labels.footer
) {
    drawBikePhoto(this, photo, theme)
    drawBikeTitle(canvas, labels, theme)
    drawBikeGrid(this, labels.cells)
}

// ── Bike photo panel ──────────────────────────────────────────────────────────

private fun drawBikePhoto(scaffold: ShareCardCanvas, photo: Bitmap?, theme: RideShareTheme) {
    val canvas = scaffold.canvas
    val rect = RectF(BIKE_PANEL_LEFT, BIKE_PANEL_TOP, BIKE_PANEL_RIGHT, BIKE_PANEL_BOTTOM)
    val radius = 48f
    val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

    if (photo != null && photo.width > 0 && photo.height > 0) {
        canvas.save()
        canvas.clipPath(clip)
        // Centre-crop the photo so it fills the panel without distortion.
        val src = centerCropSrc(photo.width, photo.height, rect.width(), rect.height())
        canvas.drawBitmap(photo, src, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // Gentle bottom scrim so the panel blends into the card.
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(0x00000000, shareCardWithAlpha(theme.gradientBottom, 0x59)),
                floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, scrim)
        canvas.restore()
        scaffold.glassPanelBorder(rect, radius)
    } else {
        // No photo: themed placeholder with a centred bike glyph.
        scaffold.glassPanel(rect, radius)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 200f
            textAlign = Paint.Align.CENTER
        }
        val fm = glyph.fontMetrics
        canvas.drawText("🚲", rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, glyph)
    }
}

// ── Bike title (name + brand/model/type) ───────────────────────────────────────

private fun drawBikeTitle(canvas: Canvas, labels: BikeShareLabels, theme: RideShareTheme) {
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BIKE_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 68f
    }
    // Auto-fit the name so long bike names never spill past the margin.
    val available = SHARE_CARD_WIDTH - BIKE_MARGIN * 2f
    while (namePaint.textSize > 40f && namePaint.measureText(labels.bikeName) > available) {
        namePaint.textSize -= 4f
    }
    canvas.drawText(labels.bikeName, BIKE_MARGIN, 728f, namePaint)

    if (labels.subtitle.isNotBlank()) {
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accent
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 34f
            letterSpacing = 0.06f
        }
        canvas.drawText(labels.subtitle, BIKE_MARGIN, 776f, subtitle)
    }
}

// ── Stat grid (2 columns) ──────────────────────────────────────────────────────

private fun drawBikeGrid(scaffold: ShareCardCanvas, cells: List<BikeShareCell>) {
    if (cells.isEmpty()) return

    val canvas = scaffold.canvas
    val gap = 24f
    val areaLeft = BIKE_MARGIN
    val areaRight = SHARE_CARD_WIDTH - BIKE_MARGIN
    val cellWidth = (areaRight - areaLeft - gap * (BIKE_GRID_COLUMNS - 1)) / BIKE_GRID_COLUMNS
    val radius = 34f

    val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = 40f
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BIKE_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 44f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BIKE_WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 24f
    }

    cells.forEachIndexed { i, cell ->
        val col = i % BIKE_GRID_COLUMNS
        val row = i / BIKE_GRID_COLUMNS
        val left = areaLeft + col * (cellWidth + gap)
        val top = BIKE_GRID_TOP + row * (BIKE_CELL_HEIGHT + BIKE_GRID_VGAP)
        val rect = RectF(left, top, left + cellWidth, top + BIKE_CELL_HEIGHT)
        scaffold.glassPanel(rect, radius)

        // Three stacked baselines with clear separation so the big value touches
        // neither the emoji above it nor the caption below it (emoji ≈ top+42,
        // value ≈ top+90, label ≈ top+122 inside a 132-tall cell).
        val padX = 30f
        canvas.drawText(cell.emoji, left + padX, top + 42f, emojiPaint)
        valuePaint.textSize = 44f
        val maxValueWidth = cellWidth - padX * 2f
        while (valuePaint.textSize > 26f && valuePaint.measureText(cell.value) > maxValueWidth) {
            valuePaint.textSize -= 3f
        }
        canvas.drawText(cell.value, left + padX, top + 90f, valuePaint)
        canvas.drawText(cell.label, left + padX, top + 122f, labelPaint)
    }
}

/**
 * The y (px) of the *bottom* edge of the last stat-grid row for [cellCount] cells,
 * or [BIKE_GRID_TOP] when there are none. Package-visible so the (pure) footer /
 * grid separation maths can be asserted on the JVM — the grid must always end well
 * above [shareCardFooterTop] so the footer never overlaps the bottom stats row.
 */
internal fun bikeGridBottom(cellCount: Int): Float {
    if (cellCount <= 0) return BIKE_GRID_TOP
    val rows = (cellCount + BIKE_GRID_COLUMNS - 1) / BIKE_GRID_COLUMNS
    return BIKE_GRID_TOP + rows * BIKE_CELL_HEIGHT + (rows - 1) * BIKE_GRID_VGAP
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Returns the centre-crop source [Rect] of a [srcW]×[srcH] bitmap that fills a
 * [dstW]×[dstH] target while preserving aspect ratio (the standard "cover" fit).
 * Package-visible so the (pure) crop maths can be unit-tested on the JVM.
 */
internal fun centerCropSrc(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Rect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0f || dstH <= 0f) return Rect(0, 0, srcW, srcH)
    val srcAspect = srcW.toFloat() / srcH
    val dstAspect = dstW / dstH
    return if (srcAspect > dstAspect) {
        // Source is wider: crop the left/right.
        val cropW = (srcH * dstAspect).toInt().coerceIn(1, srcW)
        val x = (srcW - cropW) / 2
        Rect(x, 0, x + cropW, srcH)
    } else {
        // Source is taller: crop the top/bottom.
        val cropH = (srcW / dstAspect).toInt().coerceIn(1, srcH)
        val y = (srcH - cropH) / 2
        Rect(0, y, srcW, y + cropH)
    }
}

// Shared card constants live in ShareCardScaffold; aliased here for readability.
private const val BIKE_MARGIN = SHARE_CARD_MARGIN
private const val BIKE_WHITE = SHARE_CARD_WHITE
private const val BIKE_WHITE_70 = SHARE_CARD_WHITE_70

// Photo panel rectangle on the card.
private const val BIKE_PANEL_LEFT = BIKE_MARGIN
private const val BIKE_PANEL_TOP = 210f
private const val BIKE_PANEL_RIGHT = SHARE_CARD_WIDTH - BIKE_MARGIN
private const val BIKE_PANEL_BOTTOM = 640f

// ── Stat-grid geometry ────────────────────────────────────────────────────────
// The grid is 2 columns × up to 3 rows. Each cell is tall enough to stack the emoji,
// the big value and the caption without them overlapping, while the tallest 6-cell
// (3-row) grid still ends with ≥40 px clearance above the shared footer: with 6 cells
// the last row bottoms out at 792 + 3*132 + 2*10 = 1208 px, i.e. 42 px above the
// footer's visual top (~1250 px = 1350 − 70 − 30).
private const val BIKE_GRID_COLUMNS = 2
private const val BIKE_GRID_TOP = 792f
private const val BIKE_CELL_HEIGHT = 132f
private const val BIKE_GRID_VGAP = 10f

