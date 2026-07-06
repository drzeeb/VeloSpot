package de.velospot.core.analysis

import de.velospot.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGeometryStatsTest {

    private fun point(elevation: Double?) = RoutePoint(0.0, 0.0, elevation)

    @Test
    fun `sums positive deltas as gain and negative as loss`() {
        val (gain, loss) = RouteGeometryStats.elevationGainLoss(
            listOf(point(100.0), point(110.0), point(105.0), point(130.0))
        )
        // +10, -5, +25 → gain 35, loss 5
        assertEquals(35.0, gain, 0.001)
        assertEquals(5.0, loss, 0.001)
    }

    @Test
    fun `dead-band ignores sub-metre jitter`() {
        val (gain, loss) = RouteGeometryStats.elevationGainLoss(
            listOf(point(100.0), point(100.4), point(99.7), point(100.2)),
            deadBandMeters = 1.0
        )
        assertEquals(0.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }

    @Test
    fun `returns zero when no elevation data is present`() {
        val (gain, loss) = RouteGeometryStats.elevationGainLoss(
            listOf(point(null), point(null))
        )
        assertEquals(0.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }

    @Test
    fun `skips nodes without elevation between known ones`() {
        val (gain, loss) = RouteGeometryStats.elevationGainLoss(
            listOf(point(100.0), point(null), point(120.0))
        )
        assertEquals(20.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }
}

