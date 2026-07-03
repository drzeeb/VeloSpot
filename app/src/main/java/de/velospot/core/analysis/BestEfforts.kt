package de.velospot.core.analysis

import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide

/**
 * The fastest **time** the ride covered a fixed [distanceMeters] (e.g. the quickest
 * 1 km / 5 km / 10 km of the whole ride) — the cycling-app "best effort".
 */
data class DistanceEffort(
    val distanceMeters: Double,
    val elapsedSeconds: Double,
    val avgSpeedMps: Double
)

/** The furthest the ride travelled within a fixed [seconds] window. */
data class DurationEffort(
    val seconds: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double
)

/** Both best-effort tables for a ride (only entries the ride was long enough for). */
data class BestEfforts(
    val fastestDistances: List<DistanceEffort>,
    val bestDurations: List<DurationEffort>
)

private val DISTANCE_TARGETS_METERS = doubleArrayOf(1_000.0, 5_000.0, 10_000.0, 20_000.0, 40_000.0, 100_000.0)
private val DURATION_TARGETS_SECONDS = longArrayOf(60, 300, 600, 1_200, 3_600)
/** Segments implying a higher speed than this are GPS noise and are clamped. */
private const val MAX_PLAUSIBLE_MPS = 30.0 // ~108 km/h

/**
 * Computes the ride's **best efforts**: the fastest time over each standard
 * distance and the furthest distance within each standard time window. Pure and
 * JVM-unit-testable; both passes are O(n) with a sliding window over cumulative
 * distance/time. GPS "teleport" glitches are clamped so they can't fake a record.
 */
fun computeBestEfforts(ride: RecordedRide): BestEfforts {
    val pts = ride.points
    if (pts.size < 2) return BestEfforts(emptyList(), emptyList())

    val n = pts.size
    val cumDist = DoubleArray(n)
    val cumTime = DoubleArray(n) // seconds
    for (i in 1 until n) {
        val a = pts[i - 1]
        val b = pts[i]
        val dt = ((b.timestamp - a.timestamp).toDouble() / 1000.0).coerceAtLeast(0.0)
        var d = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        if (dt > 0 && d / dt > MAX_PLAUSIBLE_MPS) d = MAX_PLAUSIBLE_MPS * dt // clamp GPS jumps
        cumDist[i] = cumDist[i - 1] + d
        cumTime[i] = cumTime[i - 1] + dt
    }
    val totalDist = cumDist[n - 1]
    val totalTime = cumTime[n - 1]

    val fastestDistances = DISTANCE_TARGETS_METERS
        .filter { it <= totalDist }
        .mapNotNull { target ->
            fastestTimeForDistance(cumDist, cumTime, target)?.let { secs ->
                DistanceEffort(target, secs, if (secs > 0) target / secs else 0.0)
            }
        }

    val bestDurations = DURATION_TARGETS_SECONDS
        .filter { it <= totalTime }
        .mapNotNull { target ->
            maxDistanceForDuration(cumDist, cumTime, target.toDouble())?.let { dist ->
                DurationEffort(target, dist, dist / target)
            }
        }

    return BestEfforts(fastestDistances, bestDurations)
}

/** Minimum time (s) to cover [target] metres anywhere on the track, else `null`. */
private fun fastestTimeForDistance(cumDist: DoubleArray, cumTime: DoubleArray, target: Double): Double? {
    val n = cumDist.size
    var best = Double.MAX_VALUE
    var i = 0
    for (j in 1 until n) {
        // Tighten the start as far as possible while still covering `target`.
        while (i < j && cumDist[j] - cumDist[i + 1] >= target) i++
        val covered = cumDist[j] - cumDist[i]
        if (covered < target) continue
        // Trim the start segment so exactly `target` metres remain.
        val segDist = cumDist[i + 1] - cumDist[i]
        val segTime = cumTime[i + 1] - cumTime[i]
        val overshoot = covered - target
        val trimTime = if (segDist > 0) overshoot / segDist * segTime else 0.0
        val time = (cumTime[j] - cumTime[i]) - trimTime
        if (time < best) best = time
    }
    return best.takeIf { it != Double.MAX_VALUE }
}

/** Maximum distance (m) covered within any [targetSec]-second window, else `null`. */
private fun maxDistanceForDuration(cumDist: DoubleArray, cumTime: DoubleArray, targetSec: Double): Double? {
    val n = cumDist.size
    var best = -1.0
    var i = 0
    for (j in 1 until n) {
        while (i < j && cumTime[j] - cumTime[i + 1] >= targetSec) i++
        val duration = cumTime[j] - cumTime[i]
        if (duration < targetSec) continue
        // Trim the start segment so exactly `targetSec` seconds remain.
        val segTime = cumTime[i + 1] - cumTime[i]
        val segDist = cumDist[i + 1] - cumDist[i]
        val overshoot = duration - targetSec
        val trimDist = if (segTime > 0) overshoot / segTime * segDist else 0.0
        val dist = (cumDist[j] - cumDist[i]) - trimDist
        if (dist > best) best = dist
    }
    return best.takeIf { it >= 0.0 }
}

