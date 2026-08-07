package de.velospot.core.health

import de.velospot.core.tracking.estimateRideCalories
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure Health Connect record blueprint builder
 * [HealthConnectRideMapper]. No Android / Health Connect I/O is exercised — only the
 * derived values that later become `ExerciseSessionRecord` / `DistanceRecord` / … .
 */
class HealthConnectRideMapperTest {

    private fun ride(
        id: String = "ride-42",
        name: String? = "Evening loop",
        distanceMeters: Double = 12_000.0,
        elevationGainMeters: Double = 150.0,
        points: List<TrackPoint> = listOf(
            point(0, 5.0f),
            point(1_000, null),   // no speed → filtered out
            point(2_000, 7.5f),
            point(3_000, -1.0f)   // negative → filtered out
        )
    ) = RecordedRide(
        id = id,
        startedAt = 1_000_000L,
        endedAt = 1_003_000L,
        distanceMeters = distanceMeters,
        elapsedSeconds = 3,
        movingSeconds = 3,
        avgSpeedMps = 4.0,
        maxSpeedMps = 7.5,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = 40.0,
        points = points,
        name = name
    )

    private fun point(offsetMillis: Long, speed: Float?) = TrackPoint(
        latitude = 49.75 + offsetMillis / 1_000_000.0,
        longitude = 6.64,
        timestamp = 1_000_000L + offsetMillis,
        speedMps = speed,
        altitudeMeters = 200.0
    )

    @Test
    fun `builds the expected core values`() {
        val r = ride()
        val data = HealthConnectRideMapper.buildRideData(r)

        assertEquals(r.startedAt, data.startTimeMillis)
        assertEquals(r.endedAt, data.endTimeMillis)
        assertEquals("Evening loop", data.title)
        assertEquals(12_000.0, data.distanceMeters, 0.0)
        assertEquals(150.0, data.elevationGainMeters, 0.0)
        // Energy must reuse the shared estimator, not a re-implementation.
        assertEquals(estimateRideCalories(r), data.energyKilocalories)
    }

    @Test
    fun `speed samples skip null and negative speeds`() {
        val data = HealthConnectRideMapper.buildRideData(ride())
        // Only the two non-null, non-negative samples survive.
        assertEquals(2, data.speedSamples.size)
        assertTrue(data.hasSpeedSamples)
        assertEquals(5.0, data.speedSamples[0].metersPerSecond, 0.0)
        assertEquals(7.5, data.speedSamples[1].metersPerSecond, 0.0)
        assertEquals(1_000_000L, data.speedSamples[0].timeMillis)
        assertEquals(1_002_000L, data.speedSamples[1].timeMillis)
    }

    @Test
    fun `no usable speeds yields no speed record`() {
        val data = HealthConnectRideMapper.buildRideData(
            ride(points = listOf(point(0, null), point(1_000, null)))
        )
        assertTrue(data.speedSamples.isEmpty())
        assertFalse(data.hasSpeedSamples)
    }

    @Test
    fun `client record ids are derived from the ride id and namespaced`() {
        val data = HealthConnectRideMapper.buildRideData(ride(id = "abc-123"))
        assertEquals("velospot-exercise-abc-123", data.exerciseClientRecordId)
        assertEquals("velospot-distance-abc-123", data.distanceClientRecordId)
        assertEquals("velospot-energy-abc-123", data.energyClientRecordId)
        assertEquals("velospot-elevation-abc-123", data.elevationClientRecordId)
        assertEquals("velospot-speed-abc-123", data.speedClientRecordId)
    }

    @Test
    fun `zero distance and elevation suppress their records`() {
        val data = HealthConnectRideMapper.buildRideData(
            ride(distanceMeters = 0.0, elevationGainMeters = 0.0)
        )
        assertFalse(data.hasDistance)
        assertFalse(data.hasElevationGain)
    }

    @Test
    fun `blank name becomes null title`() {
        val data = HealthConnectRideMapper.buildRideData(ride(name = "   "))
        assertEquals(null, data.title)
    }
}

