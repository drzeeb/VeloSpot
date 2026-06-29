package de.velospot.feature.map.presentation.ride

import de.velospot.domain.model.RecordedRide
import de.velospot.core.tracking.estimateRideCalories
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
        // 10 km total → CO2 = 10 * 120 g = 1200 g. Calories are now a physics-based
        // estimate (rolling + drag + climb), summed per ride, so we assert the total
        // matches the shared estimator rather than a flat kcal/km figure.
        val r = ride("a", now - dayMs, distanceMeters = 10_000.0)
        val stats = computeRideStatistics(listOf(r), now)
        assertEquals(1_200.0, stats.co2SavedGrams, 0.001)
        assertEquals(estimateRideCalories(r), stats.caloriesBurned)
        assertTrue(stats.caloriesBurned > 0)
        assertTrue(stats.earthCircumferencePercent > 0.0)
    }

    @Test
    fun `calories sum across rides and exclude mock rides`() {
        val real1 = ride("r1", now - 2 * dayMs, distanceMeters = 8_000.0, gain = 60.0)
        val real2 = ride("r2", now - 1 * dayMs, distanceMeters = 4_000.0, gain = 20.0)
        val mock = ride("m", now - dayMs, distanceMeters = 9_000.0).copy(isMock = true)
        val stats = computeRideStatistics(listOf(real1, real2, mock), now)
        assertEquals(
            estimateRideCalories(real1) + estimateRideCalories(real2),
            stats.caloriesBurned
        )
    }

    @Test
    fun `mock rides are excluded from statistics`() {
        val stats = computeRideStatistics(
            listOf(
                ride("real", now - dayMs, distanceMeters = 2_000.0),
                ride("mock", now - dayMs, distanceMeters = 9_000.0).copy(isMock = true)
            ),
            now
        )
        // Only the real ride counts; the mock ride is ignored entirely.
        assertEquals(1, stats.rideCount)
        assertEquals(2_000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(2_000.0, stats.longestRideMeters, 0.0)
    }

    @Test
    fun `history of only mock rides reports no data`() {
        val stats = computeRideStatistics(
            listOf(ride("mock", now - dayMs).copy(isMock = true)),
            now
        )
        assertFalse(stats.hasData)
        assertEquals(0, stats.rideCount)
    }
}

