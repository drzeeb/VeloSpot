package de.velospot.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `distance between two close points is plausible`() {
        // ~111 m per 0.001° latitude near the equator/mid-latitudes.
        val d = GeoMath.distanceMeters(49.7596, 6.6441, 49.7605, 6.6441)
        assertEquals(100.0, d, 5.0)
    }

    @Test
    fun `bearing due north is ~0`() {
        val b = GeoMath.bearingDegrees(49.0, 6.0, 49.01, 6.0)
        assertEquals(0.0, b, 0.5)
    }

    @Test
    fun `bearing due east is ~90`() {
        val b = GeoMath.bearingDegrees(49.0, 6.0, 49.0, 6.01)
        assertEquals(90.0, b, 0.5)
    }

    @Test
    fun `normalizeDegrees wraps negative and large angles`() {
        assertEquals(350.0, GeoMath.normalizeDegrees(-10.0), 1e-9)
        assertEquals(10.0, GeoMath.normalizeDegrees(370.0), 1e-9)
    }

    @Test
    fun `shortestAngleDelta picks the short way around`() {
        assertEquals(-20.0, GeoMath.shortestAngleDelta(10.0, 350.0), 1e-9)
        assertEquals(20.0, GeoMath.shortestAngleDelta(350.0, 10.0), 1e-9)
    }

    @Test
    fun `lerpAngle crosses the 360-0 boundary correctly`() {
        // Halfway from 350° to 10° is 0°, not 180°.
        assertEquals(0.0, GeoMath.lerpAngle(350.0, 10.0, 0.5), 1e-6)
    }

    @Test
    fun `projectOntoSegment clamps before the start`() {
        // Query point "behind" segment start → t == 0.
        val p = GeoMath.projectOntoSegment(
            lat = 49.0, lon = 5.99,
            aLat = 49.0, aLon = 6.0,
            bLat = 49.0, bLon = 6.01
        )
        assertEquals(0.0, p.t, 1e-6)
    }

    @Test
    fun `projectOntoSegment finds the midpoint of a segment`() {
        val p = GeoMath.projectOntoSegment(
            lat = 49.0001, lon = 6.005,
            aLat = 49.0, aLon = 6.0,
            bLat = 49.0, bLon = 6.01
        )
        assertEquals(0.5, p.t, 0.05)
        assertTrue("projection should be near the line", p.distanceMeters < 20.0)
    }
}

