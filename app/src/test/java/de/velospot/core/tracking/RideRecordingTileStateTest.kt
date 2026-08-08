package de.velospot.core.tracking

import de.velospot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [tileRenderState] — the pure recording-state → Quick Settings
 * tile label/subtitle/highlight mapping extracted from `RideRecordingTileService`.
 *
 * This is the exact mapping that must stay in lock-step with the live recording
 * state (finding #16: the tile going stale). The manager already re-invokes the
 * tile's `onStartListening` on every state change via `requestListeningState`; this
 * guards that the resulting render is correct for each idle / recording / paused
 * state so the label + active highlight never drift.
 */
class RideRecordingTileStateTest {

    @Test
    fun `idle shows Start and is inactive`() {
        val s = tileRenderState(recording = false, paused = false)
        assertFalse("idle tile must not be highlighted", s.active)
        assertEquals(R.string.ride_record_start, s.labelRes)
        // Next tap starts a ride.
        assertEquals(R.string.ride_record_start, s.subtitleRes)
    }

    @Test
    fun `recording shows Recording, is active, and previews Pause`() {
        val s = tileRenderState(recording = true, paused = false)
        assertTrue("an active recording is the only highlighted state", s.active)
        assertEquals(R.string.ride_recording, s.labelRes)
        assertEquals(R.string.ride_pause, s.subtitleRes)
    }

    @Test
    fun `paused shows Paused, reads inactive, and previews Resume`() {
        val s = tileRenderState(recording = true, paused = true)
        assertFalse("a paused ride is not 'capturing now', so not highlighted", s.active)
        assertEquals(R.string.ride_paused, s.labelRes)
        assertEquals(R.string.ride_resume, s.subtitleRes)
    }

    @Test
    fun `paused-but-not-recording is treated as idle`() {
        // Defensive: an impossible-in-practice combo must degrade to the idle render
        // rather than claim a paused ride when nothing is recording.
        assertEquals(
            tileRenderState(recording = false, paused = false),
            tileRenderState(recording = false, paused = true),
        )
    }
}

