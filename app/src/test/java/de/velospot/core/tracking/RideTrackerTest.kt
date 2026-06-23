package de.velospot.core.tracking

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
    fun `stored positions are smoothed by the moving average while distance stays raw`() {
        val tracker = RideTracker()
        tracker.start(0L)
        // Three fixes marching north; the stored coordinate of each is the average
        // of the (up to 3) most recent raw fixes, so the second point sits at the
        // midpoint of the first two — visibly smoother than the raw zig-zag.
        tracker.addPoint(0.000, baseLon, 0L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        tracker.addPoint(0.001, baseLon, 5_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
        val ride = run {
            tracker.addPoint(0.002, baseLon, 10_000L, speedMps = 5f, altitudeMeters = null, accuracyMeters = 6f)
            tracker.stop(10_000L)
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
}


