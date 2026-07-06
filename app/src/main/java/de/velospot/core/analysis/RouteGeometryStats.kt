package de.velospot.core.analysis

import de.velospot.domain.model.RoutePoint

/**
 * Pure helpers to derive display stats from a routed polyline.
 */
object RouteGeometryStats {

    /**
     * Cumulative ascent / descent (metres) over a route's [points], summing only
     * the positive resp. negative elevation deltas between consecutive nodes that
     * carry an elevation. Returns `0.0 to 0.0` when no elevation data is present
     * (e.g. an OSRM-online route).
     *
     * A small [deadBandMeters] dead-band ignores sub-metre jitter so a flat route
     * doesn't accumulate phantom metres.
     */
    fun elevationGainLoss(
        points: List<RoutePoint>,
        deadBandMeters: Double = 1.0
    ): Pair<Double, Double> {
        var gain = 0.0
        var loss = 0.0
        var previous: Double? = null
        for (point in points) {
            val elevation = point.elevationMeters ?: continue
            val prev = previous
            if (prev != null) {
                val delta = elevation - prev
                when {
                    delta > deadBandMeters -> gain += delta
                    delta < -deadBandMeters -> loss += -delta
                }
            }
            previous = elevation
        }
        return gain to loss
    }
}

