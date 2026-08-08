package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for finding #12: turn detection must catch rounded corners
 * that BRouter samples across several gentle vertices, where no single vertex
 * crosses the turn threshold but the accumulated heading change does.
 */
class RouteMatcherTurnDetectionTest {

    /**
     * Builds a densely-sampled route by walking from a start point. Each
     * [advance] first applies [turnDeg] to the heading (positive = right /
     * clockwise, negative = left) then moves [distanceM] along the new heading,
     * emitting one vertex — so a rounded corner is expressed as several small
     * same-sign turns, exactly like BRouter output.
     */
    private class PathBuilder(startLat: Double, startLon: Double, startBearingDeg: Double) {
        val points = mutableListOf(RoutePoint(startLat, startLon))
        private var bearing = startBearingDeg
        private var lat = startLat
        private var lon = startLon

        fun advance(distanceM: Double, turnDeg: Double = 0.0) {
            bearing += turnDeg
            val rad = Math.toRadians(bearing)
            val dNorth = distanceM * Math.cos(rad)
            val dEast = distanceM * Math.sin(rad)
            lat += dNorth / 111_195.0
            lon += dEast / (111_195.0 * Math.cos(Math.toRadians(lat)))
            points.add(RoutePoint(lat, lon))
        }
    }

    /** Snaps to a given route vertex and returns the next-turn hint from there. */
    private fun turnAt(route: List<RoutePoint>, vertexIndex: Int): RouteMatcher.TurnHint? {
        val p = route[vertexIndex]
        val match = RouteMatcher.match(route, p.latitude, p.longitude, fromSegment = vertexIndex)!!
        return RouteMatcher.nextTurn(route, match.segmentIndex, match.t)
    }

    @Test
    fun `rounded 90 degree corner fires exactly one left turn`() {
        val b = PathBuilder(49.0, 6.0, startBearingDeg = 90.0) // heading east
        repeat(5) { b.advance(20.0) }                          // straight approach
        repeat(5) { b.advance(5.0, turnDeg = -18.0) }          // rounded left 5×18° = 90°
        repeat(8) { b.advance(20.0) }                          // straight exit
        val route = b.points

        // From the approach the banner sees a single ~-90° (left) corner.
        val turn = turnAt(route, 1)
        assertNotNull("rounded corner must be detected", turn)
        assertTrue("expected a left turn (negative)", turn!!.angleDegrees < 0)
        assertTrue(
            "cumulative angle should approach -90°, was ${turn.angleDegrees}",
            turn.angleDegrees in -100.0..-70.0
        )
        // The apex sits ahead of us, at the corner — not at distance 0 or off-route.
        assertTrue("apex distance should be positive", turn.distanceMeters > 0)

        // Once the whole corner is behind us, no phantom second turn remains.
        assertNull("no further turn after the corner", turnAt(route, route.size - 3))
    }

    @Test
    fun `rounded corner reflected in turn sharpness for the camera`() {
        val b = PathBuilder(49.0, 6.0, startBearingDeg = 90.0)
        repeat(2) { b.advance(8.0) }                  // short straight so corner is within lookahead
        repeat(5) { b.advance(5.0, turnDeg = -18.0) } // rounded left 90°
        repeat(6) { b.advance(20.0) }
        val route = b.points

        val match = RouteMatcher.match(route, route[1].latitude, route[1].longitude, fromSegment = 1)!!
        assertTrue(
            "sharpness should reflect the whole corner, was ${match.turnSharpnessDegrees}",
            match.turnSharpnessDegrees > 45.0
        )
    }

    @Test
    fun `straight line with sub-threshold jitter fires no turn`() {
        val b = PathBuilder(49.0, 6.0, startBearingDeg = 90.0)
        // Alternating ±8° wobble: above the per-vertex noise floor but reversing
        // every vertex, so nothing ever accumulates to a real turn.
        for (k in 0 until 20) {
            b.advance(15.0, turnDeg = if (k % 2 == 0) 8.0 else -8.0)
        }
        val route = b.points

        assertNull("road noise must not fire a turn", turnAt(route, 1))
    }

    @Test
    fun `genuine sharp single-vertex turn still fires one`() {
        val b = PathBuilder(49.0, 6.0, startBearingDeg = 90.0)
        repeat(5) { b.advance(20.0) }
        b.advance(20.0, turnDeg = -90.0) // one sharp left vertex
        repeat(5) { b.advance(20.0) }
        val route = b.points

        val turn = turnAt(route, 1)
        assertNotNull(turn)
        assertTrue("expected a sharp left", turn!!.angleDegrees < -70.0)
        assertNull("only one turn on this route", turnAt(route, route.size - 3))
    }

    @Test
    fun `zig-zag fires two distinct maneuvers not a cancelled one`() {
        val b = PathBuilder(49.0, 6.0, startBearingDeg = 90.0)
        repeat(4) { b.advance(20.0) }                 // straight approach
        repeat(2) { b.advance(6.0, turnDeg = -25.0) } // rounded left 50°
        repeat(3) { b.advance(15.0) }                 // short straight between corners
        repeat(2) { b.advance(6.0, turnDeg = 25.0) }  // rounded right 50°
        repeat(5) { b.advance(20.0) }
        val route = b.points

        // First upcoming turn is the left one.
        val first = turnAt(route, 1)
        assertNotNull(first)
        assertTrue("first maneuver should be left", first!!.angleDegrees < 0)

        // After passing the left corner, the right one is next — not cancelled.
        val secondVertex = 4 + 2 + 2 // past the approach + left corner + a bit of straight
        val second = turnAt(route, secondVertex)
        assertNotNull("the right turn must still fire", second)
        assertTrue("second maneuver should be right", second!!.angleDegrees > 0)
    }
}

