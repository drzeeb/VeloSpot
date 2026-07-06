package de.velospot.domain.repository

import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate

interface RoutingRepository {
    /**
     * Calculate a bike route between two geographic coordinates.
     *
     * @param from Starting position.
     * @param to   Destination position.
     * @return [BikeRoute] with geometry, distance and duration.
     * @throws de.velospot.domain.model.RoutingFailedException if the API reports an error.
     * @throws de.velospot.domain.model.NoRouteFoundException if no route is available.
     * @throws de.velospot.domain.model.EmptyRouteGeometryException if route geometry is empty.
     */
    suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute

    /**
     * Calculate a bike route that visits every coordinate in [waypoints] in order
     * (at least two: a start and a destination). The legs are routed pairwise and
     * concatenated into one continuous [BikeRoute] (geometry, distance, duration
     * and energy summed). Used for user-planned multi-waypoint routes; reversing a
     * planned route simply passes the reversed waypoint list.
     *
     * @throws IllegalArgumentException if fewer than two waypoints are given.
     * @throws de.velospot.domain.model.RoutingFailedException if any leg fails.
     */
    suspend fun getBikeRouteVia(waypoints: List<GeoCoordinate>): BikeRoute

    /**
     * Generate a circular round-trip route starting and ending at [from], roughly
     * [targetDistanceMeters] long. Offline-only (BRouter); throws
     * [de.velospot.domain.model.RoutingFailedException] when offline routing is
     * unavailable.
     */
    suspend fun getRoundTrip(from: GeoCoordinate, targetDistanceMeters: Double): BikeRoute
}

