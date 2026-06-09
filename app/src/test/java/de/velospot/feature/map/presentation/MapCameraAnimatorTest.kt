package de.velospot.feature.map.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapCameraAnimatorTest {

    @Test
    fun `hasZoomChange returns false for tiny delta`() {
        assertFalse(hasZoomChange(startZoom = 15.0, targetZoom = 15.005))
    }

    @Test
    fun `hasZoomChange returns true for visible delta`() {
        assertTrue(hasZoomChange(startZoom = 15.0, targetZoom = 15.05))
    }

    @Test
    fun `calculateAdjustedCenter uses current span when zoom stays equal`() {
        val adjusted = calculateAdjustedCenter(
            startZoom = 15.0,
            targetZoom = 15.0,
            baseCenter = LatLng(49.75, 6.64),
            verticalOffsetFraction = 1.0 / 6.0,
            currentLatitudeSpan = 0.12,
            startLatitudeSpan = 0.8
        )

        // 49.75 - (0.12 * 1/6) = 49.73
        assertEquals(49.73, adjusted.latitude, 1e-9)
        assertEquals(6.64, adjusted.longitude, 1e-9)
    }

    @Test
    fun `calculateAdjustedCenter uses projected target span when zoom changes`() {
        val adjusted = calculateAdjustedCenter(
            startZoom = 14.0,
            targetZoom = 16.0,
            baseCenter = LatLng(49.75, 6.64),
            verticalOffsetFraction = 0.25,
            currentLatitudeSpan = 0.9,
            startLatitudeSpan = 0.8
        )

        // targetSpan = 0.8 / 2^(16-14) = 0.2; offset = 0.05
        assertEquals(49.70, adjusted.latitude, 1e-9)
        assertEquals(6.64, adjusted.longitude, 1e-9)
    }

    @Test
    fun `calculateAdjustedCenter returns base center when offset is zero`() {
        val base = LatLng(49.75, 6.64)
        val adjusted = calculateAdjustedCenter(
            startZoom = 14.0,
            targetZoom = 18.0,
            baseCenter = base,
            verticalOffsetFraction = 0.0,
            currentLatitudeSpan = 0.9,
            startLatitudeSpan = 0.8
        )

        assertEquals(base.latitude, adjusted.latitude, 1e-9)
        assertEquals(base.longitude, adjusted.longitude, 1e-9)
    }
}
