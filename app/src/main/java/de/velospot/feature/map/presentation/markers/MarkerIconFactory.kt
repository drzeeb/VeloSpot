package de.velospot.feature.map.presentation.markers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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

/**
 * The live "my location" marker: a full-colour cyclist avatar
 * (`R.drawable.ic_cyclist_avatar`) rendered as a real little rider seen from
 * behind — no flat dot. A soft contact shadow underneath lifts it off the basemap
 * for a 3D feel. The art is a true 3rd-person **rear** view, so as the upright
 * billboard the [LAYER_LOCATION] layer draws it, it reads as a rider standing on
 * the map; the navigation camera keeps the heading pointing "up", so the rider
 * naturally faces the travel direction. [isNavigationActive] renders the avatar a
 * bit larger while navigating.
 *
 * [pedalPhase] (`0f..1f`) drives the **pedalling animation**: the legs, shoes and
 * pedals are drawn programmatically (not baked into the vector), so by rendering a
 * sequence of frames at advancing phases the rider appears to pedal. Phase `0`
 * is a neutral mid-stroke stance; the navigation frame loop cycles the phase in
 * step with the rider's ground speed.
 *
 * [idle] renders the **stopped** pose instead of the pedalling pose: the rider
 * plants one foot flat on the ground beside the bike (the other stays on the
 * raised pedal), exactly like a cyclist waiting at a standstill. It defaults to
 * `true` whenever the rider is not navigating, so the resting map marker looks
 * naturally parked rather than frozen mid-stroke.
 */
