package de.velospot.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [BikePhotoScaling] — the downscale maths applied before a
 * picked bike photo is re-encoded into app storage.
 */
class BikePhotoScalingTest {

    @Test
    fun `sampleSize is 1 for images within the cap`() {
        assertEquals(1, BikePhotoScaling.sampleSize(800, 600, maxDimension = 1440))
        assertEquals(1, BikePhotoScaling.sampleSize(1440, 1000, maxDimension = 1440))
    }

    @Test
    fun `sampleSize grows in powers of two for large images`() {
        // 4000px longest, cap 1440 → /2 = 2000 (>=1440) → /4 = 1000 (<1440) → 2.
        assertEquals(2, BikePhotoScaling.sampleSize(4000, 3000, maxDimension = 1440))
        // 6000px longest → /4 = 1500 (>=1440) → /8 = 750 → 4.
        assertEquals(4, BikePhotoScaling.sampleSize(6000, 4000, maxDimension = 1440))
    }

    @Test
    fun `sampleSize is safe for degenerate inputs`() {
        assertEquals(1, BikePhotoScaling.sampleSize(0, 0))
        assertEquals(1, BikePhotoScaling.sampleSize(-10, 20))
    }

    @Test
    fun `targetDimensions preserves small images unchanged`() {
        assertEquals(800 to 600, BikePhotoScaling.targetDimensions(800, 600, maxDimension = 1440))
    }

    @Test
    fun `targetDimensions clamps the longest edge and keeps aspect ratio`() {
        val (w, h) = BikePhotoScaling.targetDimensions(4000, 2000, maxDimension = 1440)
        assertEquals(1440, w)
        assertEquals(720, h)
    }

    @Test
    fun `targetDimensions never returns a zero edge`() {
        val (w, h) = BikePhotoScaling.targetDimensions(4000, 1, maxDimension = 1440)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }
}

