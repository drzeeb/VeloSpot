package de.velospot.core.maptiles

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A plain, Android-free geographic bounding box (south/west/north/east in degrees),
 * so the region maths stays a pure, JVM-unit-testable concern. The MapLibre
 * `LatLngBounds` conversion lives in `OfflineMapTilesManager` (the Android edge).
 */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * Pure helpers for the **offline map tiles** feature — the visible vector map that
 * MapLibre can pre-download for a region (mirroring the offline *routing* segments).
 *
 * Kept free of Android/MapLibre types so the bbox maths, the country coverage and the
 * progress fraction are covered by plain JVM unit tests (`OfflineMapRegionsTest`).
 */
object OfflineMapRegions {

    /** Lowest zoom cached — keeps some zoomed-out context without wasting storage. */
    const val MIN_ZOOM: Double = 6.0

    /** Street-level detail for the small "my region" download. */
    const val REGION_MAX_ZOOM: Double = 14.0

    /**
     * The whole-area download stops one bucket earlier: each extra zoom level ~4×s
     * the tile count, so capping the (already huge) country download at z12 keeps it
     * from ballooning into many GB and hammering the donation-funded tile server.
     */
    const val COUNTRY_MAX_ZOOM: Double = 12.0

    /** Half-span, in km, of the "my region" box drawn around the rider's position. */
    const val DEFAULT_REGION_RADIUS_KM: Double = 40.0

    private const val KM_PER_DEGREE_LAT = 111.0

    /**
     * A square-ish bounding box of [radiusKm] half-span around [lat]/[lon]. The
     * longitude span is widened by 1/cos(lat) so the box stays roughly [radiusKm]
     * on the ground east–west as well, and everything is clamped to valid lat/lon.
     */
    fun boundsAround(
        lat: Double,
        lon: Double,
        radiusKm: Double = DEFAULT_REGION_RADIUS_KM,
    ): GeoBounds {
        val latDelta = radiusKm / KM_PER_DEGREE_LAT
        // Guard against the poles where cos(lat) → 0 (not reachable in DE/FR/LU, but
        // keeps the maths total and the box finite).
        val cosLat = max(cos(Math.toRadians(lat)), 0.01)
        val lonDelta = radiusKm / (KM_PER_DEGREE_LAT * cosLat)
        return GeoBounds(
            south = clampLat(lat - latDelta),
            north = clampLat(lat + latDelta),
            west  = clampLon(lon - lonDelta),
            east  = clampLon(lon + lonDelta),
        )
    }

    /**
     * Coarse boxes tiling the three supported countries — Germany 🇩🇪, France 🇫🇷
     * (incl. Corsica) and Luxembourg 🇱🇺. Split into a few chunks (like the BRouter
     * `COUNTRY_SEGMENTS`) so the whole-area download runs — and resumes — per box and
     * the UI can show an "x of y" indicator. The France box already covers Luxembourg,
     * but the overlap only re-uses cached tiles, so it is harmless.
     */
    val COUNTRY_BOUNDS: List<GeoBounds> = listOf(
        // France (incl. the Channel coast, Pyrenees and Corsica) + Luxembourg.
        GeoBounds(south = 41.3, west = -5.2, north = 51.2, east = 9.7),
        // Germany.
        GeoBounds(south = 47.2, west = 5.8, north = 55.1, east = 15.1),
    )

    /**
     * Download progress as a 0f–1f fraction of resources fetched. Returns `-1f`
     * ("indeterminate") until the required count is known, and is clamped to 1f.
     */
    fun progressFraction(completed: Long, required: Long): Float {
        if (required <= 0L) return -1f
        return min(completed.toFloat() / required.toFloat(), 1f)
    }

    private fun clampLat(value: Double): Double = value.coerceIn(-85.0, 85.0)

    private fun clampLon(value: Double): Double {
        // Wrap into [-180, 180] rather than clamp so a box near the antimeridian
        // stays sensible (again, not reachable in DE/FR/LU, but keeps it total).
        var v = value
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return if (abs(v) < 1e-9) 0.0 else v
    }
}

