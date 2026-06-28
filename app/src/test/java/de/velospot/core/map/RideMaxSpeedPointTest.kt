package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideMaxSpeedPointTest {

    private fun ride(maxSpeedMps: Double, vararg points: TrackPoint) = RecordedRide(
        id = "r",
        startedAt = 0L,
        endedAt = 0L,
        distanceMeters = 0.0,
        elapsedSeconds = 0L,
        movingSeconds = 0L,
        avgSpeedMps = 0.0,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = 0.0,
        elevationLossMeters = 0.0,
        points = points.toList()
    )

    private fun point(lat: Double, lon: Double, speed: Float?) =
        TrackPoint(lat, lon, 0L, speedMps = speed)

    @Test
    fun `returns the fix whose speed matches the stored peak`() {
        val fast = point(52.52, 13.41, 8.6f)
        val peak = RideMaxSpeedPoint.find(
            ride(
                maxSpeedMps = 8.6,
                point(52.50, 13.40, 3.0f),
                fast,
                point(52.51, 13.42, 4.0f)
            )
        )
        assertEquals(fast, peak)
    }

    @Test
    fun `picks the closest speed when none equals the peak exactly`() {
        val closest = point(52.52, 13.41, 8.4f)
        val peak = RideMaxSpeedPoint.find(
            ride(
                maxSpeedMps = 8.6,
                point(52.50, 13.40, 3.0f),
                closest,
                point(52.51, 13.42, 5.0f)
            )
        )
        assertEquals(closest, peak)
    }

    @Test
    fun `null when no fix carries a speed sample`() {
        assertNull(
            RideMaxSpeedPoint.find(
                ride(
                    maxSpeedMps = 8.6,
                    point(52.50, 13.40, null),
                    point(52.51, 13.42, null)
                )
            )
        )
    }

    @Test
    fun `null when the ride has no positive peak`() {
        assertNull(
            RideMaxSpeedPoint.find(
                ride(maxSpeedMps = 0.0, point(52.50, 13.40, 0f))
            )
        )
    }
}

