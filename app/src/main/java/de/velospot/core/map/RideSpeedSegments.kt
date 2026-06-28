package de.velospot.core.map

import de.velospot.domain.model.TrackPoint

/**
 * One contiguous slice of a recorded ride's track that is drawn in a single
 * colour, derived from the [speedMps] ridden along it.
 *
 * @property line ordered `(latitude, longitude)` vertices of the slice. Adjacent
 *  segments share an endpoint so the coloured line reads as continuous.
 * @property speedMps the representative (peak) speed of the slice — used to pick
 *  its colour on the green → red ramp.
 */
data class SpeedSegment(
    val line: List<Pair<Double, Double>>,
    val speedMps: Double
)

/**
 * Pure, Android-free preparation of a recorded ride's track for the **"colour by
 * speed"** overlay: the polyline split into contiguous [SpeedSegment]s, each
 * carrying the speed ridden so the map layer can paint it green (slow) → red
 * (the ride's top speed).
 *
 * A raw track holds a fix roughly every second, so colouring every individual
 * segment would create thousands of map features. The track is therefore reduced
 * to at most [TARGET_SEGMENTS] slices by striding evenly through the points; each
 * slice keeps the **peak** speed over the points it spans, so the fastest stretch
 * always reaches the red end of the ramp (matching the "max speed" marker).
 */
object RideSpeedSegments {

    /** Upper bound on the number of coloured slices produced for one ride. */
    const val TARGET_SEGMENTS = 240

    /**
     * Splits [points] into at most [targetSegments] contiguous coloured slices.
     * Returns an empty list when the track has fewer than two points (nothing to
     * draw). Points without a speed sample contribute `0` to their slice's peak.
     */
    fun build(
        points: List<TrackPoint>,
        targetSegments: Int = TARGET_SEGMENTS
    ): List<SpeedSegment> {
        val n = points.size
        if (n < 2) return emptyList()

        // Stride so the whole track collapses to ~targetSegments slices, but never
        // coarser than one point per slice.
        val stride = ((n - 1) + targetSegments - 1) / targetSegments // ceil
        val segments = ArrayList<SpeedSegment>()
        var start = 0
        while (start < n - 1) {
            val end = (start + stride).coerceAtMost(n - 1)
            var peak = 0.0
            for (i in start..end) {
                val s = points[i].speedMps?.toDouble() ?: 0.0
                if (s > peak) peak = s
            }
            segments.add(
                SpeedSegment(
                    line = (start..end).map { points[it].latitude to points[it].longitude },
                    speedMps = peak
                )
            )
            start = end
        }
        return segments
    }
}


