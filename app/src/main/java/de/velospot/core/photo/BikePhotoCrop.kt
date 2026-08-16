package de.velospot.core.photo

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A crop rectangle expressed in *normalized* source-image coordinates (each edge in
 * `0..1`, relative to the full, EXIF-oriented image). Kept Android-free so it can be
 * carried through the editor draft and the photo-store seam and reasoned about /
 * unit-tested on the JVM.
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    /** `true` when the rect covers (essentially) the whole image — nothing to crop. */
    val isFull: Boolean
        get() = left <= 0.0005f && top <= 0.0005f && right >= 0.9995f && bottom >= 0.9995f

    companion object {
        /** The identity crop: the entire image. */
        val FULL = NormalizedCropRect(0f, 0f, 1f, 1f)
    }
}

/** A crop rectangle in absolute source-image pixels (right/bottom exclusive). */
data class PixelCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Pure, Android-free maths for the in-app bike-photo framing/crop step. The Compose
 * crop UI presents the picked image inside a fixed crop window (matching the
 * Sharepic photo box aspect ratio) that the rider pans / pinch-zooms; these helpers
 * convert the display scale + pan into the normalized crop rect and back into the
 * absolute source pixels handed to [BikePhotoScaling]-driven storage — all fully
 * testable on the JVM.
 */
object BikePhotoCrop {

    /**
     * Aspect ratio (width : height) of the crop frame. It mirrors the Sharepic
     * photo box — the *primary* place the photo is shown — whose panel spans
     * `1080 - 2*80 = 920` px wide by `640 - 210 = 430` px tall on the share card.
     * The circular row avatar uses a 1:1 crop of the same stored file; the share
     * card box is the one the framing targets.
     */
    const val FRAME_ASPECT_WIDTH = 920f
    const val FRAME_ASPECT_HEIGHT = 430f

    /** Maximum pinch-zoom, relative to the cover scale. */
    const val MAX_ZOOM = 5f

    /**
     * The *cover* scale (display px per image px): the smallest scale at which a
     * [imageWidth]×[imageHeight] image fully covers a [frameWidth]×[frameHeight]
     * window (so no gaps ever show inside the frame). Returns `1f` for degenerate
     * inputs.
     */
    fun coverScale(imageWidth: Int, imageHeight: Int, frameWidth: Float, frameHeight: Float): Float {
        if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) return 1f
        return max(frameWidth / imageWidth, frameHeight / imageHeight)
    }

    /**
     * Clamps a pan [offsetX]/[offsetY] (the image *centre*'s displacement from the
     * frame centre, in screen px) so the frame always stays fully inside the
     * displayed image at [scale]. Returns the clamped `(x, y)`.
     */
    fun clampOffset(
        imageWidth: Int,
        imageHeight: Int,
        frameWidth: Float,
        frameHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val maxX = ((imageWidth * scale - frameWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((imageHeight * scale - frameHeight) / 2f).coerceAtLeast(0f)
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }

    /**
     * The normalized crop rect (0..1 in source-image space) currently framed by a
     * fixed [frameWidth]×[frameHeight] window, given the displayed image [scale]
     * (display px per image px) and the image-centre [offsetX]/[offsetY] (screen px).
     * Result edges are clamped to `0..1`.
     */
    fun normalizedCrop(
        imageWidth: Int,
        imageHeight: Int,
        frameWidth: Float,
        frameHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): NormalizedCropRect {
        if (imageWidth <= 0 || imageHeight <= 0 || scale <= 0f) return NormalizedCropRect.FULL
        val leftPx = (-frameWidth / 2f - offsetX) / scale + imageWidth / 2f
        val rightPx = (frameWidth / 2f - offsetX) / scale + imageWidth / 2f
        val topPx = (-frameHeight / 2f - offsetY) / scale + imageHeight / 2f
        val bottomPx = (frameHeight / 2f - offsetY) / scale + imageHeight / 2f
        return NormalizedCropRect(
            left = (leftPx / imageWidth).coerceIn(0f, 1f),
            top = (topPx / imageHeight).coerceIn(0f, 1f),
            right = (rightPx / imageWidth).coerceIn(0f, 1f),
            bottom = (bottomPx / imageHeight).coerceIn(0f, 1f)
        )
    }

    /**
     * The absolute source-pixel crop rectangle for [crop] applied to a
     * [srcWidth]×[srcHeight] bitmap. Always in bounds and at least 1px on each edge
     * (a degenerate / full crop yields the whole image).
     */
    fun sourcePixels(srcWidth: Int, srcHeight: Int, crop: NormalizedCropRect): PixelCropRect {
        if (srcWidth <= 0 || srcHeight <= 0) return PixelCropRect(0, 0, srcWidth, srcHeight)
        val left = (crop.left * srcWidth).roundToInt().coerceIn(0, srcWidth - 1)
        val top = (crop.top * srcHeight).roundToInt().coerceIn(0, srcHeight - 1)
        val right = (crop.right * srcWidth).roundToInt().coerceIn(left + 1, srcWidth)
        val bottom = (crop.bottom * srcHeight).roundToInt().coerceIn(top + 1, srcHeight)
        return PixelCropRect(left, top, right, bottom)
    }
}

