package de.velospot.data.brouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationPreferenceTest {

    @Test
    fun `default preserves previous behaviour with zero extra cost`() {
        assertSame(ElevationPreference.ANY, ElevationPreference.DEFAULT)
        assertEquals(0, ElevationPreference.DEFAULT.uphillExtraCost)
    }

    @Test
    fun `extra uphill cost increases monotonically towards flatter levels`() {
        val costs = ElevationPreference.entries.map { it.uphillExtraCost }
        assertEquals(costs.sortedBy { it }, costs)
        // Each step must be strictly steeper than the previous.
        for (i in 1 until costs.size) {
            assertTrue("level $i should cost more than ${i - 1}", costs[i] > costs[i - 1])
        }
    }

    @Test
    fun `fromOrdinal maps valid positions and falls back to default`() {
        ElevationPreference.entries.forEachIndexed { index, level ->
            assertSame(level, ElevationPreference.fromOrdinal(index))
        }
        assertSame(ElevationPreference.DEFAULT, ElevationPreference.fromOrdinal(-1))
        assertSame(ElevationPreference.DEFAULT, ElevationPreference.fromOrdinal(999))
    }
}

