package de.velospot.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEnergyTest {

    @Test
    fun `zero distance burns nothing`() {
        assertEquals(0, estimateRideCalories(distanceMeters = 0.0, movingSeconds = 0, elevationGainMeters = 0.0))
    }

    @Test
    fun `flat ride at a realistic pace is in a sensible kcal range`() {
        // 10 km flat at 5 m/s (18 km/h), no climb. Rolling + drag only.
        val kcal = estimateRideCalories(
            distanceMeters = 10_000.0,
            movingSeconds = 2_000, // 10 km / 2000 s = 5 m/s
            elevationGainMeters = 0.0
        )
        // ~12–13 kcal/km for a flat ride — guard a plausible band.
        assertTrue("expected ~120 kcal, was $kcal", kcal in 100..160)
    }

    @Test
    fun `climbing increases the estimate`() {
        val flat = estimateRideCalories(10_000.0, 2_000, elevationGainMeters = 0.0)
        val hilly = estimateRideCalories(10_000.0, 2_000, elevationGainMeters = 200.0)
        assertTrue("hilly ($hilly) should exceed flat ($flat)", hilly > flat)
    }

    @Test
    fun `higher speed increases drag and the estimate`() {
        val slow = estimateRideCalories(10_000.0, movingSeconds = 2_000, elevationGainMeters = 0.0) // 5 m/s
        val fast = estimateRideCalories(10_000.0, movingSeconds = 1_000, elevationGainMeters = 0.0) // 10 m/s
        assertTrue("fast ($fast) should exceed slow ($slow)", fast > slow)
    }

    @Test
    fun `only net ascent costs energy, descent is free`() {
        val noElev = estimateRideCalories(5_000.0, 1_500, elevationGainMeters = 0.0)
        val descentOnly = estimateRideCalories(5_000.0, 1_500, elevationGainMeters = -50.0)
        // A negative gain must not subtract energy.
        assertEquals(noElev, descentOnly)
    }
}

