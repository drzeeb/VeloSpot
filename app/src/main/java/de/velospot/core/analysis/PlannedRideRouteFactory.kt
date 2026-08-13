package de.velospot.core.analysis

import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RoutingDefaults
import de.velospot.domain.model.RoutingSource

/**
 * Turns a saved [PlannedRoute] into the exact [BikeRoute] that should be *ridden*,
 * reusing the route's stored on-road [PlannedRoute.geometry] verbatim instead of
 * re-routing it from the rider's current position.
 *
 * This is what makes a leaderboard fair: every attempt of a route follows the same
 * polyline (the one computed and cached when the route was planned), so times are
 * comparable. Re-routing on each ride could pick a different road (different
 * profile/version, online OSRM vs. offline BRouter) and invalidate the comparison.
 *
 * Riding a route **reversed** simply reverses the geometry. The stored
 * [PlannedRoute.energyJoules] is kept as an approximation for the reverse direction
 * too (the exact mechanical work differs because climbs and descents swap, but the
 * route carries only the forward figure — it is used purely as a rough kcal
 * estimate, not for navigation).
 *
 * Pure and side-effect-free so it is JVM-unit-testable.
 */
object PlannedRideRouteFactory {

    /** A ridable route needs at least a start and an end. */
    private const val MIN_GEOMETRY_POINTS = 2

    /**
     * Builds the [BikeRoute] to ride for [route] in the given direction. Returns
     * `null` when the stored geometry has fewer than [MIN_GEOMETRY_POINTS] points
     * (nothing to follow).
     */
    fun build(route: PlannedRoute, reversed: Boolean): BikeRoute? {
        val geometry = route.geometry
        if (geometry.size < MIN_GEOMETRY_POINTS) return null
        val points = if (reversed) geometry.reversed() else geometry

        // No per-node timing is stored on a PlannedRoute, so leave it null: live
        // navigation then falls back to distance ÷ average pace for its ETA (see
        // NavigationManager.DEFAULT_BIKE_SPEED_MPS). Provide a non-zero total
        // duration derived from the same default pace so nothing divides by zero.
        val durationSeconds =
            route.distanceMeters / RoutingDefaults.DEFAULT_CYCLING_SPEED_MPS

        return BikeRoute(
            points = points,
            distanceMeters = route.distanceMeters,
            durationSeconds = durationSeconds,
            // The stored geometry may come from either engine; tag it as offline
            // BRouter, which is the source that produces the cached on-road line
            // with elevation for a planned route.
            source = RoutingSource.BROUTER_OFFLINE,
            energyJoules = route.energyJoules,
            cumulativeTimesSeconds = null
        )
    }
}

