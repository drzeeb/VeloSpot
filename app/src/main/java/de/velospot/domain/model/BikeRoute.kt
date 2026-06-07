package de.velospot.domain.model

data class RoutePoint(
    val latitude: Double,
    val longitude: Double
)

data class BikeRoute(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