internal fun createLocationMarkerIcon(
    context: Context,
    isNavigationActive: Boolean,
    pedalPhase: Float = 0f,
    idle: Boolean = !isNavigationActive
): Drawable {
    val size = if (isNavigationActive) 208 else 148
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    // Soft contact shadow so the avatar reads as lifted off the map (3D feel).
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        maskFilter = BlurMaskFilter(size * 0.06f, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawOval(
        center - size * 0.24f, center + size * 0.16f,
        center + size * 0.24f, center + size * 0.42f,
        shadowPaint
    )

    // Full-colour 2D cyclist avatar (3rd-person view).
    val avatar = AppCompatResources.getDrawable(context, R.drawable.ic_cyclist_avatar)
    if (avatar != null) {
        // Render the avatar onto its own layer first so we can stamp a clean
        // white keyline around it (like a map sticker) for contrast on any
        // basemap, then composite the colour version on top.
        val pad = (size * 0.10f).roundToInt()
        val avatarBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val avatarCanvas = Canvas(avatarBmp)
        avatar.setBounds(pad, pad, size - pad, size - pad)
        avatar.draw(avatarCanvas)
        // Animated legs / shoes / pedals on top of the bike (and rider torso),
        // so the white keyline below wraps them together with the rest of the
        // sprite into one clean silhouette.
        drawCyclistLegs(avatarCanvas, size, pad, pedalPhase, idle)

        // Compose the rider + its white keyline onto a single layer so the whole
        // silhouette is one clean sticker over the contact shadow.
        val composed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val composedCanvas = Canvas(composed)
        val outline = size * 0.012f
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        var angle = 0.0
        while (angle < 2 * Math.PI) {
            composedCanvas.drawBitmap(
                avatarBmp,
                (Math.cos(angle) * outline).toFloat(),
                (Math.sin(angle) * outline).toFloat(),
                whitePaint
            )
            angle += Math.PI / 8
        }
        composedCanvas.drawBitmap(avatarBmp, 0f, 0f, null)

        // The avatar art is now a true 3rd-person *rear* view, so as an upright
        // billboard it already reads as a rider standing on the map seen from
        // behind — no perspective faking needed. Just composite it (with its white
        // keyline) over the contact shadow.
        canvas.drawBitmap(composed, 0f, 0f, null)
    }

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * Draws the rider's two legs, shoes and pedals onto the avatar bitmap at the given
 * crank [phase] (`0f..1f`). The feet oscillate forward/back along the bike's
 * longitudinal axis 180° out of phase (left vs right), mimicking a turning crank
 * seen from above, while the knees bow slightly outward for a believable bent-leg
 * pedalling pose. Coordinates are expressed in the avatar vector's 64×64 space and
 * mapped into the padded bitmap so they line up exactly with the drawn bike frame.
 *
 * When [idle] is `true` the pedalling pose is replaced by a **standstill** pose:
 * the left leg reaches down and out to plant a flat foot on the ground beside the
 * bike (a wider, flattened shoe sells the ground contact), while the right foot
 * rests on the raised pedal — the natural stance of a stopped cyclist.
 */
private fun drawCyclistLegs(canvas: Canvas, size: Int, pad: Int, phase: Float, idle: Boolean = false) {
    val scale = (size - 2 * pad) / 64f
    fun vx(x: Float) = pad + x * scale
    fun vy(y: Float) = pad + y * scale

    val a = phase * (2f * Math.PI.toFloat())
    val cos = Math.cos(a.toDouble()).toFloat()
    val amplitude = 3.5f          // forward/back foot travel (vector units)
    val crankY = 47f              // bottom-bracket height (beside the rear wheel)

    // Hips emerge from just under the torso (waist ≈ y 34), straddling the wheel.
    val hipLeftX = 29f;  val hipRightX = 35f;  val hipY = 34f

    val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#283593".toColorInt()                 // shorts / legs blue
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // A single quadratic curve hip → (knee) → foot, with the control point pushed
    // outward so the leg reads as bent at the knee.
    fun drawLeg(hipX: Float, footX: Float, footY: Float, kneeOutX: Float) {
        val kneeX = (hipX + footX) / 2f + kneeOutX
        val kneeY = (hipY + footY) / 2f
        canvas.drawPath(Path().apply {
            moveTo(vx(hipX), vy(hipY))
            quadTo(vx(kneeX), vy(kneeY), vx(footX), vy(footY))
        }, legPaint)
    }

    val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#212121".toColorInt() }
    fun drawShoe(footX: Float, footY: Float, w: Float, h: Float) {
        val cx = vx(footX); val cy = vy(footY)
        val halfW = w * scale / 2f; val halfH = h * scale / 2f
        canvas.drawRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, halfH, halfH, shoePaint)
    }

    if (idle) {
        // Standstill: left foot planted on the ground (down & out), right foot up
        // on the pedal. The planted foot sits below/outside the bike footprint.
        val groundFootX = 22f;   val groundFootY = 56f
        val pedalFootX = 37.5f;  val pedalFootY = 46f
        drawLeg(hipLeftX, groundFootX, groundFootY, kneeOutX = -2.6f)
        drawLeg(hipRightX, pedalFootX, pedalFootY, kneeOutX = 1.6f)
        // Planted shoe is flatter & wider so it reads as resting on the ground.
        drawShoe(groundFootX, groundFootY, w = 5.2f, h = 2.2f)
        drawShoe(pedalFootX, pedalFootY, w = 3.8f, h = 2.4f)
        return
    }

    val footLeftX = 26.5f; val footRightX = 37.5f
    val footLeftY  = crankY - cos * amplitude          // left foot leads…
    val footRightY = crankY + cos * amplitude          // …right foot trails (180° apart)
    drawLeg(hipLeftX, footLeftX, footLeftY, kneeOutX = -1.6f)
    drawLeg(hipRightX, footRightX, footRightY, kneeOutX = 1.6f)
    drawShoe(footLeftX, footLeftY, w = 3.8f, h = 2.4f)
    drawShoe(footRightX, footRightY, w = 3.8f, h = 2.4f)
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

    // White five-pointed star centred on the pin body, so a saved favourite is
    // instantly recognisable (and matches the icon's documented appearance).
    canvas.drawPath(
        starPath(centerX = cx, centerY = cy, outerRadius = 16f, innerRadius = 6.6f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    )

    return BitmapDrawable(null, bitmap)
}

/**
 * A numbered dropped-pin used while planning a multi-waypoint route, so each
 * chosen stop is clearly visible on the map with its order number. The last
 * waypoint is drawn in a distinct amber colour so the rider can immediately tell
 * where the route currently ends; the earlier stops are teal.
 */
internal fun createWaypointPinIcon(number: Int, isLast: Boolean): Bitmap {
    val width = 72
    val height = 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = 32f

    // Shadow
    canvas.drawCircle(cx + 3f, 76f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    })

    val bodyColor = if (isLast) "#FB8C00".toColorInt() else "#00897B".toColorInt()
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bodyColor; style = Paint.Style.FILL
    }
    // White keyline so the pin pops on any basemap.
    val keyline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }

    val body = Path().apply {
        addCircle(cx, cy, 28f, Path.Direction.CW)
        moveTo(cx, 90f)
        lineTo(cx - 16f, 50f)
        lineTo(cx + 16f, 50f)
        close()
    }
    canvas.drawPath(body, keyline)
    canvas.drawPath(body, pinPaint)

    // White inner disc that holds the number.
    canvas.drawCircle(cx, cy, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    })

    // Order number, centred in the white disc.
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bodyColor
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val fm = textPaint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    canvas.drawText(number.toString(), cx, baseline, textPaint)

    return bitmap
}

