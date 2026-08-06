package de.velospot.core.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM coverage for the pure [StandstillDetector] — the debounced
 * movement classifier that lets the recorder idle the GPS during sustained stops
 * (battery win) and restore full fidelity the instant the rider moves.
 *
 * Uses an explicit threshold (0.5 m/s) and dwell (60 s) matching the production
 * defaults, and drives synthetic (speed, timestamp) fixes so no wall clock or
 * Android runtime is involved.
 */
class StandstillDetectorTest {

    private val threshold = StandstillDetector.MOVING_SPEED_THRESHOLD_MPS // 0.5 m/s
    private val dwell = StandstillDetector.STATIONARY_DWELL_MILLIS         // 60_000 ms

    private fun detector() = StandstillDetector(threshold, dwell)

    @Test
    fun `stays moving while speed is above the threshold`() {
        val d = detector()
        var t = 0L
        repeat(20) {
            assertFalse("above-threshold fix must never be stationary", d.onFix(5f, t))
            t += 3_000L
        }
    }

    @Test
    fun `only flips stationary after the full dwell of sustained low speed`() {
        val d = detector()
        // A moving fix, then the rider stops: the dwell starts at the first low fix.
        assertFalse(d.onFix(4f, 0L))
        val lowStart = 3_000L
        assertFalse("first low fix starts the dwell, still moving", d.onFix(0.1f, lowStart))
        assertFalse(d.onFix(0.0f, lowStart + dwell - 1))
        // At/after the full dwell of sustained low speed it flips to stationary.
        assertTrue("sustained low speed for the full dwell idles the GPS", d.onFix(0.0f, lowStart + dwell))
    }

    @Test
    fun `flips back to moving immediately on one fast fix`() {
        val d = detector()
        // Reach stationary.
        d.onFix(0f, 0L)
        assertTrue(d.onFix(0f, dwell))
        // A single fix above the threshold restores moving at once (no re-dwell).
        assertFalse("exit-stationary is immediate", d.onFix(3f, dwell + 3_000L))
    }

    @Test
    fun `low-speed streak resets when the rider briefly moves`() {
        val d = detector()
        // Almost a full dwell of low speed…
        d.onFix(0f, 0L)
        assertFalse(d.onFix(0f, dwell - 1_000L))
        // …but a brief move resets the streak, so the dwell must restart.
        val moveAt = dwell
        assertFalse(d.onFix(2f, moveAt))
        // The fresh low-speed streak starts here and needs another full dwell.
        val lowStart = moveAt + 1_000L
        assertFalse("dwell restarts after moving", d.onFix(0f, lowStart))
        assertFalse(d.onFix(0f, lowStart + dwell - 1_000L))
        assertTrue("stationary only after a fresh full dwell", d.onFix(0f, lowStart + dwell))
    }

    @Test
    fun `does not flap around the threshold`() {
        val d = detector()
        // Creeping just below / above the threshold in traffic must not oscillate
        // the classification: it never idles the GPS while never sustaining low speed.
        var t = 0L
        repeat(30) { i ->
            val speed = if (i % 2 == 0) threshold + 0.2f else threshold + 0.05f
            assertFalse("alternating above-threshold speeds stay moving", d.onFix(speed, t))
            t += 2_000L
        }
    }

    @Test
    fun `pause idles immediately and resume restores moving`() {
        val d = detector()
        d.onFix(5f, 0L)
        // Pausing (train/ferry leg) idles the GPS at once, no dwell required.
        assertTrue("pause is stationary immediately", d.setPaused(true))
        // While paused every fix stays stationary regardless of reported speed.
        assertTrue(d.onFix(9f, 3_000L))
        // Resuming restores moving at once so the rider gets full-accuracy fixes.
        assertFalse("resume restores moving", d.setPaused(false))
    }

    @Test
    fun `reset returns to the moving state`() {
        val d = detector()
        d.onFix(0f, 0L)
        assertTrue(d.onFix(0f, dwell))
        d.reset()
        assertFalse("reset clears standstill", d.isStationary)
        assertFalse(d.onFix(0f, dwell + 1_000L))
    }
}

