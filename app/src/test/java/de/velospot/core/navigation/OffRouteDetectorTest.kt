package de.velospot.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the accuracy-aware off-route decision ([OffRouteDetector]).
 * Verifies that a good fix keeps the tight base threshold, a poor fix widens the
 * corridor, a genuine large deviation is still flagged, and an absurd accuracy
 * reading is clamped so detection can't be disabled.
 */
class OffRouteDetectorTest {

    // ── Threshold ─────────────────────────────────────────────────────────────

    @Test
    fun `good accuracy uses the tight base threshold`() {
        // A 5 m-accuracy fix stays at the base floor (good-GPS behaviour unchanged).
        assertEquals(
            OffRouteDetector.BASE_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(5f),
            1e-9
        )
    }

    @Test
    fun `null accuracy falls back to the base threshold`() {
        assertEquals(
            OffRouteDetector.BASE_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(null),
            1e-9
        )
    }

    @Test
    fun `non-positive or non-finite accuracy falls back to the base threshold`() {
        assertEquals(
            OffRouteDetector.BASE_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(0f),
            1e-9
        )
        assertEquals(
            OffRouteDetector.BASE_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(-10f),
            1e-9
        )
        assertEquals(
            OffRouteDetector.BASE_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(Float.NaN),
            1e-9
        )
    }

    @Test
    fun `poor accuracy widens the corridor`() {
        // 40 m accuracy → 1.5 × 40 = 60 m, well above the 30 m base.
        val threshold = OffRouteDetector.offRouteThresholdMeters(40f)
        assertEquals(60.0, threshold, 1e-9)
        assertTrue(threshold > OffRouteDetector.BASE_OFFROUTE_M)
    }

    @Test
    fun `absurd accuracy is clamped to the maximum`() {
        // A garbage 1000 m accuracy reading can't disable off-route detection.
        assertEquals(
            OffRouteDetector.MAX_OFFROUTE_M,
            OffRouteDetector.offRouteThresholdMeters(1000f),
            1e-9
        )
    }

    // ── Decision ──────────────────────────────────────────────────────────────

    @Test
    fun `good fix flags a modest deviation`() {
        // 35 m off with a crisp 4 m fix → beyond the 30 m base corridor.
        assertTrue(OffRouteDetector.isOffRoute(35.0, 4f))
    }

    @Test
    fun `poor fix tolerates the same modest deviation`() {
        // Same 35 m deviation, but a 40 m-accuracy fix widens the corridor to 60 m,
        // so it is NOT treated as off-route (avoids a false reroute).
        assertFalse(OffRouteDetector.isOffRoute(35.0, 40f))
    }

    @Test
    fun `genuine large deviation is flagged even for a poor fix`() {
        // 90 m off exceeds even the widened (and clamped, 75 m) corridor.
        assertTrue(OffRouteDetector.isOffRoute(90.0, 40f))
        assertTrue(OffRouteDetector.isOffRoute(90.0, 1000f))
    }

    @Test
    fun `on-route fix is not flagged`() {
        assertFalse(OffRouteDetector.isOffRoute(10.0, 5f))
    }
}

