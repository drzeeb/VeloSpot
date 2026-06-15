package de.velospot.feature.map.presentation.markers

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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import de.velospot.R
import kotlin.math.roundToInt

/**
 * Pure marker-icon drawing logic — turns app state into [Drawable] / [Bitmap]
 * pin graphics. Has no knowledge of MapLibre; layer/source/image registration
 * lives in `MapStyleLayers.kt`, orchestration in `MapMarkerRenderer.kt`.
 */

internal fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bmp
}

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
    val circleCenterY = 52f * scale
    canvas.drawCircle(width / 2f, circleCenterY, 44f * scale, pinPaint)
    canvas.drawPath(Path().apply {
        moveTo(width / 2f, 140f * scale); lineTo(30f * scale, 78f * scale); lineTo(90f * scale, 78f * scale); close()
    }, pinPaint)

    // Crisp white vector bike glyph (replaces the previous colour emoji, whose
    // tint could not be controlled and washed out on lighter pin colours).
    val bikeDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_bike_marker)
        ?.mutate()
    if (bikeDrawable != null) {
        DrawableCompat.setTint(bikeDrawable, Color.WHITE)
        val iconSize = (62f * scale).roundToInt()
        val left = ((width - iconSize) / 2f).roundToInt()
        val top  = (circleCenterY - iconSize / 2f).roundToInt()
        bikeDrawable.setBounds(left, top, left + iconSize, top + iconSize)
        bikeDrawable.draw(canvas)
    }

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
internal fun createSearchPinIcon(): Drawable {
    val width  = 72
    val height = 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Shadow
    canvas.drawCircle(width / 2f + 3f, 76f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    })

    // Pin body (red circle)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E53935".toColorInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(width / 2f, 32f, 28f, pinPaint)

    // Pin tip
    canvas.drawPath(Path().apply {
        moveTo(width / 2f, 90f)
        lineTo(width / 2f - 16f, 50f)
        lineTo(width / 2f + 16f, 50f)
        close()
    }, pinPaint)

    // White inner circle
    canvas.drawCircle(width / 2f, 32f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    })

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
    canvas.drawPath(Path().apply {
        moveTo(width / 2f, 90f)
        lineTo(width / 2f - 16f, 50f)
        lineTo(width / 2f + 16f, 50f)
        close()
    }, pinPaint)

    // White inner circle
    canvas.drawCircle(width / 2f, 32f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    })

    return BitmapDrawable(null, bitmap)
}

/**
 * A green dropped-pin icon with a white star, used for saved places
 * (custom pins the user stored as named favourites). Visually distinct from the
 * transient blue custom pin and the red address-search pin.
 */
internal fun createSavedPlaceIcon(): Drawable {
    val width  = 76
    val height = 100
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Shadow
    canvas.drawCircle(width / 2f + 3f, 78f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    })

    // Pin body (green circle)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#2E7D32".toColorInt()
        style = Paint.Style.FILL
    }
    val cx = width / 2f
    val cy = 33f
    canvas.drawCircle(cx, cy, 29f, pinPaint)

    // Pin tip
    canvas.drawPath(Path().apply {
        moveTo(cx, 94f)
        lineTo(cx - 17f, 52f)
        lineTo(cx + 17f, 52f)
        close()
    }, pinPaint)

    // White star
    canvas.drawPath(
        starPath(centerX = cx, centerY = cy, outerRadius = 15f, innerRadius = 6.2f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    )

    return BitmapDrawable(null, bitmap)
}

/** Builds a five-pointed star [Path] centred at ([centerX], [centerY]). */
private fun starPath(centerX: Float, centerY: Float, outerRadius: Float, innerRadius: Float): Path {
    val path = Path()
    val points = 5
    val angleStep = Math.PI / points
    // Start at the top point (-90°).
    var angle = -Math.PI / 2
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (r * Math.cos(angle)).toFloat()
        val y = centerY + (r * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        angle += angleStep
    }
    path.close()
    return path
}

