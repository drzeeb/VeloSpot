package de.velospot.domain.model

/**
 * Represents a geographic position with latitude and longitude.
 * Replaces raw [Pair] usage across location and routing APIs.
 *
 * @property bearing Optional GPS heading in degrees `[0, 360)` (clockwise from
 *  true north). `null` when the fix carries no bearing (e.g. while standing
 *  still or for last-known fixes). Used to point the navigation arrow.
 * @property speedMetersPerSecond Optional ground speed in m/s. `null` when the
 *  fix carries no speed. Drives the speed-dependent navigation zoom.
 * @property altitudeMeters Optional altitude above the WGS84 ellipsoid in metres.
 *  `null` when the fix carries no altitude. Used by the ride tracker to estimate
 *  elevation gain/loss.
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val altitudeMeters: Double? = null
)

