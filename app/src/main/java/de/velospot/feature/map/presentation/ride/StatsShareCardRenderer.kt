package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import de.velospot.core.stats.RideStatistics
import kotlin.math.roundToInt

/**
 * A single "glass" stat cell on the all-time statistics share card: an emoji glyph,
 * a big bold value and a small caption. All strings are pre-formatted by the caller
 * so the renderer stays free of Android resource lookups.
 */
internal data class StatsShareCell(
    val emoji: String,
    val value: String,
    val label: String
)

/** A "bro flex" pill badge (emoji + pre-formatted text) shown below the stat grid. */
internal data class StatsShareBadge(
    val emoji: String,
    val text: String
)

/**
 * Localised / pre-formatted text shown on the shareable all-time statistics card.
 * Passed in from the composable so the renderer itself stays free of any Android
 * resource lookups (and its layout logic can be unit-tested on the JVM).
 *
 * @property headline the small accent hero label, e.g. "TOTAL DISTANCE".
 * @property subtitle the hero subline, e.g. "128 rides · 96 active days".
 * @property cells the six stat-grid cells (order is preserved).
 * @property badges the qualifying flex badges, already filtered & pre-formatted.
 * @property footer the centred footer line ("Recorded with VeloSpot").
 */
internal data class StatsShareLabels(
    val headline: String,
    val subtitle: String,
    val cells: List<StatsShareCell>,
    val badges: List<StatsShareBadge>,
    val footer: String
)

/**
 * Renders a "VeloSpot Wrapped — All-time"-style shareable card summarising the
 * rider's whole history — a bold, vertical (4:5) social tile with the same vibrant
 * gradient, brand row and glass panels as the per-ride [renderRideShareCard], but
 * built around a huge total-distance hero, a 2×3 stat grid and a row of flex badges.
 *
 * Drawn directly onto an off-screen [Bitmap] with the platform [Canvas] so it can be
 * produced on a background thread and is fully deterministic / reproducible.
 *
 * @param stats the aggregate statistics (only numeric fields are read here; every
 *  piece of text is supplied via [labels] / [periodLabel]).
 * @param labels the pre-formatted, localised card text.
 * @param periodLabel the top-right period, e.g. "SINCE JUN 2024" or "ALL-TIME".
 * @param theme the colour theme selected in the share dialog.
 */
internal fun renderStatsShareCard(
    stats: RideStatistics,
    labels: StatsShareLabels,
    periodLabel: String,
    theme: RideShareTheme = RideShareThemes.default
): Bitmap = renderShareCard(
    theme = theme,
    // The all-time card shows the period (e.g. "ALL-TIME") on the right of the brand row.
    trailing = ShareCardBrandTrailing(text = periodLabel, textSize = 32f, bold = true, letterSpacing = 0.14f),
    footer = labels.footer
) {
    drawStatsHero(canvas, stats.totalDistanceMeters, labels, theme, width)
    drawStatsGrid(this, labels.cells, width)
    drawStatsBadges(canvas, labels.badges, theme, width)
}

// ── Hero (total distance) ─────────────────────────────────────────────────────

private fun drawStatsHero(
    canvas: Canvas,
    totalDistanceMeters: Double,
    labels: StatsShareLabels,
    theme: RideShareTheme,
    w: Int
) {
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accent
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 36f
        letterSpacing = 0.18f
    }
    canvas.drawText(labels.headline.uppercase(), STATS_MARGIN, 300f, label)

    val (number, unit) = statsHeadlineDistanceParts(totalDistanceMeters)

    // Auto-fit the big number so long all-time totals never spill past the margin.
    val available = w - STATS_MARGIN * 2f
    val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE_70
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 64f
    }
    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 168f
    }
    while (
        numberPaint.textSize > 96f &&
        numberPaint.measureText(number) + 20f + unitPaint.measureText(unit) > available
    ) {
        numberPaint.textSize -= 6f
    }

    val baseline = 440f
    canvas.drawText(number, STATS_MARGIN, baseline, numberPaint)
    val numberWidth = numberPaint.measureText(number)
    canvas.drawText(unit, STATS_MARGIN + numberWidth + 20f, baseline, unitPaint)

    val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 34f
    }
    canvas.drawText(labels.subtitle, STATS_MARGIN, 500f, subtitle)
}

// ── 2×3 stat grid ─────────────────────────────────────────────────────────────

private fun drawStatsGrid(scaffold: ShareCardCanvas, cells: List<StatsShareCell>, w: Int) {
    if (cells.isEmpty()) return

    val canvas = scaffold.canvas
    val columns = 2
    val gap = 24f
    val areaLeft = STATS_MARGIN
    val areaRight = w - STATS_MARGIN
    val cellWidth = (areaRight - areaLeft - gap * (columns - 1)) / columns
    val cellHeight = 172f
    val vGap = 22f
    val gridTop = 552f
    val radius = 36f

    val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = 50f
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 50f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 26f
    }

    cells.forEachIndexed { i, cell ->
        val col = i % columns
        val row = i / columns
        val left = areaLeft + col * (cellWidth + gap)
        val top = gridTop + row * (cellHeight + vGap)
        val rect = RectF(left, top, left + cellWidth, top + cellHeight)
        // Reuse the shared "glass" panel primitive (translucent fill + hairline border).
        scaffold.glassPanel(rect, radius)

        val padX = 32f
        canvas.drawText(cell.emoji, left + padX, top + 66f, emojiPaint)
        // Auto-fit the value so wide durations (e.g. "1000:00:00") still fit.
        valuePaint.textSize = 50f
        val maxValueWidth = cellWidth - padX * 2f
        while (valuePaint.textSize > 30f && valuePaint.measureText(cell.value) > maxValueWidth) {
            valuePaint.textSize -= 3f
        }
        canvas.drawText(cell.value, left + padX, top + 122f, valuePaint)
        canvas.drawText(cell.label, left + padX, top + 152f, labelPaint)
    }
}

// ── Flex badges ─────────────────────────────────────────────────────────────

private fun drawStatsBadges(
    canvas: Canvas,
    badges: List<StatsShareBadge>,
    theme: RideShareTheme,
    w: Int
) {
    if (badges.isEmpty()) return

    val y = 1180f
    val height = 68f
    val hPad = 30f
    val gap = 20f
    val radius = height / 2f

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STATS_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 30f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shareCardWithAlpha(theme.accent, 0x2E) }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = shareCardWithAlpha(theme.accent, 0x66)
    }

    val texts = badges.map { "${it.emoji}  ${it.text}" }
    val widths = texts.map { textPaint.measureText(it) + hPad * 2f }
    val totalWidth = widths.sum() + gap * (badges.size - 1)
    var x = (w - totalWidth) / 2f

    texts.forEachIndexed { i, text ->
        val pillWidth = widths[i]
        val rect = RectF(x, y, x + pillWidth, y + height)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
        // Vertically centre the text within the pill.
        val fm = textPaint.fontMetrics
        val textBaseline = y + height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, x + hPad, textBaseline, textPaint)
        x += pillWidth + gap
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Splits the total distance into a big number and its unit, e.g. `1 234.56` + `km`. */
private fun statsHeadlineDistanceParts(meters: Double): Pair<String, String> =
    if (meters < 1_000) meters.roundToInt().toString() to "m"
    else "%.2f".format(meters / 1_000.0) to "km"

// Shared card constants live in ShareCardScaffold; aliased here for readability.
private const val STATS_MARGIN = SHARE_CARD_MARGIN
private const val STATS_WHITE = SHARE_CARD_WHITE
private const val STATS_WHITE_70 = SHARE_CARD_WHITE_70

