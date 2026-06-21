package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideHeatmapTest {

    private fun ride(vararg points: Pair<Double, Double>) = RecordedRide(
        id = "r",
        startedAt = 0L,
        endedAt = 0L,
        distanceMeters = 0.0,
        elapsedSeconds = 0L,
        movingSeconds = 0L,
        avgSpeedMps = 0.0,
        maxSpeedMps = 0.0,
        elevationGainMeters = 0.0,
        elevationLossMeters = 0.0,
        points = points.map { TrackPoint(it.first, it.second, 0L) }
    )

    @Test
    fun `empty rides produce no cells`() {
        assertTrue(RideHeatmap.build(emptyList()).isEmpty())
    }

    @Test
    fun `points in the same grid cell are aggregated`() {
        // Two points ~1 m apart at 4-decimal grid (~11 m) collapse into one cell.
        val cells = RideHeatmap.build(listOf(ride(52.50000 to 13.40000, 52.50001 to 13.40001)))
        assertEquals(1, cells.size)
    }

    @Test
    fun `distant points produce separate cells`() {
        val cells = RideHeatmap.build(listOf(ride(52.5000 to 13.4000, 52.6000 to 13.5000)))
        assertEquals(2, cells.size)
    }

    @Test
    fun `intensity scales with repeated passes and saturates`() {
        // One pass through a cell.
        val single = RideHeatmap.build(
            listOf(ride(52.5 to 13.4)),
            saturationCount = 4
        ).first().intensity
        assertEquals(0.25, single, 1e-9)

        // Four passes reach full heat …
        val four = RideHeatmap.build(
            listOf(ride(52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4)),
            saturationCount = 4
        ).first().intensity
        assertEquals(1.0, four, 1e-9)

        // … and further passes don't exceed it.
        val many = RideHeatmap.build(
            listOf(ride(52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4, 52.5 to 13.4)),
            saturationCount = 4
        ).first().intensity
        assertEquals(1.0, many, 1e-9)
    }

    @Test
    fun `negative longitudes round-trip correctly`() {
        // West of Greenwich: ensure the packed grid key sign-extends back properly.
        val cells = RideHeatmap.build(listOf(ride(48.8566 to -2.3522)))
        assertEquals(1, cells.size)
        val cell = cells.first()
        assertEquals(48.8566, cell.latitude, 1e-4)
        assertEquals(-2.3522, cell.longitude, 1e-4)
    }
}

