package de.velospot.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraTest {

    @Test
    fun `zoom is closest when standing still`() {
        assertEquals(NavigationCamera.ZOOM_SLOW, NavigationCamera.targetZoom(0f, 0.0), 1e-6)
    }

    @Test
    fun `zoom is farthest at high speed`() {
        assertEquals(NavigationCamera.ZOOM_FAST, NavigationCamera.targetZoom(12f, 0.0), 1e-6)
    }

    @Test
    fun `zoom ramps between slow and fast`() {
        val z = NavigationCamera.targetZoom(4.5f, 0.0)
        assertTrue(z in NavigationCamera.ZOOM_FAST..NavigationCamera.ZOOM_SLOW)
    }

    @Test
    fun `imminent sharp turn forces the closest zoom even at speed`() {
        assertEquals(NavigationCamera.ZOOM_SLOW, NavigationCamera.targetZoom(12f, 80.0), 1e-6)
    }

    @Test
    fun `null speed is treated as standing still`() {
        assertEquals(NavigationCamera.ZOOM_SLOW, NavigationCamera.targetZoom(null, 0.0), 1e-6)
    }

    @Test
    fun `smoothing alpha is in 0_1 and grows with dt`() {
        val small = NavigationCamera.smoothingAlpha(0.008, NavigationCamera.TAU_POSITION_S)
        val large = NavigationCamera.smoothingAlpha(0.05, NavigationCamera.TAU_POSITION_S)
        assertTrue(small in 0.0..1.0)
        assertTrue(large in 0.0..1.0)
        assertTrue("longer frame ⇒ larger step", large > small)
    }
}

