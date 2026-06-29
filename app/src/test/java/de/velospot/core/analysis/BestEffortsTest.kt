package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BestEffortsTest {

    /**
     * Straight ride, one fix per second. [stepMeters] per second → constant speed.
     */
    private fun straightRide(count: Int, stepMeters: Double = 5.566): RecordedRide {
        // ~0.00005° latitude ≈ 5.566 m; scale to the requested step.
        val stepLat = 0.00005 * (stepMeters / 5.566)
        val points = (0 until count).map { i ->
            TrackPoint(
                latitude = i * stepLat,
                longitude = 8.0,
                timestamp = i * 1_000L,
                speedMps = stepMeters.toFloat()
            )
        }
        return RecordedRide(
            id = "r",
            startedAt = 0,
            endedAt = (count - 1) * 1_000L,
            distanceMeters = stepMeters * (count - 1),
            elapsedSeconds = (count - 1).toLong(),
            movingSeconds = (count - 1).toLong(),
            avgSpeedMps = stepMeters,
            maxSpeedMps = stepMeters,
            elevationGainMeters = 0.0,
            elevationLossMeters = 0.0,
            points = points
        )
    }

    @Test
    fun `too-short ride yields no efforts`() {
        // ~600 m total → below the 1 km minimum, and only 100 s (1 min effort ok).
        val efforts = computeBestEfforts(straightRide(101, stepMeters = 6.0))
        assertTrue(efforts.fastestDistances.isEmpty())
    }

    @Test
    fun `only the distances the ride is long enough for are listed`() {
        // ~12 km ride → 1 km, 5 km, 10 km (not 20/40/100).
        val efforts = computeBestEfforts(straightRide(2001, stepMeters = 6.0))
        val targets = efforts.fastestDistances.map { it.distanceMeters }
        assertTrue(1_000.0 in targets)
        assertTrue(10_000.0 in targets)
        assertTrue(20_000.0 !in targets)
    }

    @Test
    fun `fastest 1 km time matches the constant speed`() {
        // 6 m/s constant → 1000 m takes ~166.7 s.
        val efforts = computeBestEfforts(straightRide(2001, stepMeters = 6.0))
        val oneKm = efforts.fastestDistances.first { it.distanceMeters == 1_000.0 }
        assertEquals(1_000.0 / 6.0, oneKm.elapsedSeconds, 2.0)
        assertEquals(6.0, oneKm.avgSpeedMps, 0.1)
    }

    @Test
    fun `best 1-minute distance matches the constant speed`() {
        // 6 m/s for 60 s → ~360 m.
        val efforts = computeBestEfforts(straightRide(2001, stepMeters = 6.0))
        val oneMin = efforts.bestDurations.first { it.seconds == 60L }
        assertEquals(360.0, oneMin.distanceMeters, 7.0)
        assertEquals(6.0, oneMin.avgSpeedMps, 0.1)
    }

    @Test
    fun `a faster middle stretch is picked as the best effort`() {
        // 2000 points at 5 m/s, but a 200 s burst at 10 m/s in the middle.
        val points = ArrayList<TrackPoint>()
        var lat = 0.0
        for (i in 0 until 2000) {
            val fast = i in 800..1000
            val step = if (fast) 10.0 else 5.0
            lat += step * (0.00005 / 5.566)
            points += TrackPoint(lat, 8.0, i * 1_000L, step.toFloat())
        }
        val ride = RecordedRide(
            "r", 0, 1_999_000, 0.0, 1_999, 1_999, 6.0, 10.0, 0.0, 0.0, points
        )
        val efforts = computeBestEfforts(ride)
        // The best 1-min window should sit near the 10 m/s burst (~600 m).
        val oneMin = efforts.bestDurations.first { it.seconds == 60L }
        assertTrue("best 1-min should reflect the fast burst", oneMin.avgSpeedMps > 9.0)
    }
}

