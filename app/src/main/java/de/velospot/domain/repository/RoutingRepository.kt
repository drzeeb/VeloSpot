package de.velospot.domain.repository

import de.velospot.domain.model.BikeRoute

interface RoutingRepository {
    suspend fun getBikeRoute(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): BikeRoute
}