/**
 * A bold amber dropped-pin icon carrying a white bike glyph, used to mark where
 * the user parked their bike. Deliberately distinct from every other pin (green
 * saved-place star, blue custom pin, red search pin) so the parked bike is
 * instantly recognisable on the map.
 */
internal fun createParkedBikeIcon(context: Context): Drawable {
    val width  = 104
    val height = 137
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = 45f

    // Shadow
    canvas.drawCircle(cx + 4f, 106f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    })

    // Pin body (amber circle)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F57C00".toColorInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 40f, pinPaint)

    // Pin tip
    canvas.drawPath(Path().apply {
        moveTo(cx, 128f)
        lineTo(cx - 24f, 71f)
        lineTo(cx + 24f, 71f)
        close()
    }, pinPaint)

    // White inner disc so the bike glyph reads cleanly at any size.
    canvas.drawCircle(cx, cy, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    })

    // Amber bike glyph centred on the white disc.
    val bikeDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_bike_marker)?.mutate()
    if (bikeDrawable != null) {
        DrawableCompat.setTint(bikeDrawable, "#E65100".toColorInt())
        val iconSize = 40
        val left = (cx - iconSize / 2f).roundToInt()
        val top  = (cy - iconSize / 2f).roundToInt()
        bikeDrawable.setBounds(left, top, left + iconSize, top + iconSize)
        bikeDrawable.draw(canvas)
    }

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * A speech-bubble "tag" that marks the spot a recorded ride reached its top speed,
 * shown when the rider inspects a past ride. The [label] (e.g. `"31.0 km/h"`) is
 * drawn in white on a bold red rounded bubble with a small downward pointer (tail)
 * whose tip sits at the bottom-centre of the bitmap — so a `ICON_ANCHOR_BOTTOM`
 * symbol layer plants the tail exactly on the GPS point. A soft drop shadow and a
 * white keyline lift it off any basemap.
 */
internal fun createSpeedBubbleIcon(
    label: String,
    fillColor: Int = "#E53935".toColorInt(),
    textColor: Int = Color.WHITE
): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val fm = textPaint.fontMetrics
    val textWidth = textPaint.measureText(label)
    val textHeight = fm.descent - fm.ascent

    val padH = 26f
    val padV = 16f
    val stroke = 4f
    val tailW = 22f
    val tailH = 16f
    val pad = 6f // breathing room for the shadow / keyline

    val bubbleW = textWidth + padH * 2
    val bubbleH = textHeight + padV * 2
    val width = (bubbleW + stroke * 2 + pad * 2).roundToInt()
    val height = (bubbleH + tailH + stroke * 2 + pad * 2).roundToInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val cx = width / 2f
    val left = pad + stroke
    val top = pad + stroke
    val right = width - pad - stroke
    val bottom = top + bubbleH
    val radius = bubbleH / 2f

    // Bubble + tail as a single path so the keyline wraps them as one silhouette.
    val bubblePath = Path().apply {
        addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        moveTo(cx - tailW / 2f, bottom - 1f)
        lineTo(cx, bottom + tailH)
        lineTo(cx + tailW / 2f, bottom - 1f)
        close()
    }

    // Soft drop shadow.
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44000000
        maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
    })
    // White keyline.
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = stroke * 2f
        strokeJoin = Paint.Join.ROUND
    })
    // Filled bubble.
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    })

    // Centred label.
    val baseline = top + padV - fm.ascent
    canvas.drawText(label, cx, baseline, textPaint)

    return bitmap
}

