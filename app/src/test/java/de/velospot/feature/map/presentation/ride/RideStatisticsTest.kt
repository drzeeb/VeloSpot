package de.velospot.feature.map.presentation.ride

import de.velospot.domain.model.RecordedRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RideStatisticsTest {

    private val dayMs = TimeUnit.DAYS.toMillis(1)

    /** A fixed "now" so week/month/streak buckets are deterministic. */
    private val now = TimeUnit.DAYS.toMillis(20_000) // ~2024, midday-agnostic

    private fun ride(
        id: String,
        startedAt: Long,
        distanceMeters: Double = 1_000.0,
        elapsedSeconds: Long = 600,
        movingSeconds: Long = 600,
        avgSpeedMps: Double = distanceMeters / movingSeconds,
        maxSpeedMps: Double = 8.0,
        gain: Double = 50.0,
        loss: Double = 40.0
    ) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + elapsedSeconds * 1_000,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = gain,
        elevationLossMeters = loss,
        points = emptyList()
    )

    @Test
    fun `empty history has no data and zeroed metrics`() {
        val stats = computeRideStatistics(emptyList(), now)
        assertFalse(stats.hasData)
        assertEquals(0, stats.rideCount)
        assertEquals(0.0, stats.totalDistanceMeters, 0.0)
        assertEquals(0, stats.activeDays)
    }

    @Test
    fun `totals sum across all rides`() {
        val stats = computeRideStatistics(
            listOf(
                ride("a", now - 2 * dayMs, distanceMeters = 1_000.0, gain = 30.0, loss = 20.0),
                ride("b", now - 1 * dayMs, distanceMeters = 3_000.0, gain = 70.0, loss = 60.0)
            ),
            now
        )
        assertTrue(stats.hasData)
        assertEquals(2, stats.rideCount)
        assertEquals(4_000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(100.0, stats.totalElevationGainMeters, 0.0)
        assertEquals(80.0, stats.totalElevationLossMeters, 0.0)
        assertEquals(2_000.0, stats.avgDistanceMeters, 0.0)
    }

    @Test
    fun `records pick the maxima`() {
        val stats = computeRideStatistics(
            listOf(
                ride("a", now - 3 * dayMs, distanceMeters = 5_000.0, maxSpeedMps = 12.0, gain = 200.0),
                ride("b", now - 2 * dayMs, distanceMeters = 2_000.0, maxSpeedMps = 9.0, gain = 50.0)
            ),
            now
        )
        assertEquals(5_000.0, stats.longestRideMeters, 0.0)
        assertEquals(12.0, stats.topSpeedMps, 0.0)
        assertEquals(200.0, stats.biggestClimbMeters, 0.0)
    }

    @Test
    fun `active days counts distinct calendar days`() {
        val sameDay = now - 5 * dayMs
        val stats = computeRideStatistics(
            listOf(
                ride("a", sameDay),
                ride("b", sameDay + 3_600_000), // one hour later, same day
                ride("c", now - 4 * dayMs)
            ),
            now
        )
        assertEquals(2, stats.activeDays)
    }

    @Test
    fun `current streak counts consecutive days ending today`() {
        val stats = computeRideStatistics(
            listOf(
                ride("a", now),
                ride("b", now - 1 * dayMs),
                ride("c", now - 2 * dayMs),
                // gap on day -3
                ride("d", now - 4 * dayMs)
            ),
            now
        )
        assertEquals(3, stats.currentStreakDays)
        assertEquals(3, stats.longestStreakDays)
    }

    @Test
    fun `current streak is zero when last ride is older than yesterday`() {
        val stats = computeRideStatistics(
            listOf(ride("a", now - 5 * dayMs)),
            now
        )
        assertEquals(0, stats.currentStreakDays)
        assertEquals(1, stats.longestStreakDays)
    }

    @Test
    fun `fun facts scale with total distance`() {
        // 10 km total → CO2 = 10 * 120 g = 1200 g; calories = 10 * 30 = 300 kcal.
        val stats = computeRideStatistics(
            listOf(ride("a", now - dayMs, distanceMeters = 10_000.0)),
            now
        )
        assertEquals(1_200.0, stats.co2SavedGrams, 0.001)
        assertEquals(300, stats.caloriesBurned)
        assertTrue(stats.earthCircumferencePercent > 0.0)
    }
}

