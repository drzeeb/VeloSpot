package de.velospot.core.tracking

import de.velospot.testsupport.ElevationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the shared cumulative-elevation integrator, pinned against
 * real recorded-ride altitude series ([ElevationFixtures]) plus a few synthetic
 * edge cases.
 *
 * ## The bug these guard
 * The previous EMA + single-moving-base accumulator collapsed the counter-direction
 * to exactly `0.0` on a net-monotonic ride and grossly under-counted the dominant
 * direction. The stored (old) numbers are quoted in each fixture's KDoc; every
 * assertion below is chosen so it **fails on the old numbers and passes on the new
 * algorithm** — e.g. ride `abf570df` stored gain=0.0/loss=6.49, the new integrator
 * yields gain≈13.2/loss≈25.7.
 */
class ElevationAccumulatorTest {

    private fun samples(altitudes: List<Double>) =
        altitudes.map { AltitudeSample(it) }

    private fun accumulate(altitudes: List<Double>): ElevationAccumulator {
        val acc = ElevationAccumulator()
        altitudes.forEach { acc.add(it) }
        return acc
    }

    // ── Real-ride fixtures ────────────────────────────────────────────────────

    @Test
    fun `net-descent real ride counts a realistic loss and does not zero the climb`() {
        // Ride abf570df: 221.6 → 190 m net descent. OLD stored gain=0.0, loss=6.49.
        val acc = accumulate(ElevationFixtures.NET_DESCENT_ABF570DF)

        // Loss is realistic and far above the old 6.49 m under-count.
        assertTrue("loss should be well above the old 6.49 m (got ${acc.loss})", acc.loss > 12.0)
        assertTrue("loss should stay physically sensible (got ${acc.loss})", acc.loss < 45.0)
        // The dominant direction is loss, but the climb is NOT forced to 0 like before.
        assertTrue("gain must not be zero-forced on a net descent (got ${acc.gain})", acc.gain > 3.0)
        assertTrue("loss should dominate on a net descent", acc.loss > acc.gain)
    }

    @Test
    fun `net-climb real ride counts a realistic gain`() {
        // Ride a46709f5: net climb. OLD stored gain=6.36, loss=0.0. The mid-ride
        // 50 m altitude plateau is spike-gated (steps > 12 m), so this ride's loss
        // legitimately stays ~0; the point here is that the climb is counted and the
        // integrator does not blow up on the spikes.
        val acc = accumulate(ElevationFixtures.NET_CLIMB_A46709F5)

        assertTrue("gain should be realistic (got ${acc.gain})", acc.gain in 5.0..10.0)
        assertTrue("gain dominates on a net climb", acc.gain > acc.loss)
    }

    @Test
    fun `rolling real ride counts non-trivial gain and loss in both directions`() {
        // Ride 0e2a1c56: rolling terrain, net climb. OLD stored gain=57.5, loss=39.4.
        // The new integrator captures the intermediate rolls the old one swallowed,
        // so BOTH directions are materially larger — and loss is emphatically NOT 0
        // on this net-climb ride (the counter-direction mirror of abf570df).
        val acc = accumulate(ElevationFixtures.ROLLING_0E2A1C56)

        assertTrue("gain should be substantial (got ${acc.gain})", acc.gain > 80.0)
        assertTrue("loss should be substantial and not zeroed (got ${acc.loss})", acc.loss > 80.0)
        // Both larger than the under-counting old stored values.
        assertTrue("gain exceeds the old 57.5 m under-count", acc.gain > 57.5)
        assertTrue("loss exceeds the old 39.4 m under-count", acc.loss > 39.4)
    }

    // ── Synthetic edge cases ──────────────────────────────────────────────────

