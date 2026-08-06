package de.velospot.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class RideCo2Test {

    @Test
    fun `saved co2 scales linearly at 120 grams per km`() {
        assertEquals(CO2_GRAMS_SAVED_PER_KM, estimateRideCo2SavedGrams(1_000.0), 1e-6)
        // 10 km → 1200 g.
        assertEquals(1_200.0, estimateRideCo2SavedGrams(10_000.0), 1e-6)
        // 42.195 km → ~5063 g.
        assertEquals(5_063.4, estimateRideCo2SavedGrams(42_195.0), 1e-3)
    }

    @Test
    fun `zero or negative distance saves nothing`() {
        assertEquals(0.0, estimateRideCo2SavedGrams(0.0), 0.0)
        assertEquals(0.0, estimateRideCo2SavedGrams(-500.0), 0.0)
    }

    @Test
    fun `aggregating rides equals summing their distances`() {
        val distances = listOf(2_000.0, 3_500.0, 900.0)
        val perRide = distances.sumOf { estimateRideCo2SavedGrams(it) }
        val fromTotal = estimateRideCo2SavedGrams(distances.sum())
        assertEquals(fromTotal, perRide, 1e-6)
    }
}

