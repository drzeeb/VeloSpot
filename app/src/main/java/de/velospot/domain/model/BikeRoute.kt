package de.velospot.domain.model

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    /**
     * Terrain elevation in metres at this point, when the routing source provides
     * it (BRouter reads it from the SRTM data baked into its `.rd5` segment files).
     * `null` for sources without elevation data (e.g. the OSRM online fallback).
     */
    val elevationMeters: Double? = null
)

data class BikeRoute(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val source: RoutingSource = RoutingSource.OSRM_ONLINE
)

