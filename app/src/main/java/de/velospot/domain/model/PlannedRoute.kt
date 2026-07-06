package de.velospot.domain.model

import kotlin.math.roundToInt

/**
 * A single stop the rider dropped while planning a multi-waypoint route. Stored
 * as plain coordinates (plus an optional human label) so a planned route is
 * self-contained and can be re-routed / reversed without any external lookup.
 */
data class RouteWaypoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
)

/**
 * A user-planned bike route through **two or more** [waypoints], saved so it can
 * be ridden again later and compared against previous attempts on a leaderboard.
 *
 * The [geometry] is the actual on-road polyline BRouter (or the OSRM fallback)
 * produced for the waypoints in order — cached so the route can be redrawn and
 * ridden offline without recomputing. The route is always stored in its
 * **forward** waypoint order; riding it *reversed* simply reverses the waypoint
 * list before routing, which is why reversed attempts live on their own
 * leaderboard (the ascent/descent — and therefore the achievable time — flips).
 *
 * @property energyJoules Mechanical work of the forward route from BRouter's
 *  kinematic model, or `null` for sources without it (the OSRM fallback).
 */
data class PlannedRoute(
    val id: String,
    val name: String,
    val waypoints: List<RouteWaypoint>,
    val geometry: List<RoutePoint>,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val energyJoules: Double? = null,
    val createdAt: Long
) {
    /** Rough calorie estimate for the forward route (see [BikeRoute.estimatedKcal]). */
    val estimatedKcal: Int? get() = energyJoules?.let { (it / 1000.0).roundToInt() }
}

/**
 * A completed ride of a [PlannedRoute] — one leaderboard entry. Attempts are the
 * same rider's own efforts (VeloSpot has no accounts / cloud), so a route's
 * leaderboard is a **personal best list**: the rider's attempts ranked by time.
 *
 * Forward and reverse rides are kept apart via [reversed]: because reversing a
 * route swaps its climbs and descents, mixing both directions into one ranking
 * would be unfair, so each direction has its own leaderboard.
 *
 * @property rideId Links back to the [RecordedRide] the attempt was derived from
 *  (so the full track / analysis stays reachable), or `null` if that ride was
 *  since deleted.
 */
data class RouteAttempt(
    val id: String,
    val routeId: String,
    val reversed: Boolean,
    val recordedAt: Long,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val rideId: String? = null
)

