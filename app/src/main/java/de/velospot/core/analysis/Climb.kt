package de.velospot.core.analysis

import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint

/**
 * A detected climb (sustained ascent) within a recorded ride, plus the cycling
 * "HC / Cat 1–4" categorisation. Computed purely from the GPS track — see
 * [detectClimbs].
 */
data class Climb(
    /** 0-based order along the ride; display as `index + 1`. */
    val index: Int,
    val lengthMeters: Double,
    val ascentMeters: Double,
    /** Average gradient over the climb, in percent. */
    val avgGradientPercent: Double,
    /** Steepest sustained gradient seen on the climb, in percent. */
    val maxGradientPercent: Double,
    /** Vertical ascent metres per hour (VAM) — a climbing-pace measure. */
    val vamMetersPerHour: Double,
    val category: ClimbCategory,
    /** Index range into the ride's points, so the screen can draw a mini-profile. */
    val startPointIndex: Int,
    val endPointIndex: Int
)

/**
 * Climb difficulty, derived from the road-cycling **climb score**
 * `length(m) × average gradient(%)` (which equals total ascent × 100). Thresholds
 * follow the commonly used scale: e.g. Cat 4 from ~80 m of ascent up to HC from
 * ~800 m, so meaningful everyday hills register while only the big ones reach HC.
 */
enum class ClimbCategory(val score: Int) {
    /** Below Cat 4 — a noticeable rise, not formally categorised. */
    UNCATEGORIZED(0),
    CAT4(8_000),
    CAT3(16_000),
    CAT2(32_000),
    CAT1(64_000),
    /** Hors catégorie — the hardest. */
    HC(80_000);

    companion object {
        fun fromScore(score: Double): ClimbCategory =
            entries.filter { score >= it.score }.maxByOrNull { it.score } ?: UNCATEGORIZED
    }
}

// A climb must gain at least this much height overall to count.
private const val MIN_CLIMB_ASCENT_METERS = 15.0
// …and run over a meaningful average gradient.
private const val MIN_CLIMB_AVG_GRADIENT = 2.0
// Allow brief descents/flats inside a climb without ending it (running loss budget).
private const val DESCENT_TOLERANCE_METERS = 12.0
// Ignore GPS time gaps for VAM timing.
private const val MAX_SEGMENT_GAP_MILLIS = 60_000L
// Window for the "max sustained gradient" (metres), to reject single-sample spikes.
private const val MAX_GRADIENT_WINDOW_METERS = 100.0

/**
 * Finds sustained climbs in [ride] from its altitude track. A climb starts where
 * the elevation begins trending up and ends after a sustained descent exceeding
 * [DESCENT_TOLERANCE_METERS]; segments without altitude are skipped. Returns
 * climbs in ride order, each categorised. Empty when the track has too little
 * elevation data.
 */
fun detectClimbs(ride: RecordedRide): List<Climb> {
    val points = ride.points
    if (points.size < 3) return emptyList()

    val climbs = ArrayList<Climb>()

    // Running state of the climb currently being built.
    var startIdx = -1
    var lengthM = 0.0
    var gainM = 0.0
    var topAltitude = 0.0
    var topIdx = -1
    var pendingLoss = 0.0          // descent accumulated since the last new high
    var pendingLengthM = 0.0
    var pendingMillis = 0L
    var climbMillis = 0L

    fun reset() {
        startIdx = -1; lengthM = 0.0; gainM = 0.0
        topAltitude = 0.0; topIdx = -1; pendingLoss = 0.0
        pendingLengthM = 0.0; pendingMillis = 0L; climbMillis = 0L
    }

    fun finish() {
        if (startIdx >= 0 && topIdx > startIdx) {
            // Trim the trailing descent: the climb ends at its highest point.
            val len = lengthM - pendingLengthM
            val secs = (climbMillis - pendingMillis) / 1000.0
            if (gainM >= MIN_CLIMB_ASCENT_METERS && len > 0) {
                val avgGrad = gainM / len * 100.0
                if (avgGrad >= MIN_CLIMB_AVG_GRADIENT) {
                    // Standard road-cycling score: length(m) × average gradient(%).
                    val score = len * avgGrad
                    climbs.add(
                        Climb(
                            index = climbs.size,
                            lengthMeters = len,
                            ascentMeters = gainM,
                            avgGradientPercent = avgGrad,
                            maxGradientPercent = maxSustainedGradient(points, startIdx, topIdx),
                            vamMetersPerHour = if (secs > 0) gainM / secs * 3600.0 else 0.0,
                            category = ClimbCategory.fromScore(score),
                            startPointIndex = startIdx,
                            endPointIndex = topIdx
                        )
                    )
                }
            }
        }
        reset()
    }

    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val altA = a.altitudeMeters
        val altB = b.altitudeMeters
        if (altA == null || altB == null) { finish(); continue }

        val segM = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val dAlt = altB - altA
        val dtMillis = (b.timestamp - a.timestamp).let { if (it in 1..MAX_SEGMENT_GAP_MILLIS) it else 0L }

        if (dAlt > 0) {
            if (startIdx < 0) { startIdx = i - 1; topAltitude = altA; topIdx = i - 1 }
            lengthM += segM; gainM += dAlt; climbMillis += dtMillis
            if (altB >= topAltitude) {
                topAltitude = altB; topIdx = i
                pendingLoss = 0.0; pendingLengthM = 0.0; pendingMillis = 0L
            }
        } else if (startIdx >= 0) {
            // Inside a climb but going down/flat: bank it as a pending interruption.
            lengthM += segM; climbMillis += dtMillis
            pendingLoss += -dAlt; pendingLengthM += segM; pendingMillis += dtMillis
            if (pendingLoss > DESCENT_TOLERANCE_METERS) finish()
        }
    }
    finish()
    return climbs
}

/**
 * The steepest gradient sustained over at least [MAX_GRADIENT_WINDOW_METERS]
 * within the point range, so a single noisy altitude spike can't dominate.
 */
private fun maxSustainedGradient(points: List<TrackPoint>, from: Int, to: Int): Double {
    var maxGrad = 0.0
    for (i in from until to) {
        // Grow a window of ~MAX_GRADIENT_WINDOW_METERS starting at i.
        var windowM = 0.0
        var windowGain = 0.0
        var k = i + 1
        while (k <= to && windowM < MAX_GRADIENT_WINDOW_METERS) {
            val a = points[k - 1]; val b = points[k]
            windowM += GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val da = (b.altitudeMeters ?: 0.0) - (a.altitudeMeters ?: 0.0)
            windowGain += da
            k++
        }
        if (windowM >= MAX_GRADIENT_WINDOW_METERS * 0.5) {
            val grad = windowGain / windowM * 100.0
            if (grad > maxGrad) maxGrad = grad
        }
    }
    return maxGrad.coerceAtLeast(0.0)
}




