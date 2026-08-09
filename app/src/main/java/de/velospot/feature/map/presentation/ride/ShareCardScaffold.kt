package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The shared *frame* for every VeloSpot share card (per-ride card, all-time stats
 * card, and — transitively — the Wrapped closing slide). It owns the common chrome
 * so each card only supplies its own distinct content:
 *
 * - the 1080×1350 (4:5) off-screen [Bitmap] / [Canvas] creation,
 * - the theme-driven background gradient (+ upper-right vignette glow),
 * - the brand/logo row ("VELOSPOT" + accent dot, with a per-card trailing label),
 * - the reusable translucent "glass" panel primitive(s),
 * - the centred footer line, and
 * - the shared colour / margin constants and paint helpers.
 *
 * Everything is drawn on the platform [Canvas] with no Compose lifecycle so cards
 * can be produced off the main thread and are fully deterministic / reproducible,
 * ready to hand straight to [de.velospot.core.share.ImageSharer.shareBitmap].
 *
 * This is a shared *frame*, not a rigid template: the per-card content lambda draws
 * whatever it likes into the prepared canvas via [ShareCardCanvas], so the two very
 * different card layouts stay pixel-for-pixel unchanged.
 */

/** Logical card size — a bold, vertical (4:5) social-media tile. */
internal const val SHARE_CARD_WIDTH = 1080
internal const val SHARE_CARD_HEIGHT = 1350

/** Shared outer margin used by every card element. */
internal const val SHARE_CARD_MARGIN = 80f

internal const val SHARE_CARD_WHITE = 0xFFFFFFFF.toInt()
internal const val SHARE_CARD_WHITE_70 = 0xB3FFFFFF.toInt()

/** Fill / border colours of the reusable "glass" panels. */
private const val GLASS_FILL = 0x1FFFFFFF
private const val GLASS_BORDER = 0x33FFFFFF

/**
 * The per-card trailing label drawn on the right of the brand row — a ride's date or
 * an all-time period. Its typography differs slightly per card, so it is captured
 * here to keep both cards byte-identical to the pre-refactor output.
 *
 * @property text the pre-formatted, localised trailing text.
 * @property textSize text size in px.
 * @property bold whether the text is drawn bold.
 * @property letterSpacing tracking (em) applied to the text.
 */
internal data class ShareCardBrandTrailing(
    val text: String,
    val textSize: Float,
    val bold: Boolean,
    val letterSpacing: Float
)

/**
 * A thin drawing seam handed to each card's content lambda. It exposes the prepared
 * [canvas] (with the background + brand row already drawn), the card [width]/[height]
 * and [theme], plus the shared glass-panel primitives so per-card content stays free
 * of any duplicated chrome logic.
 */
internal class ShareCardCanvas(
    val canvas: Canvas,
    val width: Int,
    val height: Int,
    val theme: RideShareTheme
) {
    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_FILL }
    private val glassBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = GLASS_BORDER
    }

    /** Draws a full "glass" panel: the translucent fill plus its hairline border. */
    fun glassPanel(rect: RectF, radius: Float) {
        canvas.drawRoundRect(rect, radius, radius, glassFillPaint)
        canvas.drawRoundRect(rect, radius, radius, glassBorderPaint)
    }

    /**
     * Draws only the "glass" panel border — used when the fill is replaced by other
     * content (e.g. a real map cutout behind the route) yet the same hairline frame
     * should still sit on top.
     */
    fun glassPanelBorder(rect: RectF, radius: Float) {
        canvas.drawRoundRect(rect, radius, radius, glassBorderPaint)
    }
}

/** Returns [color] with its alpha channel replaced by [alpha] (0..255). */
internal fun shareCardWithAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * Renders a finished share-card [Bitmap]: creates the canvas, paints the shared
 * chrome (background gradient, brand row, footer), then invokes [content] to draw
 * the card's own distinct body in between.
 *
 * @param theme the colour theme selected in the share dialog.
 * @param trailing the brand-row trailing label (ride date / stats period).
 * @param footer the centred footer line ("Recorded with VeloSpot").
 * @param content draws the card-specific body into the prepared [ShareCardCanvas];
 *  invoked after the brand row and before the footer, matching each card's original
 *  draw order.
 */
internal fun renderShareCard(
    theme: RideShareTheme,
    trailing: ShareCardBrandTrailing,
    footer: String,
    content: ShareCardCanvas.() -> Unit
): Bitmap {
    val w = SHARE_CARD_WIDTH
    val h = SHARE_CARD_HEIGHT
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    drawBackground(canvas, w, h, theme)
    drawBrandRow(canvas, w, trailing, theme)
    ShareCardCanvas(canvas, w, h, theme).content()
    drawFooter(canvas, w, h, footer)

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
        shader = RadialGradient(
            w * 0.85f, h * 0.12f, w * 0.7f,
            0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
}

// ── Brand row ───────────────────────────────────────────────────────────────

private fun drawBrandRow(canvas: Canvas, w: Int, trailing: ShareCardBrandTrailing, theme: RideShareTheme) {
    val y = 138f
    // Accent dot.
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
    canvas.drawCircle(SHARE_CARD_MARGIN + 14f, y - 14f, 16f, dot)

    val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHARE_CARD_WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 46f
        letterSpacing = 0.22f
    }
    canvas.drawText("VELOSPOT", SHARE_CARD_MARGIN + 48f, y, brand)

    val trailingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHARE_CARD_WHITE_70
        typeface = if (trailing.bold) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        } else {
            Typeface.SANS_SERIF
        }
        textSize = trailing.textSize
        letterSpacing = trailing.letterSpacing
        textAlign = Paint.Align.RIGHT
    }
    canvas.drawText(trailing.text, w - SHARE_CARD_MARGIN, y, trailingPaint)
}

// ── Footer ──────────────────────────────────────────────────────────────────

private fun drawFooter(canvas: Canvas, w: Int, h: Int, footer: String) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHARE_CARD_WHITE_70
        typeface = Typeface.SANS_SERIF
        textSize = 30f
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    canvas.drawText(footer, w / 2f, h - 70f, paint)
}

