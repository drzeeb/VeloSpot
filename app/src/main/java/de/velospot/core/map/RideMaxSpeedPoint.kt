package de.velospot.core.map

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import kotlin.math.abs

/**
 * Pure, Android-free lookup of **where** a recorded ride reached its peak speed —
 * the GPS sample that the on-map "max speed" bubble is anchored to when the rider
 * inspects a past ride.
 *
 * The stored [RecordedRide.maxSpeedMps] is an aggregate statistic; to place a
 * marker we need the actual track point it came from. We pick the fix whose own
 * recorded [TrackPoint.speedMps] best matches that stored peak, so the bubble sits
 * exactly on the spot the "Max speed" stat refers to (and shows the same value).
 */
object RideMaxSpeedPoint {

    /**
     * Returns the track point at which [ride] reached its peak speed, or `null`
     * when the ride carries no per-fix speed samples or no positive peak (e.g. a
     * track imported without speed data). The point closest in recorded speed to
     * [RecordedRide.maxSpeedMps] wins; ties resolve to the earliest such fix.
     */
    fun find(ride: RecordedRide): TrackPoint? {
        if (ride.maxSpeedMps <= 0.0) return null
        return ride.points
            .filter { it.speedMps != null }
            .minByOrNull { abs(it.speedMps!!.toDouble() - ride.maxSpeedMps) }
    }
}

