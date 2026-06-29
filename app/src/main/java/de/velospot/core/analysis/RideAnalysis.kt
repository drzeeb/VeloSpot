package de.velospot.core.analysis

import de.velospot.core.navigation.GeoMath
import de.velospot.core.tracking.estimateRideCalories
import de.velospot.domain.model.RecordedRide

/**
 * Rich, derived analytics for a single recorded ride — the data model behind the
 * full-screen ride analysis. Everything is computed **purely** from the persisted
 * [RecordedRide] (no extra storage, no Android dependency) so it is fully
 * unit-testable on the JVM, and is built off the main thread by the screen's
 * ViewModel.
 *
 * This is the Phase-0 foundation: per-kilometre splits, a speed distribution and
 * the headline figures. Later phases extend it (climb categories, gradient bins,
 * an animated map replay, etc.).
 */
data class RideAnalysis(
    val distanceMeters: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val stoppedSeconds: Long,
    val avgMovingSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val caloriesKcal: Int,
    /** Per-kilometre splits in ride order (the last one may be a partial km). */
    val splits: List<KmSplit>,
    /** Time spent in each speed band, for the speed-distribution chart. */
    val speedHistogram: List<SpeedBin>,
    /** Index into [splits] of the fastest / slowest **full** kilometre, or -1. */
    val fastestSplitIndex: Int,
    val slowestSplitIndex: Int
)

/** One kilometre (or trailing partial km) of the ride. */
data class KmSplit(
    /** 0-based split index; display as `index + 1`. */
    val index: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedMps: Double,
    /** `true` when this split is a full kilometre (≥ [FULL_SPLIT_METERS]). */
    val isFull: Boolean
)

/** Seconds spent riding within a `[fromKmh, toKmh)` speed band. */
data class SpeedBin(
    val fromKmh: Int,
    val toKmh: Int,
    val seconds: Long
)

private const val SPLIT_METERS = 1_000.0
private const val FULL_SPLIT_METERS = 950.0          // tolerance for "counts as a full km"
private const val MIN_TRAILING_SPLIT_METERS = 50.0   // drop a tiny dangling remainder
private const val MAX_SEGMENT_GAP_MILLIS = 60_000L   // ignore GPS dropouts for timing
private const val SPEED_BIN_KMH = 5                   // 0–5, 5–10, … km/h
private const val MAX_SPEED_BIN_KMH = 45              // last bin is "45+"

/**
 * Crunches a [ride] into a [RideAnalysis]. The headline figures reuse the ride's
 * stored aggregates (so they match the rest of the app), while the splits and the
 * speed histogram are derived from the captured GPS track.
 */
fun analyzeRide(ride: RecordedRide): RideAnalysis {
    val stopped = (ride.elapsedSeconds - ride.movingSeconds).coerceAtLeast(0)

    val splits = buildSplits(ride)
    val histogram = buildSpeedHistogram(ride)

    val fullSplits = splits.filter { it.isFull }
    val fastest = fullSplits.maxByOrNull { it.avgSpeedMps }
    val slowest = fullSplits.minByOrNull { it.avgSpeedMps }

    return RideAnalysis(
        distanceMeters = ride.distanceMeters,
        elapsedSeconds = ride.elapsedSeconds,
        movingSeconds = ride.movingSeconds,
        stoppedSeconds = stopped,
        avgMovingSpeedMps = ride.avgSpeedMps,
        maxSpeedMps = ride.maxSpeedMps,
        elevationGainMeters = ride.elevationGainMeters,
        elevationLossMeters = ride.elevationLossMeters,
        caloriesKcal = estimateRideCalories(ride),
        splits = splits,
        speedHistogram = histogram,
        fastestSplitIndex = fastest?.index ?: -1,
        slowestSplitIndex = slowest?.index ?: -1
    )
}

private fun buildSplits(ride: RecordedRide): List<KmSplit> {
    val points = ride.points
    if (points.size < 2) return emptyList()

    val splits = ArrayList<KmSplit>()
    var index = 0
    var splitMeters = 0.0
    var splitMillis = 0L

    fun push(meters: Double, millis: Long) {
        val secs = millis / 1000
        val avg = if (secs > 0) meters / secs else 0.0
        splits.add(
            KmSplit(
                index = index,
                distanceMeters = meters,
                durationSeconds = secs,
                avgSpeedMps = avg,
                isFull = meters >= FULL_SPLIT_METERS
            )
        )
        index++
    }

    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val segMeters = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val dtMillis = b.timestamp - a.timestamp
        val countTime = dtMillis in 1..MAX_SEGMENT_GAP_MILLIS

        splitMeters += segMeters
        if (countTime) splitMillis += dtMillis

        // Whole short segment is assigned to the split it starts in; segments are a
        // few metres long so the km-boundary rounding error is negligible.
        if (splitMeters >= SPLIT_METERS) {
            push(splitMeters, splitMillis)
            splitMeters = 0.0
            splitMillis = 0L
        }
    }
    if (splitMeters >= MIN_TRAILING_SPLIT_METERS) push(splitMeters, splitMillis)
    return splits
}

private fun buildSpeedHistogram(ride: RecordedRide): List<SpeedBin> {
    val binCount = MAX_SPEED_BIN_KMH / SPEED_BIN_KMH + 1 // bands + the "45+" overflow
    val seconds = LongArray(binCount)

    val points = ride.points
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val dtMillis = b.timestamp - a.timestamp
        if (dtMillis !in 1..MAX_SEGMENT_GAP_MILLIS) continue
        val segMeters = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val speedKmh = segMeters / (dtMillis / 1000.0) * 3.6
        val bin = (speedKmh.toInt() / SPEED_BIN_KMH).coerceIn(0, binCount - 1)
        seconds[bin] += dtMillis / 1000
    }

    return (0 until binCount).map { i ->
        val from = i * SPEED_BIN_KMH
        val to = if (i == binCount - 1) Int.MAX_VALUE else (i + 1) * SPEED_BIN_KMH
        SpeedBin(fromKmh = from, toKmh = to, seconds = seconds[i])
    }
}
