package de.velospot.core.map

import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSegmentsTest {

    @Test
    fun `a track without pauses is a single segment`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, 0L),
            TrackPoint(0.001, 0.0, 1_000L),
            TrackPoint(0.002, 0.0, 2_000L)
        )
        val segments = points.splitIntoSegments()
        assertEquals(1, segments.size)
        assertEquals(3, segments.first().size)
    }

    @Test
    fun `a segment-start point begins a new segment (gap)`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, 0L),
            TrackPoint(0.001, 0.0, 1_000L),
            TrackPoint(0.500, 0.0, 900_000L, segmentStart = true),
            TrackPoint(0.501, 0.0, 901_000L)
        )
        val segments = points.splitIntoSegments()
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
    }

    @Test
    fun `an empty track yields no segments`() {
        assertTrue(emptyList<TrackPoint>().splitIntoSegments().isEmpty())
    }

    @Test
    fun `a segment-start flag on the first point does not create an empty leading segment`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, 0L, segmentStart = true),
            TrackPoint(0.001, 0.0, 1_000L)
        )
        val segments = points.splitIntoSegments()
        assertEquals(1, segments.size)
        assertEquals(2, segments.first().size)
    }
}

