package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint

/**
 * Pure, Android-free ETA math for live navigation.
 *
 * The live "arrival in X min" must reflect the **modelled time of the remaining
 * portion** of the route, not a flat `remainingDistance ÷ averageSpeed`. A flat
 * estimate is wrong whenever the part of the route still ahead differs from the
 * whole-route average — a climb ahead makes the ETA too optimistic, and a
 * descent/flat ahead (after the climb is done) makes it too pessimistic.
 *
 * This helper turns the route into a **per-node cumulative-time array** aligned
 * to the route polyline, so the remaining time is simply
 * `total − cumulativeTimeAtCurrentPosition`, interpolated within the current
 * segment. Two sources feed the array, in order of preference:
 *
 *  1. **Per-node times** from the routing engine (BRouter emits a cumulative
 *     travel time on every track node via its kinematic model, which already
 *     accounts for the climb profile). Used verbatim, only rescaled so the last
 *     node equals the source total exactly.
 *  2. **Gradient-weighted geometry** (fallback) — when no per-node times exist
 *     but the route points carry elevation, each segment's time is weighted by
 *     its gradient (uphill slower, downhill faster) and the whole is normalised
 *     so the modelled total still equals the source total. This only
 *     *redistributes* the source total across the route; it never contradicts it.
 *
 * When neither is available the caller keeps its flat estimate (see
 * [blendedFlatSeconds]).
 */
internal object RouteEtaModel {

    /**
     * Extra travel time per unit of uphill gradient in the fallback model. A
     * gradient of `+0.10` (10 % climb) multiplies a segment's time by
     * `1 + 6·0.10 = 1.6`. Tuned so climbs read clearly slower without dominating.
     */
    private const val UPHILL_TIME_PER_GRADE = 6.0

    /**
     * Time relief per unit of downhill gradient (smaller than the uphill term:
     * coasting downhill helps far less than a climb hurts). A `-0.10` descent
     * multiplies time by `1 − 2·0.10 = 0.8`.
     */
    private const val DOWNHILL_TIME_PER_GRADE = 2.0

    /** Clamp the per-segment gradient time multiplier so a cliff can't zero/explode a segment. */
    private const val MIN_TIME_FACTOR = 0.4
    private const val MAX_TIME_FACTOR = 5.0

    /**
     * Builds a per-node cumulative-time array (seconds) aligned to [points]
     * (`size == points.size`, `[0] == 0`, `last == totalSeconds`), or `null` when
     * no better-than-flat model is possible.
     *
     * @param points the route polyline the navigation matcher runs on.
     * @param perNodeTimes cumulative per-node times from the routing source
     *  (BRouter), aligned to [points]; `null`/mismatched → try the fallback.
     * @param totalSeconds the source's whole-route travel time; the array is
     *  scaled so its last value equals this exactly.
     */
    fun buildCumulativeTimes(
        points: List<RoutePoint>,
        perNodeTimes: List<Double>?,
        totalSeconds: Double
    ): DoubleArray? {
        if (points.size < 2 || totalSeconds <= 0.0) return null

        // ── Preferred: engine per-node times, rescaled to the source total ────
        if (perNodeTimes != null &&
            perNodeTimes.size == points.size &&
            isNonDecreasing(perNodeTimes) &&
            perNodeTimes.last() > 0.0
        ) {
            val scale = totalSeconds / perNodeTimes.last()
            return DoubleArray(perNodeTimes.size) { perNodeTimes[it] * scale }
        }

        // ── Fallback: gradient-weighted geometry (needs elevation) ────────────
        return gradientWeightedCumulativeTimes(points, totalSeconds)
    }

    /**
     * Remaining modelled time (seconds) from the snapped position — segment
     * [segmentIndex] at fraction [t] (0..1) — to the route end, given the
     * cumulative-time array from [buildCumulativeTimes]. Interpolates linearly
     * within the current segment, so the result decreases monotonically as the
     * rider advances.
     */
    fun remainingSeconds(cumulativeTimes: DoubleArray, segmentIndex: Int, t: Double): Double {
        if (cumulativeTimes.size < 2) return 0.0
        val total = cumulativeTimes.last()
        val seg = segmentIndex.coerceIn(0, cumulativeTimes.size - 2)
        val frac = t.coerceIn(0.0, 1.0)
        val timeAtPos = cumulativeTimes[seg] + (cumulativeTimes[seg + 1] - cumulativeTimes[seg]) * frac
        return (total - timeAtPos).coerceAtLeast(0.0)
    }

    /**
     * Flat fallback used when no per-segment model exists: remaining distance
     * over a speed that **blends** the route average with the live measured
     * speed, so the ETA at least reacts to the rider actually going faster/slower
     * than the route's average. When no live speed is available this collapses to
     * the plain average-speed estimate.
     *
     * @param liveWeight how much the live speed counts vs the route average
     *  (0 = average only, 1 = live only).
     */
    fun blendedFlatSeconds(
        remainingMeters: Double,
        routeAvgSpeedMps: Double,
        liveSpeedMps: Double?,
        liveWeight: Double = 0.5,
        minSpeedMps: Double = 0.5
    ): Double {
        val avg = routeAvgSpeedMps.coerceAtLeast(minSpeedMps)
        val speed = if (liveSpeedMps != null && liveSpeedMps > minSpeedMps) {
            val w = liveWeight.coerceIn(0.0, 1.0)
            (avg * (1.0 - w) + liveSpeedMps * w)
        } else {
            avg
        }.coerceAtLeast(minSpeedMps)
        return remainingMeters / speed
    }

    /**
     * Redistributes [totalSeconds] across the route by weighting each segment's
     * time with its gradient (uphill costs more time, downhill less). Returns
     * `null` when the route carries no usable elevation (so the caller falls back
     * to the flat estimate). The output sums to [totalSeconds] exactly.
     */
    private fun gradientWeightedCumulativeTimes(
        points: List<RoutePoint>,
        totalSeconds: Double
    ): DoubleArray? {
        val n = points.size
        val weights = DoubleArray(n - 1)
        var weightSum = 0.0
        var sawElevation = false
        for (i in 0 until n - 1) {
            val a = points[i]
            val b = points[i + 1]
            val len = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val elevA = a.elevationMeters
            val elevB = b.elevationMeters
            val factor = if (elevA != null && elevB != null && len > 0.0) {
                sawElevation = true
                gradientTimeFactor((elevB - elevA) / len)
            } else {
                1.0
            }
            val w = len * factor
            weights[i] = w
            weightSum += w
        }
        if (!sawElevation || weightSum <= 0.0) return null

        val cumulative = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n - 1) {
            acc += weights[i] / weightSum * totalSeconds
            cumulative[i + 1] = acc
        }
        // Pin the last node exactly to the total (guard float drift).
        cumulative[n - 1] = totalSeconds
        return cumulative
    }

    /** Time multiplier for a segment of signed [grade] (rise/run; +uphill, −downhill). */
    private fun gradientTimeFactor(grade: Double): Double {
        val factor = if (grade >= 0.0) {
            1.0 + UPHILL_TIME_PER_GRADE * grade
        } else {
            1.0 + DOWNHILL_TIME_PER_GRADE * grade   // grade < 0 → reduces the factor
        }
        return factor.coerceIn(MIN_TIME_FACTOR, MAX_TIME_FACTOR)
    }

    private fun isNonDecreasing(values: List<Double>): Boolean {
        for (i in 1 until values.size) {
            // Allow tiny float wobble but reject a real decrease.
            if (values[i] - values[i - 1] < -1e-3) return false
        }
        return true
    }
}



