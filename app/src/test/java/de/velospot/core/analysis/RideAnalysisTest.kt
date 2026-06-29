package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideAnalysisTest {

    /**
     * Builds a straight south-to-north ride of [count] fixes, one second apart,
     * each ~5.56 m (≈ 20 km/h), starting at the equator on a fixed longitude.
     */
    private fun straightRide(count: Int): RecordedRide {
        val stepLat = 0.00005 // ~5.566 m per step
        val points = (0 until count).map { i ->
            TrackPoint(
                latitude = i * stepLat,
                longitude = 8.0,
                timestamp = i * 1_000L,
                speedMps = 5.56f,
                altitudeMeters = 100.0 + i, // gently climbing
                accuracyMeters = 5f
            )
        }
        val elapsed = (count - 1).toLong()
        return RecordedRide(
            id = "r",
            startedAt = 0,
            endedAt = elapsed * 1_000,
            distanceMeters = 5.566 * (count - 1),
            elapsedSeconds = elapsed,
            movingSeconds = elapsed - 10, // pretend 10 s standstill
            avgSpeedMps = 5.56,
            maxSpeedMps = 8.0,
            elevationGainMeters = 40.0,
            elevationLossMeters = 5.0,
            points = points
        )
    }

    @Test
    fun `too few points yields no splits but valid headline figures`() {
        val ride = straightRide(1)
        val a = analyzeRide(ride)
        assertTrue(a.splits.isEmpty())
        assertEquals(ride.distanceMeters, a.distanceMeters, 0.0)
        assertEquals(ride.movingSeconds, a.movingSeconds)
    }

    @Test
    fun `splits are roughly one kilometre each`() {
        // ~2.2 km ride → at least two full splits.
        val ride = straightRide(400)
        val a = analyzeRide(ride)
        assertTrue("expected ≥ 2 splits, got ${a.splits.size}", a.splits.size >= 2)
        val firstFull = a.splits.first()
        assertTrue("full km distance ~1000 m, was ${firstFull.distanceMeters}",
            firstFull.distanceMeters in 980.0..1060.0)
        assertTrue(firstFull.isFull)
        // ~20 km/h → ~5.56 m/s.
        assertTrue("avg ~5.56 m/s, was ${firstFull.avgSpeedMps}",
            firstFull.avgSpeedMps in 5.0..6.2)
    }

    @Test
    fun `stopped time is elapsed minus moving`() {
        val ride = straightRide(200)
        val a = analyzeRide(ride)
        assertEquals(ride.elapsedSeconds - ride.movingSeconds, a.stoppedSeconds)
    }

    @Test
    fun `speed histogram concentrates around the ridden speed`() {
        val ride = straightRide(300)
        val a = analyzeRide(ride)
        val total = a.speedHistogram.sumOf { it.seconds }
        assertTrue("histogram should cover most of the ride", total > 250)
        // 20 km/h falls in the 20–25 band (index 4).
        val peak = a.speedHistogram.maxByOrNull { it.seconds }!!
        assertEquals(20, peak.fromKmh)
    }

    @Test
    fun `calories are positive for a real ride`() {
        val a = analyzeRide(straightRide(400))
        assertTrue(a.caloriesKcal > 0)
    }
}
