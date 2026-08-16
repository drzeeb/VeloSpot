package de.velospot.core.photo

import kotlin.math.max

/**
 * Pure, Android-free maths for downscaling an uploaded bike photo before it is
 * re-encoded into app storage. Kept out of the Android layer so the (fiddly)
 * power-of-two sample-size and target-dimension logic can be unit-tested on the JVM.
 *
 * A rider's gallery photo can easily be several megapixels / multiple megabytes;
 * the garage only ever shows it small (a row avatar) or on a share card, so it is
 * clamped to [MAX_DIMENSION] px on its longest edge — plenty for both — which keeps
 * each stored file well under a few hundred kilobytes.
 */
object BikePhotoScaling {

    /** Longest-edge cap (px) for the stored, downscaled bike photo. */
    const val MAX_DIMENSION = 1440

    /** JPEG quality used when re-encoding the downscaled photo. */
    const val JPEG_QUALITY = 85

    /**
     * The largest power-of-two `inSampleSize` for `BitmapFactory` that still keeps
     * both decoded dimensions at or above [maxDimension] — the standard cheap,
     * memory-safe pre-scale before the final exact resize. Returns `1` for images
     * already within the cap (or for non-positive inputs).
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int = MAX_DIMENSION): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxDimension <= 0) return 1
        var sample = 1
        val longest = max(srcWidth, srcHeight)
        while (longest / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * The exact target dimensions (width, height) that fit [srcWidth]×[srcHeight]
     * inside a [maxDimension]×[maxDimension] box while preserving the aspect ratio.
     * Images already within the box are returned unchanged. Never returns a zero
     * edge.
     */
    fun targetDimensions(
        srcWidth: Int,
        srcHeight: Int,
        maxDimension: Int = MAX_DIMENSION
    ): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val longest = max(srcWidth, srcHeight)
        if (longest <= maxDimension) return srcWidth to srcHeight
        val scale = maxDimension.toDouble() / longest
        val w = (srcWidth * scale).toInt().coerceAtLeast(1)
        val h = (srcHeight * scale).toInt().coerceAtLeast(1)
        return w to h
    }
}

