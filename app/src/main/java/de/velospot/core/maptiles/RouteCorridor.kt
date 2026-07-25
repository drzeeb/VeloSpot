package de.velospot.core.maptiles

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for the **route-corridor** offline download (feature 2.1): turning a
 * planned route's polyline into the set of map-tile boxes and BRouter routing tiles
 * that must be fetched so the *whole* route works offline — not just a single 40 km
 * box around the start.
 *
 * Kept free of Android/MapLibre types so the corridor maths is covered by plain JVM
 * unit tests (`RouteCorridorTest`).
 */
object RouteCorridor {

    /** Max ground span (km) of a single corridor box before the route is split into a new one. */
    const val DEFAULT_BOX_SPAN_KM = 25.0

    /** Half-width (km) padded around the route line so nearby streets are cached too. */
    const val DEFAULT_CORRIDOR_RADIUS_KM = 3.0

    private const val KM_PER_DEGREE_LAT = 111.0

    /**
     * Splits [points] (lat, lon) along the route into a series of overlapping bounding
     * boxes, each no larger than roughly [boxSpanKm] on the ground and padded by
     * [corridorRadiusKm], so together they tile the whole route corridor. Returns an
     * empty list for an empty route.
     */
    fun corridorBoxes(
        points: List<Pair<Double, Double>>,
        boxSpanKm: Double = DEFAULT_BOX_SPAN_KM,
        corridorRadiusKm: Double = DEFAULT_CORRIDOR_RADIUS_KM,
    ): List<GeoBounds> {
        if (points.isEmpty()) return emptyList()

        val boxes = mutableListOf<GeoBounds>()
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var open = false

        fun flush() {
            if (open) boxes += padded(minLat, minLon, maxLat, maxLon, corridorRadiusKm)
        }

        for ((lat, lon) in points) {
            val nMinLat = min(minLat, lat)
            val nMaxLat = max(maxLat, lat)
            val nMinLon = min(minLon, lon)
            val nMaxLon = max(maxLon, lon)
            val latSpanKm = (nMaxLat - nMinLat) * KM_PER_DEGREE_LAT
            val cosLat = max(cos(Math.toRadians(lat)), 0.01)
            val lonSpanKm = (nMaxLon - nMinLon) * KM_PER_DEGREE_LAT * cosLat

            if (open && (latSpanKm > boxSpanKm || lonSpanKm > boxSpanKm)) {
                // Current box is full — close it and start a fresh one at this point
                // (starting here keeps the boxes overlapping so there is no gap).
                flush()
                minLat = lat; maxLat = lat; minLon = lon; maxLon = lon
            } else {
                minLat = nMinLat; maxLat = nMaxLat; minLon = nMinLon; maxLon = nMaxLon
                open = true
            }
        }
        flush()
        return boxes
    }

    private fun padded(
        south: Double, west: Double, north: Double, east: Double, radiusKm: Double,
    ): GeoBounds {
        val latDelta = radiusKm / KM_PER_DEGREE_LAT
        val midLat = (south + north) / 2.0
        val cosLat = max(cos(Math.toRadians(midLat)), 0.01)
        val lonDelta = radiusKm / (KM_PER_DEGREE_LAT * cosLat)
        return GeoBounds(
            south = clampLat(south - latDelta),
            north = clampLat(north + latDelta),
            west  = clampLon(west - lonDelta),
            east  = clampLon(east + lonDelta),
        )
    }

    private fun clampLat(value: Double): Double = value.coerceIn(-85.0, 85.0)

    private fun clampLon(value: Double): Double {
        var v = value
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return if (abs(v) < 1e-9) 0.0 else v
    }
}

