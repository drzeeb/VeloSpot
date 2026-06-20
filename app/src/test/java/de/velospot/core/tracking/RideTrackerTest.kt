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
    }
}


