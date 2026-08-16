package de.velospot.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [BikePhotoCrop] — the display-scale ⇄ normalized-crop maths the
 * in-app framing step uses, and the normalized → source-pixel conversion storage
 * applies before downscaling the saved JPEG.
 */
class BikePhotoCropTest {

    @Test
    fun `coverScale fills the frame on the constrained edge`() {
        // A 1000x1000 image into a 200x100 (2:1) frame: cover = max(0.2, 0.1) = 0.2.
        assertEquals(0.2f, BikePhotoCrop.coverScale(1000, 1000, 200f, 100f), 1e-4f)
        // A wide 2000x500 image into a 200x100 frame: cover = max(0.1, 0.2) = 0.2.
        assertEquals(0.2f, BikePhotoCrop.coverScale(2000, 500, 200f, 100f), 1e-4f)
    }

    @Test
    fun `coverScale is safe for degenerate inputs`() {
        assertEquals(1f, BikePhotoCrop.coverScale(0, 0, 200f, 100f), 0f)
        assertEquals(1f, BikePhotoCrop.coverScale(1000, 1000, 0f, 0f), 0f)
    }

    @Test
    fun `centred cover crop of a square into a 2 by 1 frame keeps full width middle half`() {
        // Square image, 2:1 frame, at the cover scale, centred (offset 0).
        val crop = BikePhotoCrop.normalizedCrop(
            imageWidth = 1000, imageHeight = 1000,
            frameWidth = 200f, frameHeight = 100f,
            scale = 0.2f, offsetX = 0f, offsetY = 0f
        )
        assertEquals(0f, crop.left, 1e-4f)
        assertEquals(1f, crop.right, 1e-4f)
        assertEquals(0.25f, crop.top, 1e-4f)
        assertEquals(0.75f, crop.bottom, 1e-4f)
    }

    @Test
    fun `panning down shifts the crop window up the image`() {
        // Positive offsetY moves the image down on screen → the frame sees higher rows.
        val crop = BikePhotoCrop.normalizedCrop(
            imageWidth = 1000, imageHeight = 1000,
            frameWidth = 200f, frameHeight = 100f,
            scale = 0.2f, offsetX = 0f, offsetY = 20f
        )
        // 20px offset at scale 0.2 = 100 image px shift up → top 0.25-0.1=0.15.
        assertEquals(0.15f, crop.top, 1e-4f)
        assertEquals(0.65f, crop.bottom, 1e-4f)
    }

    @Test
    fun `zooming in narrows the crop window`() {
        // 2x the cover scale → the frame covers half the width/height it did before.
        val crop = BikePhotoCrop.normalizedCrop(
            imageWidth = 1000, imageHeight = 1000,
            frameWidth = 200f, frameHeight = 100f,
            scale = 0.4f, offsetX = 0f, offsetY = 0f
        )
        assertEquals(0.25f, crop.left, 1e-4f)
        assertEquals(0.75f, crop.right, 1e-4f)
        assertEquals(0.375f, crop.top, 1e-4f)
        assertEquals(0.625f, crop.bottom, 1e-4f)
    }

    @Test
    fun `normalizedCrop clamps edges into 0 to 1`() {
        val crop = BikePhotoCrop.normalizedCrop(
            imageWidth = 1000, imageHeight = 1000,
            frameWidth = 5000f, frameHeight = 5000f,
            scale = 0.2f, offsetX = 0f, offsetY = 0f
        )
        assertTrue(crop.left >= 0f && crop.top >= 0f)
        assertTrue(crop.right <= 1f && crop.bottom <= 1f)
    }

    @Test
    fun `clampOffset keeps the frame inside the image`() {
        // Displayed size 1000*0.4 = 400; frame 200 → max offset (400-200)/2 = 100.
        val (x, y) = BikePhotoCrop.clampOffset(
            imageWidth = 1000, imageHeight = 1000,
            frameWidth = 200f, frameHeight = 100f,
            scale = 0.4f, offsetX = 999f, offsetY = -999f
        )
        assertEquals(100f, x, 1e-4f)
        // Vertical: (400-100)/2 = 150.
        assertEquals(-150f, y, 1e-4f)
    }

    @Test
    fun `sourcePixels maps a normalized rect into bounded source pixels`() {
        val px = BikePhotoCrop.sourcePixels(
            srcWidth = 4000, srcHeight = 3000,
            crop = NormalizedCropRect(0.25f, 0.5f, 0.75f, 1f)
        )
        assertEquals(1000, px.left)
        assertEquals(1500, px.top)
        assertEquals(3000, px.right)
        assertEquals(3000, px.bottom)
        assertEquals(2000, px.width)
        assertEquals(1500, px.height)
    }

    @Test
    fun `sourcePixels never yields a zero or out-of-bounds edge`() {
        val degenerate = BikePhotoCrop.sourcePixels(
            srcWidth = 100, srcHeight = 100,
            crop = NormalizedCropRect(1f, 1f, 1f, 1f)
        )
        assertTrue(degenerate.width >= 1)
        assertTrue(degenerate.height >= 1)
        assertTrue(degenerate.right <= 100 && degenerate.bottom <= 100)
    }

    @Test
    fun `FULL crop is recognised as full`() {
        assertTrue(NormalizedCropRect.FULL.isFull)
        assertTrue(!NormalizedCropRect(0f, 0.1f, 1f, 1f).isFull)
    }
}

