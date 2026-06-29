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
            // Optionally inject a ~26 s standstill in the middle (no movement).
            val moving = !(withStop && i in (count / 2)..(count / 2 + 25))
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
    fun `no kilometre split markers are placed on the map`() {
        // KILOMETRE markers were removed; only meaningful value markers remain.
        val data = buildRideMapData(straightRide(400))
        val types = data.markers.map { it.type }.toSet()
        assertTrue(types.all {
            it in setOf(
                RideMarkerType.START, RideMarkerType.FINISH, RideMarkerType.TOP_SPEED,
                RideMarkerType.MAX_GRADIENT, RideMarkerType.HIGH_POINT, RideMarkerType.STOP
            )
        })
    }

    @Test
    fun `a top-speed marker is placed when the ride has a peak speed`() {
        val data = buildRideMapData(straightRide(100))
        assertTrue(data.markers.any { it.type == RideMarkerType.TOP_SPEED })
    }

    @Test
    fun `steepest and high-point markers carry a value label on a climbing ride`() {
        // straightRide gains ~1 m every ~5.57 m → a steep, climbing ride.
        val data = buildRideMapData(straightRide(400))
        val steep = data.markers.firstOrNull { it.type == RideMarkerType.MAX_GRADIENT }
        val high = data.markers.firstOrNull { it.type == RideMarkerType.HIGH_POINT }
        assertTrue("expected a steepest-gradient marker", steep != null)
        assertTrue("steepest marker should carry a % label", steep!!.label!!.endsWith("%"))
        assertTrue("expected a high-point marker", high != null)
        assertTrue("high-point marker should carry an elevation label", high!!.label!!.endsWith("m"))
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
    fun `a long standstill produces a stop marker with its duration`() {
        val data = buildRideMapData(straightRide(160, withStop = true))
        val stop = data.markers.firstOrNull { it.type == RideMarkerType.STOP }
        assertTrue("expected a stop marker", stop != null)
        assertTrue("stop marker should carry a duration label", stop!!.label != null)
    }
}

