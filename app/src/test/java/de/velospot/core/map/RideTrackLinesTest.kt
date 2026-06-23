package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackLinesTest {

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
    fun `empty rides produce no polylines`() {
        assertTrue(RideTrackLines.build(emptyList()).isEmpty())
    }

    @Test
    fun `single-point rides are dropped`() {
        assertTrue(RideTrackLines.build(listOf(ride(52.5 to 13.4))).isEmpty())
    }

    @Test
    fun `each ride becomes its own polyline`() {
        val lines = RideTrackLines.build(
            listOf(
                ride(52.50 to 13.40, 52.51 to 13.41),
                ride(48.85 to 2.35, 48.86 to 2.36)
            )
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `collinear points are simplified away while endpoints are kept`() {
        // A straight east-west line sampled densely collapses to its two endpoints.
        val straight = ride(
            52.5 to 13.4000,
            52.5 to 13.4001,
            52.5 to 13.4002,
            52.5 to 13.4003,
            52.5 to 13.4004
        )
        val line = RideTrackLines.build(listOf(straight)).single()
        assertEquals(2, line.size)
        assertEquals(52.5 to 13.4000, line.first())
        assertEquals(52.5 to 13.4004, line.last())
    }

    @Test
    fun `a sharp corner is preserved`() {
        // An L-shaped path must keep its corner vertex (well beyond tolerance).
        val corner = ride(
            52.5000 to 13.4000,
            52.5000 to 13.4020,
            52.5020 to 13.4020
        )
        val line = RideTrackLines.build(listOf(corner)).single()
        assertEquals(3, line.size)
    }
}

