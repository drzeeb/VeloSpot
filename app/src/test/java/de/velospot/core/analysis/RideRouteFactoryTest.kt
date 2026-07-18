package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRouteFactoryTest {

    private fun ride(
        points: List<TrackPoint>,
        isMock: Boolean = false
    ) = RecordedRide(
        id = "ride",
        startedAt = 0L,
        endedAt = 1_000L,
        distanceMeters = 1234.0,
        elapsedSeconds = 600L,
        movingSeconds = 550L,
        avgSpeedMps = 4.0,
        maxSpeedMps = 9.0,
        elevationGainMeters = 30.0,
        elevationLossMeters = 25.0,
        points = points,
        isMock = isMock
    )

    /** A north–south line of [count] points spaced ~[stepMeters] apart from a base. */
    private fun line(count: Int, stepMeters: Double = 200.0): List<TrackPoint> {
        val degPerMeter = 1.0 / 111_320.0 // ~metres per degree latitude
        return (0 until count).map { i ->
            TrackPoint(
                latitude = 49.75 + i * stepMeters * degPerMeter,
                longitude = 6.64,
                timestamp = i * 10_000L,
                altitudeMeters = 100.0 + i
            )
        }
    }

    @Test
    fun `geometry mirrors the track and keeps the endpoints as waypoints`() {
        val track = line(count = 20, stepMeters = 200.0)
        val route = RideRouteFactory.build(ride(track), name = "My loop", createdAt = 42L)!!

        assertEquals("My loop", route.name)
        assertEquals(42L, route.createdAt)
        // Geometry is the track verbatim.
        assertEquals(track.size, route.geometry.size)
        assertEquals(track.first().latitude, route.geometry.first().latitude, 0.0)
        // First and last waypoints are the track's endpoints.
        assertEquals(track.first().latitude, route.waypoints.first().latitude, 0.0)
        assertEquals(track.last().latitude, route.waypoints.last().latitude, 0.0)
        // Ride aggregates carry over.
        assertEquals(1234.0, route.distanceMeters, 0.0)
        assertEquals(30.0, route.elevationGainMeters, 0.0)
    }

    @Test
    fun `waypoints are sampled down but capped for very long rides`() {
        // 2000 points × 200 m ≈ 400 km: sampling must cap the waypoint count.
        val route = RideRouteFactory.build(ride(line(2_000)), name = "long", createdAt = 0L)!!
        assertTrue("at least start + end", route.waypoints.size >= 2)
        assertTrue("capped", route.waypoints.size <= 25)
    }

    @Test
    fun `a short loop keeps intermediate waypoints so the shape survives`() {
        // A 10-point line (~1.8 km) samples a handful of intermediate stops.
        val route = RideRouteFactory.build(ride(line(10, stepMeters = 250.0)), name = "loop", createdAt = 0L)!!
        assertTrue(route.waypoints.size >= 3)
    }

    @Test
    fun `mock rides cannot become routes`() {
        assertNull(RideRouteFactory.build(ride(line(20), isMock = true), name = "m", createdAt = 0L))
    }

    @Test
    fun `a track with fewer than two points cannot become a route`() {
        assertNull(RideRouteFactory.build(ride(line(1)), name = "x", createdAt = 0L))
        assertNull(RideRouteFactory.build(ride(emptyList()), name = "x", createdAt = 0L))
    }
}

