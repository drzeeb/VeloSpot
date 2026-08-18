package de.velospot.feature.bikeprofiles.presentation

import de.velospot.domain.model.RecordedRideSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [BikeStatsCalculator] — the per-bike aggregation that feeds both
 * the garage list and the shareable bike "Sharepic".
 */
class BikeStatsCalculatorTest {

    private fun ride(
        id: String,
        distance: Double,
        movingSeconds: Long = 0,
        gain: Double = 0.0,
        maxSpeed: Double = 0.0,
        startedAt: Long = 0
    ) = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 1,
        distanceMeters = distance,
        elapsedSeconds = movingSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = 0.0,
        maxSpeedMps = maxSpeed,
        elevationGainMeters = gain,
        elevationLossMeters = 0.0
    )

    @Test
    fun `empty rides yield zeroed stats`() {
        val stats = BikeStatsCalculator.aggregate(emptyList())
        assertEquals(0, stats.rideCount)
        assertEquals(0.0, stats.totalDistanceMeters, 0.0)
        assertEquals(0.0, stats.longestRideMeters, 0.0)
        assertEquals(0.0, stats.topSpeedMps, 0.0)
        assertEquals(null, stats.firstRideAt)
        assertEquals(null, stats.lastRideAt)
    }

    @Test
    fun `aggregates totals, extremes and date range`() {
        val stats = BikeStatsCalculator.aggregate(
            listOf(
                ride("a", distance = 10_000.0, movingSeconds = 1_800, gain = 120.0, maxSpeed = 8.0, startedAt = 3_000),
                ride("b", distance = 42_000.0, movingSeconds = 7_200, gain = 300.0, maxSpeed = 15.5, startedAt = 1_000),
                ride("c", distance = 5_000.0, movingSeconds = 900, gain = 40.0, maxSpeed = 12.0, startedAt = 5_000)
            )
        )
        assertEquals(3, stats.rideCount)
        assertEquals(57_000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(9_900L, stats.totalMovingSeconds)
        assertEquals(460.0, stats.totalElevationGainMeters, 0.0)
        assertEquals(42_000.0, stats.longestRideMeters, 0.0)
        assertEquals(15.5, stats.topSpeedMps, 0.0)
        assertEquals(1_000L, stats.firstRideAt)
        assertEquals(5_000L, stats.lastRideAt)
    }
}

