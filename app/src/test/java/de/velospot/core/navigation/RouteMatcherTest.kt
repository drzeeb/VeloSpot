package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMatcherTest {

    // A simple L-shaped route: east along a row, then north.
    private val route = listOf(
        RoutePoint(49.0000, 6.0000),
        RoutePoint(49.0000, 6.0020),
        RoutePoint(49.0000, 6.0040),
        RoutePoint(49.0020, 6.0040)
    )

    @Test
    fun `returns null for a degenerate route`() {
        assertNull(RouteMatcher.match(listOf(RoutePoint(49.0, 6.0)), 49.0, 6.0))
    }

    @Test
    fun `snaps an off-road fix back onto the polyline`() {
        // Fix ~ a few metres north of the eastbound first segment.
        val match = RouteMatcher.match(route, 49.0002, 6.0010)!!
        assertEquals(49.0, match.latitude, 1e-4)
        // Snapped point sits on the segment, well within a few metres of the line.
        assertTrue(match.distanceFromRouteMeters < 30.0)
        assertEquals(0, match.segmentIndex)
        // Heading of an eastbound segment is ~90°.
        assertEquals(90.0, match.bearing, 1.0)
    }

    @Test
    fun `remaining distance decreases as we progress`() {
        val near = RouteMatcher.match(route, 49.0000, 6.0005)!!.remainingMeters
        val far  = RouteMatcher.match(route, 49.0000, 6.0035)!!.remainingMeters
        assertTrue("remaining should shrink moving forward", far < near)
    }

    @Test
    fun `detects the sharp turn at the corner`() {
        // Approaching the 90° left turn at the L's corner.
        val match = RouteMatcher.match(route, 49.0000, 6.0038)!!
        assertTrue("expected an upcoming sharp turn", match.turnSharpnessDegrees > 45.0)
    }

    @Test
    fun `forward bias keeps matching along the route`() {
        // Even though the corner is geometrically close to both segments, passing
        // fromSegment keeps the match forward-biased.
        val match = RouteMatcher.match(route, 49.0010, 6.0040, fromSegment = 2)!!
        assertEquals(2, match.segmentIndex)
        // Northbound segment heading ~0°/360°.
        val b = match.bearing
        assertTrue("expected ~north heading", b < 5.0 || b > 355.0)
    }
}

