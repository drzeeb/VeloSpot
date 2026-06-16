package de.velospot.core.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geodesic / interpolation maths used by the navigation engine.
 *
 * Deliberately free of any Android or MapLibre dependency so it can be unit
 * tested on the JVM and reused by [RouteMatcher] and [NavigationManager].
 *
 * All angles are in **degrees** unless noted; bearings are clockwise from true
 * north in the range `[0, 360)`.
 */
internal object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /** Great-circle distance between two coordinates in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) *
            sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees `[0, 360)` travelling from point 1 to point 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalizeDegrees(atan2(y, x) * RAD_TO_DEG)
    }

    /** Normalises any angle into the range `[0, 360)`. */
    fun normalizeDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Shortest signed difference `to - from` mapped to `(-180, 180]`.
     * Positive = clockwise. Used so bearing easing never spins the long way round.
     */
    fun shortestAngleDelta(from: Double, to: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    /**
     * Interpolates an angle from [from] towards [to] by fraction [t] (0..1)
     * along the shortest rotational path. Result is normalised to `[0, 360)`.
     */
    fun lerpAngle(from: Double, to: Double, t: Double): Double =
        normalizeDegrees(from + shortestAngleDelta(from, to) * t)

    /** Plain linear interpolation. */
    fun lerp(from: Double, to: Double, t: Double): Double = from + (to - from) * t

    /**
     * Result of projecting a point onto a line segment.
     *
     * @property t Clamped position along the segment, `0` = start, `1` = end.
     * @property latitude Latitude of the projected (closest) point.
     * @property longitude Longitude of the projected (closest) point.
     * @property distanceMeters Distance from the query point to the projection.
     */
    data class Projection(
        val t: Double,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double
    )

    /**
     * Projects ([lat], [lon]) onto the segment (a → b) using a local
     * equirectangular approximation (accurate for the short segments produced by
     * BRouter). Returns the clamped closest point on the segment.
     */
    fun projectOntoSegment(
        lat: Double, lon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Projection {
        // Local planar metres relative to point a; longitude scaled by cos(lat).
        val latScale = DEG_TO_RAD * EARTH_RADIUS_M
        val lonScale = latScale * cos(aLat * DEG_TO_RAD)

        val ax = 0.0
        val ay = 0.0
        val bx = (bLon - aLon) * lonScale
        val by = (bLat - aLat) * latScale
        val px = (lon - aLon) * lonScale
        val py = (lat - aLat) * latScale

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy

        val t = if (lenSq <= 1e-9) 0.0 else ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)

        val projLat = aLat + (bLat - aLat) * tc
        val projLon = aLon + (bLon - aLon) * tc
        val dist = distanceMeters(lat, lon, projLat, projLon)
        return Projection(tc, projLat, projLon, dist)
    }

    /** Absolute angular difference in `[0, 180]` degrees. */
    fun angularDistance(a: Double, b: Double): Double = abs(shortestAngleDelta(a, b))
}

