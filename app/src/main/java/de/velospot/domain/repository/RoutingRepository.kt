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
}

