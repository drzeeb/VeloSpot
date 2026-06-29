package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMapDataTest {

    /** Straight ride, 1 fix/second, ~5.566 m/step (≈ 20 km/h). */
    private fun straightRide(count: Int, withStop: Boolean = false): RecordedRide {
        val stepLat = 0.00005
        val points = (0 until count).map { i ->
            // Optionally inject a standstill in the middle (no movement, slow speed).
            val moving = !(withStop && i in (count / 2)..(count / 2 + 10))
            val lat = if (moving || i <= count / 2) i * stepLat else (count / 2) * stepLat
            TrackPoint(
                latitude = lat,
                longitude = 8.0,
                timestamp = i * 1_000L,
                speedMps = if (moving) 5.56f else 0.0f,
                altitudeMeters = 100.0 + i,
                accuracyMeters = 5f
            )
        }
        return RecordedRide(
            id = "r",
            startedAt = 0,
            endedAt = (count - 1) * 1_000L,
            distanceMeters = 5.566 * (count - 1),
            elapsedSeconds = (count - 1).toLong(),
            movingSeconds = (count - 1).toLong(),
            avgSpeedMps = 5.56,
            maxSpeedMps = 8.0,
            elevationGainMeters = 40.0,
            elevationLossMeters = 5.0,
            points = points
        )
    }

    @Test
    fun `degenerate ride yields no markers or frames`() {
        val data = buildRideMapData(straightRide(1))
        assertTrue(data.markers.isEmpty())
        assertTrue(data.replayFrames.isEmpty())
    }

    @Test
    fun `start and finish markers are present at the track ends`() {
        val data = buildRideMapData(straightRide(100))
        val start = data.markers.first { it.type == RideMarkerType.START }
        val finish = data.markers.first { it.type == RideMarkerType.FINISH }
        assertEquals(data.track.first(), start.point)
        assertEquals(data.track.last(), finish.point)
    }

    @Test
    fun `kilometre markers are produced for a multi-kilometre ride`() {
        // ~2.2 km → at least two km markers, labelled 1, 2, …
        val data = buildRideMapData(straightRide(400))
        val kms = data.markers.filter { it.type == RideMarkerType.KILOMETRE }
        assertTrue("expected ≥ 2 km markers, got ${kms.size}", kms.size >= 2)
        assertEquals("1", kms.first().label)
    }

    @Test
    fun `replay frames have the requested count and stay on the track`() {
        val data = buildRideMapData(straightRide(200), replayFrameCount = 300)
        assertEquals(300, data.replayFrames.size)
        // First frame at the start, last frame at the finish.
        assertEquals(data.track.first().latitude, data.replayFrames.first().latitude, 1e-6)
        assertEquals(data.track.last().latitude, data.replayFrames.last().latitude, 1e-6)
    }

    @Test
    fun `a standstill produces a stop marker`() {
        val data = buildRideMapData(straightRide(120, withStop = true))
        assertTrue(data.markers.any { it.type == RideMarkerType.STOP })
    }
}

