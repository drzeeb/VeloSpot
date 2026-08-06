package de.velospot.core.tracking

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary

/**
 * Average passenger-car tail-pipe emissions, in **grams of CO₂ per kilometre**.
 *
 * Cycling a given distance instead of driving a car of this efficiency avoids
 * roughly this much CO₂ per kilometre, which is what the app reports as
 * "CO₂ saved". The figure (~120 g/km) tracks the EU fleet-average new-car
 * tailpipe emissions target and is deliberately a single tunable constant so it
 * is trivial to update as fleet efficiency changes.
 */
const val CO2_GRAMS_SAVED_PER_KM = 120.0

/**
 * CO₂ (in grams) a rider saved by cycling [distanceMeters] instead of driving a
 * car. Derived purely from the ride's stored distance — no extra persistence —
 * so it needs no Room column or migration. Returns `0` for a non-positive
 * distance.
 */
fun estimateRideCo2SavedGrams(distanceMeters: Double): Double {
    if (distanceMeters <= 0.0) return 0.0
    return distanceMeters / 1_000.0 * CO2_GRAMS_SAVED_PER_KM
}

/** CO₂ saved by cycling a recorded [ride]; see [estimateRideCo2SavedGrams]. */
fun estimateRideCo2SavedGrams(ride: RecordedRide): Double =
    estimateRideCo2SavedGrams(ride.distanceMeters)

/** CO₂ saved for a ride from its aggregate [summary]; see [estimateRideCo2SavedGrams]. */
fun estimateRideCo2SavedGrams(summary: RecordedRideSummary): Double =
    estimateRideCo2SavedGrams(summary.distanceMeters)

