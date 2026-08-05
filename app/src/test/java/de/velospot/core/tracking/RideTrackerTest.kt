package de.velospot.core.tracking

import de.velospot.testsupport.ElevationFixtures
import de.velospot.domain.model.LiveRideStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackerTest {

    /** ~111.32 m per 0.001° of latitude near the equator — handy for round numbers. */
    private val baseLat = 0.0
    private val baseLon = 0.0

    @Test
    fun `recording flag toggles with start and stop`() {
        val tracker = RideTracker()
        assertTrue(!tracker.isRecording)
        tracker.start(0L)
        assertTrue(tracker.isRecording)
        tracker.stop(1L)
        assertTrue(!tracker.isRecording)
    }

    @Test
    fun `accumulates distance across moving fixes`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = null, altitudeMeters = null)
        // ~111 m north after 10 s → clearly "moving".
        val stats = tracker.addPoint(0.001, baseLon, 10_000L, speedMps = null, altitudeMeters = null)
        assertTrue("distance should be ~111 m", stats.distanceMeters in 100.0..120.0)
        assertEquals(2, stats.pointCount)
    }

    @Test
    fun `standstill jitter does not accumulate distance`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, null, null)
        // Sub-metre wobble while standing still.
        val stats = tracker.addPoint(0.000002, 0.000002, 5_000L, null, null)
        assertEquals(0.0, stats.distanceMeters, 0.001)
    }

    @Test
    fun `short ride is discarded on stop`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, null, null)
        tracker.addPoint(0.00001, baseLon, 1_000L, null, null) // ~1 m
        assertNull(tracker.stop(1_000L))
    }

    @Test
    fun `valid ride is produced on stop`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = 5f, altitudeMeters = 100.0)
        tracker.addPoint(0.001, baseLon, 10_000L, speedMps = 6f, altitudeMeters = 110.0)
        val ride = tracker.stop(10_000L)
        assertNotNull(ride)
        requireNotNull(ride)
        assertTrue(ride.distanceMeters > 100.0)
        assertEquals(6.0, ride.maxSpeedMps, 0.001)
        // Smoothed altitude rose past the 3 m dead-band → some ascent counted.
        assertTrue("ascent should be counted", ride.elevationGainMeters > 0.0)
        assertEquals(0.0, ride.elevationLossMeters, 0.001)
        assertEquals(2, ride.points.size)
    }

    @Test
    fun `noisy stationary altitude does not produce phantom elevation`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Standing still: lat/lon barely move, altitude wobbles ±2 m around 100.
        val noisyAltitudes = listOf(100.0, 102.0, 98.0, 101.0, 99.0, 100.5, 99.5)
        noisyAltitudes.forEachIndexed { i, alt ->
            tracker.addPoint(0.0000005 * i, 0.0, i * 3_000L, speedMps = 0f, altitudeMeters = alt)
        }
        val stats = tracker.currentStats()
        // The ±2 m wobble stays under the 3 m smoothed dead-band → no phantom climb.
        assertEquals(0.0, stats.elevationGainMeters, 0.001)
        assertEquals(0.0, stats.elevationLossMeters, 0.001)
    }

    @Test
    fun `implausible teleport fix is rejected from distance`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, null, null)
        // 1° latitude (~111 km) in 1 s → impossible for a bike, must be ignored.
        val stats = tracker.addPoint(1.0, baseLon, 1_000L, null, null)
        assertEquals(0.0, stats.distanceMeters, 0.001)
        // The teleport fix is rejected outright, so it never enters the track and
        // can't be drawn as a drift spike on the map.
        assertEquals(1, stats.pointCount)
    }

    @Test
    fun `low-accuracy drift fix is rejected entirely`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 5f)
        // A ~111 m jump but the fix reports 60 m accuracy → classic urban-canyon
        // drift. Must be dropped: no distance, no extra track point, no max speed.
        val stats = tracker.addPoint(0.001, baseLon, 3_000L, speedMps = 40f, altitudeMeters = null, accuracyMeters = 60f)
        assertEquals(0.0, stats.distanceMeters, 0.001)
        assertEquals(1, stats.pointCount)
        // Max speed stays 0: the lone first fix's reported speed has no position
        // baseline to corroborate it, and the drift fix was rejected outright.
        assertEquals(0.0, stats.maxSpeedMps, 0.001)
    }

    @Test
    fun `doppler speed spike that the track does not support is ignored for max speed`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Steady ~5.6 m/s ride: ~55.6 m (0.0005°) every 10 s.
        tracker.addPoint(0.0000, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 5f)
        // Reported 6 m/s is corroborated by the ~5.6 m/s geometry → counts.
        tracker.addPoint(0.0005, baseLon, 10_000L, speedMps = 6f, altitudeMeters = null, accuracyMeters = 5f)
        // GPS Doppler glitch: reports 20 m/s (72 km/h) while the position only moved
        // ~5.6 m/s — far above the corroboration tolerance, so it must NOT set the max.
        tracker.addPoint(0.0010, baseLon, 20_000L, speedMps = 20f, altitudeMeters = null, accuracyMeters = 5f)
        val ride = tracker.stop(20_000L)
        requireNotNull(ride)
        assertEquals("spike ignored, max is the corroborated 6 m/s", 6.0, ride.maxSpeedMps, 0.001)
    }

    @Test
    fun `accurate fix is accepted`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = null, altitudeMeters = null, accuracyMeters = 5f)
        // ~111 m in 10 s with a good 8 m accuracy → genuine movement, kept.
        val stats = tracker.addPoint(0.001, baseLon, 10_000L, speedMps = null, altitudeMeters = null, accuracyMeters = 8f)
        assertTrue("distance should be ~111 m", stats.distanceMeters in 100.0..120.0)
        assertEquals(2, stats.pointCount)
    }

    @Test
    fun `implausible acceleration spike is rejected even within the speed cap`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Establish a calm ~1.1 m/s baseline: ~11 m (0.0001°) every 10 s.
        tracker.addPoint(0.0000, baseLon, 0L, speedMps = 1f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.0001, baseLon, 10_000L, speedMps = 1f, altitudeMeters = null, accuracyMeters = 6f)
        val before = tracker.currentStats().distanceMeters
        // Next fix jumps ~15 m (0.000135°) in just 1 s → ~15 m/s and an acceleration
        // of ~14 m/s² from the 1.1 m/s baseline. Comfortably under the ~79 km/h
        // absolute cap but physically impossible for a bike → must be rejected by
        // the acceleration gate.
        val stats = tracker.addPoint(0.000235, baseLon, 11_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        assertEquals("drift spike adds no distance", before, stats.distanceMeters, 0.001)
        assertEquals("drift spike adds no track point", 2, stats.pointCount)
    }

    @Test
    fun `gross altitude spike does not inflate elevation`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Steady altitude ~100 m while riding, then a single GPS altitude spike to
        // 160 m (a +60 m jump, as seen on real rides) and back. The spike must be
        // rejected so it cannot inject phantom ascent.
        val altitudes = listOf(100.0, 100.5, 101.0, 160.0, 101.5, 102.0)
        altitudes.forEachIndexed { i, alt ->
            tracker.addPoint(0.0005 * i, baseLon, i * 5_000L, speedMps = 5f, altitudeMeters = alt)
        }
        val stats = tracker.currentStats()
        // The real trend rose only ~2 m (under the 3 m dead-band) → no phantom climb
        // from the 60 m spike.
        assertEquals(0.0, stats.elevationGainMeters, 0.001)
        assertEquals(0.0, stats.elevationLossMeters, 0.001)
    }

    @Test
    fun `burst fix within the minimum interval is dropped`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 5f)
        // A second fix only 30 ms later that moved ~8 m → an absurd ~270 m/s derived
        // speed. It is a GPS burst / duplicate and must be dropped outright so it
        // cannot appear as a spike at the end of the track.
        val stats = tracker.addPoint(0.00007, baseLon, 30L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 5f)
        assertEquals(0.0, stats.distanceMeters, 0.001)
        assertEquals(1, stats.pointCount)
    }

    @Test
    fun `genuine hard acceleration within physical limits is kept`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Standing start, then a strong but realistic sprint: ~0 → ~5.6 m/s over a
        // few seconds (~2 m/s²), well under the 6 m/s² gate → must be accepted.
        tracker.addPoint(0.00000, baseLon, 0L, speedMps = 0f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.00005, baseLon, 5_000L, speedMps = 1f, altitudeMeters = null, accuracyMeters = 6f) // ~1.1 m/s
        val stats = tracker.addPoint(0.00030, baseLon, 10_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f) // ~5.6 m/s
        assertEquals("legitimate sprint is kept", 3, stats.pointCount)
        assertTrue("distance keeps accumulating", stats.distanceMeters > 25.0)
    }

    @Test
    fun `stored positions are smoothed by the moving average while distance stays raw`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Three fixes marching north; the stored coordinate of each is the average
        // of the (up to 3) most recent raw fixes, so the second point sits at the
        // midpoint of the first two — visibly smoother than the raw zig-zag.
        // ~111 m every 10 s (~11 m/s) stays comfortably under the speed cap.
        tracker.addPoint(0.000, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.001, baseLon, 10_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        val ride = run {
            tracker.addPoint(0.002, baseLon, 20_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
            tracker.stop(20_000L)
        }
        requireNotNull(ride)
        // Point 0: window [0.000] → 0.000. Point 1: window [0,0.001] → 0.0005.
        // Point 2: window [0,0.001,0.002] → 0.001.
        assertEquals(0.0000, ride.points[0].latitude, 1e-9)
        assertEquals(0.0005, ride.points[1].latitude, 1e-9)
        assertEquals(0.0010, ride.points[2].latitude, 1e-9)
        // Distance is measured on the RAW fixes (0 → 0.001 → 0.002 ≈ 222 m), so the
        // smoothing has not shortened it.
        assertTrue("raw distance ~222 m", ride.distanceMeters in 210.0..235.0)
    }

    // ── Pause / resume (commute train/ferry legs) ─────────────────────────────

    @Test
    fun `paused fixes add no distance and no moving time`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.001, baseLon, 10_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        val distanceBeforePause = tracker.currentStats().distanceMeters

        // Board the train: pause, then a long fast leg that must be ignored entirely.
        tracker.pause(11_000L)
        tracker.addPoint(0.100, baseLon, 60_000L, speedMps = 30f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.200, baseLon, 120_000L, speedMps = 30f, altitudeMeters = null, accuracyMeters = 6f)
        val paused = tracker.currentStats(120_000L)
        assertTrue("recording is flagged paused", paused.isPaused)
        assertEquals("no distance added while paused", distanceBeforePause, paused.distanceMeters, 1e-6)
        assertEquals("no track points added while paused", 2, paused.pointCount)
    }

    @Test
    fun `resume starts a new track segment and excludes paused time`() {
        val tracker = RideTracker()
        tracker.start(0L)
        tracker.addPoint(baseLat, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.001, baseLon, 10_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)

        // 100 s train leg, ignored, then resume and continue riding.
        tracker.pause(10_000L)
        tracker.resume(110_000L)
        tracker.addPoint(0.002, baseLon, 120_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.003, baseLon, 130_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)

        val ride = tracker.stop(130_000L)
        requireNotNull(ride)
        // Third point (index 2) is the first fix after resume → begins a new segment.
        assertTrue("first resumed point begins a new segment", ride.points[2].segmentStart)
        assertTrue("earlier points are not segment starts",
            !ride.points[0].segmentStart && !ride.points[1].segmentStart)
        // The gap (0.001 → 0.002) must NOT be counted: only 0→0.001 and 0.002→0.003
        // contribute, ~111 m each ≈ 222 m total (not ~333 m).
        assertTrue("gap distance excluded (~222 m)", ride.distanceMeters in 200.0..245.0)
        // Elapsed excludes the 100 s pause: last point at 130 s − 100 s pause = 30 s.
        assertEquals("paused time excluded from elapsed", 30L, ride.elapsedSeconds)
    }

    // ── Recorded-ride elevation (Höhenmeter) regression ───────────────────────

    /**
     * Feeds a real altitude series through [RideTracker.addPoint] along a steadily
     * moving track (constant ~11 m/s, good 6 m accuracy) so no fix is dropped by the
     * distance/accuracy/acceleration gates and every altitude reaches the shared
     * [ElevationAccumulator]. Returns the final live stats.
     */
    private fun feedAltitudes(altitudes: List<Double>): LiveRideStats {
        val tracker = RideTracker()
        tracker.start(0L)
        var lat = baseLat
        var stats = tracker.currentStats()
        altitudes.forEachIndexed { i, alt ->
            stats = tracker.addPoint(
                latitude = lat,
                longitude = baseLon,
                timestamp = i * 3_000L,
                speedMps = 5f,
                altitudeMeters = alt,
                accuracyMeters = 6f
            )
            lat += 0.0003 // ~33 m north each fix → ~11 m/s, under the speed cap
        }
        return stats
    }

    @Test
    fun `net-descent ride counts realistic loss and does not zero the climb`() {
        // Ride abf570df (OLD stored gain=0.0, loss=6.49). The refactored accounting
        // must produce a realistic loss AND must not force the climb to exactly 0.
        val altitudes = ElevationFixtures.NET_DESCENT_ABF570DF
        val stats = feedAltitudes(altitudes)

        // The recorder must agree with the shared integrator on the same series.
        val expected = ElevationAccumulator.compute(altitudes.map { AltitudeSample(it) })
        assertEquals(expected.gainMeters, stats.elevationGainMeters, 1e-6)
        assertEquals(expected.lossMeters, stats.elevationLossMeters, 1e-6)

        assertTrue("loss well above the old 6.49 m (got ${stats.elevationLossMeters})",
            stats.elevationLossMeters > 6.0)
        assertTrue("loss stays physically sensible", stats.elevationLossMeters < 45.0)
        assertTrue("climb must not be zero-forced on a net descent (got ${stats.elevationGainMeters})",
            stats.elevationGainMeters > 3.0)
        assertTrue("loss dominates on a net descent",
            stats.elevationLossMeters > stats.elevationGainMeters)
    }

    @Test
    fun `net-climb ride counts realistic gain via the recorder`() {
        // Ride a46709f5 (OLD stored gain=6.36, loss=0.0). Gain is counted; loss is
        // legitimately ~0 as the mid-ride 50 m plateau is spike-gated.
        val altitudes = ElevationFixtures.NET_CLIMB_A46709F5
        val stats = feedAltitudes(altitudes)

        val expected = ElevationAccumulator.compute(altitudes.map { AltitudeSample(it) })
        assertEquals(expected.gainMeters, stats.elevationGainMeters, 1e-6)
        assertEquals(expected.lossMeters, stats.elevationLossMeters, 1e-6)

        assertTrue("gain realistic (got ${stats.elevationGainMeters})",
            stats.elevationGainMeters in 5.0..10.0)
        assertTrue("gain dominates on a net climb",
            stats.elevationGainMeters > stats.elevationLossMeters)
    }

    @Test
    fun `elevation accounting resumes after a gross altitude spike`() {
        // The pre-fix froze all further accounting once a > 12 m altitude step-change
        // was seen. Feed a clean climb, one 60 m spike, then keep climbing: the
        // accumulator must re-seed on the spike and RESUME counting afterwards.
        val tracker = RideTracker()
        tracker.start(0L)
        var lat = baseLat
        var t = 0L
        fun push(alt: Double) {
            tracker.addPoint(lat, baseLon, t, speedMps = 5f, altitudeMeters = alt, accuracyMeters = 6f)
            lat += 0.0003
            t += 3_000L
        }
        // Clean climb, then a single 60 m spike.
        listOf(100.0, 102.0, 104.0, 106.0, 160.0).forEach { push(it) }
        val gainAtSpike = tracker.currentStats().elevationGainMeters

        // Recover to normal altitude and keep climbing.
        listOf(108.0, 110.0, 112.0, 114.0).forEach { push(it) }
        val gainAfter = tracker.currentStats().elevationGainMeters

        assertTrue("some climb was counted before the spike", gainAtSpike > 2.0)
        assertTrue("accounting must resume after the spike (got $gainAtSpike → $gainAfter)",
            gainAfter > gainAtSpike + 1.0)
    }
}


