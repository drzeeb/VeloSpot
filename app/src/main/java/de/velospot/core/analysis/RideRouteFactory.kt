package de.velospot.core.analysis

import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RouteWaypoint
import de.velospot.domain.model.TrackPoint
import java.util.UUID
import kotlin.math.max

/**
 * Turns a finished [RecordedRide] — a manual recording, a navigated ride or a
 * generated round trip — into a re-rideable [PlannedRoute] so it can be saved to
 * *My routes* and raced on its own leaderboard.
 *
 * A recorded ride is a **dense GPS track**, not a list of planned stops, so:
 *  - the [PlannedRoute.geometry] reuses the track verbatim (for drawing), while
 *  - the [PlannedRoute.waypoints] are **sampled** from the track at a roughly even
 *    spacing (capped in count), because riding a planned route re-routes BRouter
 *    through its waypoints. Too few would let the re-routed line drift far from the
 *    original; too many would make re-routing slow. The start and end points are
 *    always kept, which preserves the loop shape of a round trip (start ≈ end).
 *
 * Pure and side-effect-free so it is JVM-unit-testable.
 */
object RideRouteFactory {

    /** Target spacing between sampled waypoints; widened for long rides (see below). */
    private const val WAYPOINT_SPACING_METERS = 750.0

    /** Upper bound on sampled waypoints so re-routing a saved ride stays cheap. */
    private const val MAX_WAYPOINTS = 25

    /** A route needs at least a start and an end to be routable. */
    private const val MIN_TRACK_POINTS = 2

    /**
     * Builds a [PlannedRoute] from [ride], named [name] and stamped [createdAt].
     * Returns `null` when the ride can't become a route — a mock (simulator) ride
     * or a track with fewer than [MIN_TRACK_POINTS] points.
     */
    fun build(ride: RecordedRide, name: String, createdAt: Long): PlannedRoute? {
        if (ride.isMock) return null
        val track = ride.points
        if (track.size < MIN_TRACK_POINTS) return null

        val geometry = track.map { RoutePoint(it.latitude, it.longitude, it.altitudeMeters) }
        val waypoints = sampleWaypoints(track)
        if (waypoints.size < 2) return null

        return PlannedRoute(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { DEFAULT_NAME },
            waypoints = waypoints,
            geometry = geometry,
            distanceMeters = ride.distanceMeters,
            elevationGainMeters = ride.elevationGainMeters,
            elevationLossMeters = ride.elevationLossMeters,
            // A recorded ride carries no BRouter mechanical-energy figure.
            energyJoules = null,
            createdAt = createdAt
        )
    }

    /**
     * Samples the [track] into an ordered list of [RouteWaypoint]s spaced roughly
     * [WAYPOINT_SPACING_METERS] apart (widened so the count never exceeds
     * [MAX_WAYPOINTS]), always keeping the first and last point.
     */
    private fun sampleWaypoints(track: List<TrackPoint>): List<RouteWaypoint> {
        val first = track.first()
        val last = track.last()
        val total = totalDistanceMeters(track)
        // Widen the spacing on long rides so we never exceed MAX_WAYPOINTS stops.
        val spacing = max(WAYPOINT_SPACING_METERS, total / (MAX_WAYPOINTS - 1))

        val result = mutableListOf(RouteWaypoint(first.latitude, first.longitude))
        var accumulated = 0.0
        for (i in 1 until track.size - 1) {
            accumulated += GeoMath.distanceMeters(
                track[i - 1].latitude, track[i - 1].longitude,
                track[i].latitude, track[i].longitude
            )
            if (accumulated >= spacing) {
                result += RouteWaypoint(track[i].latitude, track[i].longitude)
                accumulated = 0.0
            }
        }
        result += RouteWaypoint(last.latitude, last.longitude)
        return result
    }

    private fun totalDistanceMeters(track: List<TrackPoint>): Double {
        var sum = 0.0
        for (i in 1 until track.size) {
            sum += GeoMath.distanceMeters(
                track[i - 1].latitude, track[i - 1].longitude,
                track[i].latitude, track[i].longitude
            )
        }
        return sum
    }

    private const val DEFAULT_NAME = "Route"
}

