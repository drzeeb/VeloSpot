package de.velospot.domain.model

/**
 * Represents a geographic position with latitude and longitude.
 * Replaces raw [Pair] usage across location and routing APIs.
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
)

