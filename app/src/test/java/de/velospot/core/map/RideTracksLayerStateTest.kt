package de.velospot.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the graceful legacy→unified migration of the two separate
 * recorded-ride overlays (heatmap / ridden tracks) into the single
 * [MapLayerCategory.RIDE_TRACKS] layer with a [RideTracksMode].
 */
class RideTracksLayerStateTest {

    @Test
    fun `legacy heatmap on migrates to unified on plus HEATMAP mode`() {
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = null, newMode = null,
            legacyHeatmap = true, legacyTracks = false
        )
        assertEquals(true, visible)
        assertEquals(RideTracksMode.HEATMAP, mode)
    }

    @Test
    fun `legacy tracks on migrates to unified on plus LINES mode`() {
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = null, newMode = null,
            legacyHeatmap = false, legacyTracks = true
        )
        assertEquals(true, visible)
        assertEquals(RideTracksMode.LINES, mode)
    }

    @Test
    fun `both legacy off migrates to unified off (default LINES)`() {
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = null, newMode = null,
            legacyHeatmap = false, legacyTracks = false
        )
        assertEquals(false, visible)
        assertEquals(RideTracksMode.LINES, mode)
    }

    @Test
    fun `both legacy on migrates to on with HEATMAP taking precedence`() {
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = null, newMode = null,
            legacyHeatmap = true, legacyTracks = true
        )
        assertEquals(true, visible)
        assertEquals(RideTracksMode.HEATMAP, mode)
    }

    @Test
    fun `new unified value always wins over legacy seed`() {
        // User has explicitly turned the new layer OFF; legacy keys are ignored.
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = false, newMode = RideTracksMode.LINES,
            legacyHeatmap = true, legacyTracks = true
        )
        assertEquals(false, visible)
        assertEquals(RideTracksMode.LINES, mode)

        // User has explicitly chosen HEATMAP while the legacy seed would say LINES.
        val (visible2, mode2) = RideTracksLayerState.resolve(
            newVisible = true, newMode = RideTracksMode.HEATMAP,
            legacyHeatmap = false, legacyTracks = true
        )
        assertEquals(true, visible2)
        assertEquals(RideTracksMode.HEATMAP, mode2)
    }

    @Test
    fun `new visibility with no explicit mode falls back to legacy-derived mode`() {
        // Only the new visibility was written (e.g. toggled on) but no mode yet —
        // the mode still seeds from the legacy heatmap key.
        val (visible, mode) = RideTracksLayerState.resolve(
            newVisible = true, newMode = null,
            legacyHeatmap = true, legacyTracks = false
        )
        assertEquals(true, visible)
        assertEquals(RideTracksMode.HEATMAP, mode)
    }
}

