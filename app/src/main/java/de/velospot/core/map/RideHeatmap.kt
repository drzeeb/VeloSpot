package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A single aggregated heatmap cell: a grid-snapped coordinate plus a normalised
 * [intensity] (0..1) derived from how many recorded-ride GPS samples fell into it.
 *
 * @property latitude  Grid-cell centre latitude (WGS84 degrees).
 * @property longitude Grid-cell centre longitude (WGS84 degrees).
 * @property intensity Heat weight in `0.0..1.0`, saturating once a cell has been
 *  ridden through [RideHeatmap.SATURATION_COUNT] times.
 */
data class HeatCell(
    val latitude: Double,
    val longitude: Double,
    val intensity: Double
)

/**
 * Pure, Android-free aggregation of recorded-ride GPS tracks into a compact set of
 * weighted [HeatCell]s for the map heatmap overlay.
 *
 * Raw tracks hold thousands of points; feeding them straight to MapLibre would be
 * wasteful and would not convey "where do I ride most" (a single ride and ten rides
 * over the same street would look identical). Instead every point is snapped to a
 * small lat/lon grid and the number of samples per cell is counted, so streets you
 * ride often come out hotter. The count is normalised to `0..1` (saturating at
 * [SATURATION_COUNT]) so the map layer can use it directly as the heatmap weight.
 */
object RideHeatmap {

    /**
     * Grid resolution in decimal places. 4 decimals ≈ 11 m cells — fine enough to
     * trace streets while collapsing the dense per-second samples of a single pass.
     */
    const val GRID_DECIMALS = 4

    /** Sample count at which a cell reaches full heat (further passes don't add). */
    const val SATURATION_COUNT = 8

    /**
     * Aggregates all [rides]' track points into weighted [HeatCell]s.
     *
     * @param gridDecimals Grid resolution (decimal places); larger = finer cells.
     * @param saturationCount Sample count mapped to the maximum [HeatCell.intensity].
     */
    fun build(
        rides: List<RecordedRide>,
        gridDecimals: Int = GRID_DECIMALS,
        saturationCount: Int = SATURATION_COUNT
    ): List<HeatCell> {
        if (rides.isEmpty()) return emptyList()
        val factor = 10.0.pow(gridDecimals)
        val counts = HashMap<Long, Int>()
        // Pack the rounded lat/lon grid index into a single Long key to avoid
        // allocating a Pair per point across potentially huge tracks.
        for (ride in rides) {
            for (p in ride.points) {
                val latIdx = (p.latitude * factor).roundToLong()
                val lonIdx = (p.longitude * factor).roundToLong()
                val key = (latIdx shl 32) xor (lonIdx and 0xFFFFFFFFL)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val safeSaturation = saturationCount.coerceAtLeast(1)
        return counts.entries.map { (key, count) ->
            val latIdx = key shr 32
            val lonIdx = (key and 0xFFFFFFFFL).toInt().toLong() // sign-extend back
            HeatCell(
                latitude = latIdx / factor,
                longitude = lonIdx / factor,
                intensity = min(count, safeSaturation).toDouble() / safeSaturation
            )
        }
    }
}