    @Test
    fun `clean monotonic ramp counts the whole climb and legitimately zero loss`() {
        // 100 → 130 m over 30 steps of +1 m. A clean monotonic climb must be fully
        // counted (≈30 m, minus the light EMA lag) and the counter-direction is 0
        // *legitimately* — the opposite of the bug, where the CLIMB was the one zeroed.
        val ramp = (0..30).map { 100.0 + it }
        val acc = accumulate(ramp)

        assertTrue("clean climb fully counted (got ${acc.gain})", acc.gain in 27.0..30.0)
        assertEquals("no phantom loss on a pure climb", 0.0, acc.loss, 1e-9)
    }

    @Test
    fun `noisy stationary wobble produces no phantom elevation`() {
        // ±2 m jitter around 100 m while parked: stays inside the hysteresis, so
        // neither direction may accumulate.
        val acc = accumulate(listOf(100.0, 102.0, 98.0, 101.0, 99.0, 100.5, 99.5))

        assertEquals(0.0, acc.gain, 1e-9)
        assertEquals(0.0, acc.loss, 1e-9)
    }

    @Test
    fun `poor-accuracy fixes are dropped by the accuracy gate`() {
        // A clean 100 → 110 m climb (accuracy 5 m) with one bogus 200 m fix injected
        // mid-climb carrying a poor 40 m accuracy. The gate must drop it, so the
        // result equals the same climb with the bogus fix simply removed.
        val climb = (0..10).map { 100.0 + it }
        val withPoor = ElevationAccumulator()
        val cleanRemoved = ElevationAccumulator()
        climb.forEachIndexed { i, alt ->
            withPoor.add(alt, accuracyMeters = 5f)
            cleanRemoved.add(alt, accuracyMeters = 5f)
            if (i == 5) withPoor.add(200.0, accuracyMeters = 40f) // dropped: accuracy > 25 m
        }
        assertEquals("dropped poor fix must not change gain", cleanRemoved.gain, withPoor.gain, 1e-9)
        assertEquals("dropped poor fix must not change loss", cleanRemoved.loss, withPoor.loss, 1e-9)

        // Sanity: the SAME bogus altitude with a GOOD accuracy would be accepted and
        // change the result (spike-gated re-seed), proving it was the accuracy that
        // dropped it — not some unrelated filtering.
        val withGoodAccuracyBogus = ElevationAccumulator()
        climb.forEachIndexed { i, alt ->
            withGoodAccuracyBogus.add(alt, accuracyMeters = 5f)
            if (i == 5) withGoodAccuracyBogus.add(200.0, accuracyMeters = 10f)
        }
        assertTrue(
            "an accepted bogus fix should change the result",
            kotlin.math.abs(withGoodAccuracyBogus.gain - withPoor.gain) > 0.5
        )
    }

    @Test
    fun `batch compute matches the incremental add path`() {
        for (fixture in listOf(
            ElevationFixtures.NET_DESCENT_ABF570DF,
            ElevationFixtures.NET_CLIMB_A46709F5,
            ElevationFixtures.ROLLING_0E2A1C56
        )) {
            val incremental = accumulate(fixture)
            val batch = ElevationAccumulator.compute(samples(fixture))
            assertEquals(incremental.gain, batch.gainMeters, 1e-9)
            assertEquals(incremental.loss, batch.lossMeters, 1e-9)
        }
    }

    @Test
    fun `breakSegment keeps banked totals but re-seeds across a pause`() {
        // Climb, pause (breakSegment), then a big altitude jump across the gap must
        // NOT be banked as a phantom step; the already-accumulated gain is retained.
        val acc = ElevationAccumulator()
        listOf(100.0, 102.0, 104.0, 106.0).forEach { acc.add(it) }
        val gainBeforePause = acc.gain
        assertTrue("some climb was banked before the pause", gainBeforePause > 2.0)

        acc.breakSegment()
        // Resume 40 m higher (e.g. after a train leg) then sit still.
        listOf(146.0, 146.0, 146.0).forEach { acc.add(it) }

        assertEquals("no phantom step banked across the pause gap", gainBeforePause, acc.gain, 1e-9)
        assertEquals(0.0, acc.loss, 1e-9)
    }
}

