package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import kotlin.math.cos

/** A simplified ride polyline: ordered `(latitude, longitude)` vertices. */
typealias TrackPolyline = List<Pair<Double, Double>>

/**
 * Pure, Android-free preparation of recorded-ride GPS tracks for the **"ridden
 * tracks"** map overlay — every ride drawn as its own thin line, so you can see
 * everywhere you have been (and, through overlapping translucent lines, where you
 * ride most often).
 *
 * Raw tracks hold a fix roughly every second, so a single ride is thousands of
 * near-collinear points; feeding them straight to MapLibre would be wasteful.
 * Each track is therefore reduced with the Ramer–Douglas–Peucker algorithm to the
 * vertices that actually change the line's shape (within [SIMPLIFY_TOLERANCE_METERS]),
 * which typically removes 80–95 % of the points while leaving the route visually
 * identical.
 */
object RideTrackLines {

    /**
     * Max perpendicular deviation (metres) a point may have from the simplified
     * line before it is kept. ~8 m keeps street-level shape while collapsing the
     * dense per-second samples of a straight stretch.
     */
    const val SIMPLIFY_TOLERANCE_METERS = 8.0

    /**
     * Simplifies every ride in [rides] into a thin polyline. Rides with fewer than
     * two points (or that collapse below two after simplification) are dropped.
     */
    fun build(
        rides: List<RecordedRide>,
        toleranceMeters: Double = SIMPLIFY_TOLERANCE_METERS
    ): List<TrackPolyline> = rides.mapNotNull { ride ->
        val points = ride.points
        if (points.size < 2) return@mapNotNull null
        val simplified = simplify(points, toleranceMeters)
        if (simplified.size < 2) null
        else simplified.map { it.latitude to it.longitude }
    }

    /**
     * Ramer–Douglas–Peucker line simplification. Distances are computed in a local
     * equirectangular projection (metres) anchored at the track's first latitude,
     * which is accurate at ride scale. Iterative (explicit stack) to stay safe for
     * very long tracks.
     */
    internal fun simplify(points: List<TrackPoint>, toleranceMeters: Double): List<TrackPoint> {
        val n = points.size
        if (n < 3) return points
        val tol2 = toleranceMeters * toleranceMeters
        val latRefCos = cos(Math.toRadians(points.first().latitude))

        // Project to local metres once.
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = points[i].longitude * METERS_PER_DEGREE * latRefCos
            ys[i] = points[i].latitude * METERS_PER_DEGREE
        }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue
            var maxDist2 = 0.0
            var index = -1
            for (i in (start + 1) until end) {
                val d2 = perpDistanceSq(xs[i], ys[i], xs[start], ys[start], xs[end], ys[end])
                if (d2 > maxDist2) {
                    maxDist2 = d2
                    index = i
                }
            }
            if (index != -1 && maxDist2 > tol2) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }

        val result = ArrayList<TrackPoint>(n)
        for (i in 0 until n) if (keep[i]) result.add(points[i])
        return result
    }

    /** Squared perpendicular distance of point P to the segment A–B (metre space). */
    private fun perpDistanceSq(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            val ex = px - ax
            val ey = py - ay
            return ex * ex + ey * ey
        }
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val cx = ax + clamped * dx
        val cy = ay + clamped * dy
        val ex = px - cx
        val ey = py - cy
        return ex * ex + ey * ey
    }

    /** Metres per degree of latitude (and of longitude at the equator). */
    private const val METERS_PER_DEGREE = 111_320.0
}

