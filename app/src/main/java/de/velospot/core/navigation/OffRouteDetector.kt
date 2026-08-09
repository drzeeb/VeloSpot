package de.velospot.core.navigation

/**
 * Pure, Android-free decision for whether a GPS fix is "off-route", made
 * **accuracy-aware** so it stays tolerant when the fix is imprecise (urban
 * canyons, under trees, tunnels) and tight when the fix is good.
 *
 * A single fixed distance-from-route threshold is a poor fit for real GPS: in
 * poor-reception areas a fix can be reported tens of metres off the true
 * position, tripping a false off-route (and an unnecessary reroute); a fixed
 * threshold large enough to absorb that would in turn be sluggish to notice a
 * genuine wrong turn on a good fix. So the effective corridor half-width scales
 * with the fix's reported horizontal accuracy:
 *
 * ```
 * effective = clamp(max(BASE_OFFROUTE_M, ACCURACY_MULTIPLIER * accuracyM), .., MAX_OFFROUTE_M)
 * ```
 *
 * - [BASE_OFFROUTE_M] is the floor, equal to the previous fixed threshold, so a
 *   good-GPS fix behaves exactly as before.
 * - [ACCURACY_MULTIPLIER] widens the corridor for an imprecise fix (e.g. a 40 m
 *   accuracy fix widens it well beyond the base).
 * - [MAX_OFFROUTE_M] clamps the corridor so a single garbage accuracy reading
 *   can't widen it so far that off-route detection is effectively disabled.
 *
 * The caller still debounces with N consecutive off-route fixes, so a lone
 * stray fix cannot trigger a reroute on its own.
 */
object OffRouteDetector {

    /**
     * Floor for the off-route corridor half-width (m). Matches the historical
     * fixed threshold, so behaviour on a good (low-accuracy-radius) fix is
     * unchanged.
     */
    const val BASE_OFFROUTE_M = 30.0

    /**
     * How many times the reported horizontal accuracy the corridor may grow to.
     * At 1.5×, a 40 m-accuracy fix yields a 60 m corridor, comfortably absorbing
     * the expected scatter without ignoring a real wrong turn.
     */
    const val ACCURACY_MULTIPLIER = 1.5

    /**
     * Hard cap on the corridor half-width (m). Prevents an absurd accuracy
     * reading (hundreds of metres) from widening the corridor so far that a
     * genuine off-route is never flagged.
     */
    const val MAX_OFFROUTE_M = 75.0

    /**
     * Effective off-route distance threshold (corridor half-width, m) for a fix
     * whose reported horizontal accuracy is [accuracyMeters]. A `null`,
     * non-finite or non-positive accuracy falls back to [BASE_OFFROUTE_M].
     */
    fun offRouteThresholdMeters(accuracyMeters: Float?): Double {
        val accuracy = accuracyMeters?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val widened = maxOf(BASE_OFFROUTE_M, ACCURACY_MULTIPLIER * accuracy)
        return widened.coerceAtMost(MAX_OFFROUTE_M)
    }

    /**
     * `true` when [distanceFromRouteM] (the perpendicular distance of the raw fix
     * from the route) exceeds the accuracy-aware threshold for [accuracyM].
     */
    fun isOffRoute(distanceFromRouteM: Double, accuracyM: Float?): Boolean =
        distanceFromRouteM > offRouteThresholdMeters(accuracyM)
}