/** Identifies what an analysis value bubble means, drawn as a leading pictograph. */
internal enum class RideBubbleGlyph { SPEED, GRADIENT, PEAK, PAUSE }

/**
 * A labelled value bubble for the **ride-analysis map**. Like [createSpeedBubbleIcon]
 * but with two fixes for that screen: a leading white **pictograph** that says what
 * the value means (so a bare `"128 m"` reads as a summit, not a distance), and a
 * **dark outline** that stays crisp on the light basemap (the plain white keyline
 * was invisible against it). The tail tip sits at the bottom-centre, so an
 * `ICON_ANCHOR_BOTTOM` layer plants it on the GPS point.
 */
internal fun createAnalysisBubbleIcon(
    label: String,
    fillColor: Int,
    glyph: RideBubbleGlyph,
    textColor: Int = Color.WHITE
): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val fm = textPaint.fontMetrics
    val textWidth = textPaint.measureText(label)
    val textHeight = fm.descent - fm.ascent

    val glyphSize = textHeight * 0.9f
    val glyphGap = 12f
    val padH = 24f
    val padV = 16f
    val outline = 3f
    val tailW = 22f
    val tailH = 16f
    val pad = 7f

    val contentW = glyphSize + glyphGap + textWidth
    val bubbleW = contentW + padH * 2
    val bubbleH = textHeight + padV * 2
    val width = (bubbleW + outline * 2 + pad * 2).roundToInt()
    val height = (bubbleH + tailH + outline * 2 + pad * 2).roundToInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val cx = width / 2f
    val left = pad + outline
    val top = pad + outline
    val right = width - pad - outline
    val bottom = top + bubbleH
    val radius = bubbleH / 2f

    val bubblePath = Path().apply {
        addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        moveTo(cx - tailW / 2f, bottom - 1f)
        lineTo(cx, bottom + tailH)
        lineTo(cx + tailW / 2f, bottom - 1f)
        close()
    }

    // Soft drop shadow so the bubble lifts off the basemap.
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    })
    // Dark outline — crisp on a light map (unlike the white keyline).
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1A1A1A".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = outline * 2f
        strokeJoin = Paint.Join.ROUND
    })
    // Filled bubble.
    canvas.drawPath(bubblePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    })

    // Leading pictograph, then the value text.
    val contentLeft = left + padH
    val glyphCx = contentLeft + glyphSize / 2f
    val glyphCy = (top + bottom) / 2f
    drawBubbleGlyph(canvas, glyph, glyphCx, glyphCy, glyphSize, textColor)

    val textX = contentLeft + glyphSize + glyphGap
    val baseline = top + padV - fm.ascent
    canvas.drawText(label, textX, baseline, textPaint)

    return bitmap
}

/** Draws the small white pictograph at the left of an analysis value bubble. */
private fun drawBubbleGlyph(
    canvas: Canvas, glyph: RideBubbleGlyph, cx: Float, cy: Float, size: Float, color: Int
) {
    val s = size / 2f
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = size * 0.16f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    when (glyph) {
        RideBubbleGlyph.PEAK -> canvas.drawPath(Path().apply {
            // Mountain: up-pointing triangle.
            moveTo(cx, cy - s); lineTo(cx + s, cy + s); lineTo(cx - s, cy + s); close()
        }, fillPaint)
        RideBubbleGlyph.GRADIENT -> canvas.drawPath(Path().apply {
            // Ramp: right triangle rising to the right.
            moveTo(cx - s, cy + s); lineTo(cx + s, cy + s); lineTo(cx + s, cy - s); close()
        }, fillPaint)
        RideBubbleGlyph.PAUSE -> {
            // Two rounded bars (a "pause" symbol).
            val barW = size * 0.24f
            val gap = size * 0.16f
            val r = barW / 2f
            canvas.drawRoundRect(cx - gap - barW, cy - s, cx - gap, cy + s, r, r, fillPaint)
            canvas.drawRoundRect(cx + gap, cy - s, cx + gap + barW, cy + s, r, r, fillPaint)
        }
        RideBubbleGlyph.SPEED -> {
            // Gauge: a 240° arc with a needle.
            canvas.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), 150f, 240f, false, strokePaint)
            canvas.drawLine(cx, cy, cx + s * 0.65f, cy - s * 0.5f, strokePaint)
        }
    }
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

