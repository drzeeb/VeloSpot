package de.velospot.core.map

import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSpeedSegmentsTest {

    private fun pt(lat: Double, lon: Double, speed: Float?) =
        TrackPoint(lat, lon, 0L, speedMps = speed)

    @Test
    fun `fewer than two points produce no segments`() {
        assertTrue(RideSpeedSegments.build(emptyList()).isEmpty())
        assertTrue(RideSpeedSegments.build(listOf(pt(52.5, 13.4, 5f))).isEmpty())
    }

    @Test
    fun `consecutive segments share an endpoint and cover the whole track`() {
        val points = listOf(
            pt(52.50, 13.40, 3f),
            pt(52.51, 13.41, 6f),
            pt(52.52, 13.42, 9f),
            pt(52.53, 13.43, 4f)
        )
        // Force one segment per step so continuity is easy to assert.
        val segments = RideSpeedSegments.build(points, targetSegments = points.size)
        // Each segment's last vertex equals the next segment's first vertex.
        for (i in 0 until segments.size - 1) {
            assertEquals(segments[i].line.last(), segments[i + 1].line.first())
        }
        // The union of vertices spans the original first and last points.
        assertEquals(52.50 to 13.40, segments.first().line.first())
        assertEquals(52.53 to 13.43, segments.last().line.last())
    }

    @Test
    fun `each segment carries the peak speed of the points it spans`() {
        val points = listOf(
            pt(52.50, 13.40, 3f),
            pt(52.51, 13.41, 9f),
            pt(52.52, 13.42, 4f)
        )
        val segments = RideSpeedSegments.build(points, targetSegments = points.size)
        // First slice spans points 0..1 → peak 9, second spans 1..2 → peak 9 then 4.
        assertEquals(9.0, segments.first().speedMps, 0.0001)
    }

    @Test
    fun `missing speed samples count as zero`() {
        val points = listOf(
            pt(52.50, 13.40, null),
            pt(52.51, 13.41, null)
        )
        val segments = RideSpeedSegments.build(points)
        assertEquals(1, segments.size)
        assertEquals(0.0, segments.first().speedMps, 0.0001)
    }

    @Test
    fun `a long track is reduced to at most the target number of segments`() {
        val points = (0 until 5_000).map { pt(52.5 + it * 1e-5, 13.4, 5f) }
        val segments = RideSpeedSegments.build(points, targetSegments = 100)
        assertTrue("got ${segments.size}", segments.size <= 100)
    }
}

